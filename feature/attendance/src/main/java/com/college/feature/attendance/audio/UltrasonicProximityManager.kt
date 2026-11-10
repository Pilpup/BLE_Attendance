package com.college.feature.attendance.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper

// Tracks energy envelope and peak power of incoming ultrasonic bursts
private class BurstEnvelopeTracker(
    private val maxBurstDurationMs: Long = 160L,
    private val quietChunksThreshold: Int = 2
) {
    var burstActive: Boolean = false
        private set
    var burstPeakPower: Double = 0.0
        private set
    var burstPeakPowerDb: Double = 0.0
        private set
    var burstPeakSnrDb: Double = 0.0
        private set
    private var burstQuietChunks: Int = 0
    private var burstStartTime: Long = 0L

    // Updates envelope with new audio chunk detection; returns true if a burst just finalized
    fun update(detection: UltrasonicDetectionResult, now: Long): Boolean {
        if (detection.isDetected) {
            if (!burstActive) {
                burstActive = true
                burstStartTime = now
                burstPeakPower = detection.targetPower
                burstPeakPowerDb = detection.powerLevelDb
                burstPeakSnrDb = detection.snrDb
                burstQuietChunks = 0
            } else {
                if (detection.targetPower > burstPeakPower) {
                    burstPeakPower = detection.targetPower
                    burstPeakPowerDb = detection.powerLevelDb
                    burstPeakSnrDb = detection.snrDb
                }
                burstQuietChunks = 0
            }
        } else if (burstActive) {
            burstQuietChunks++
        }

        return burstActive && (burstQuietChunks >= quietChunksThreshold || (now - burstStartTime) > maxBurstDurationMs)
    }

    // Resets tracker state after burst completion or session interruption
    fun reset() {
        burstActive = false
        burstPeakPower = 0.0
        burstPeakPowerDb = 0.0
        burstPeakSnrDb = 0.0
        burstQuietChunks = 0
        burstStartTime = 0L
    }
}

// Manages ultrasonic acoustic transmission and detection for physical desk co-location
class UltrasonicProximityManager(private val context: Context) {

    init {
        // R11 (review §2.6): resolve the device's effective audio sample rate
        // exactly once, before any chirp is queued — this manager is the only
        // owner of the ultrasonic handshake, so its construction is the
        // guaranteed-earliest hook. 44.1 kHz-locked devices previously died
        // silently at AudioRecord init.
        UltrasonicToneSynthesizer.resolveEffectiveSampleRate(context)
    }

    private val isListening = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isDiagnosticRunning = AtomicBoolean(false)
    private var diagnosticThread: Thread? = null

    private val isBeaconEmitting = AtomicBoolean(false)
    private var beaconThread: Thread? = null

    // Creates an AudioRecord prioritizing UNPROCESSED (raw acoustics, no AGC) with graceful fallbacks
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize.coerceAtLeast(sampleRate / 10)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val supportsUnprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val sourcesToTry = mutableListOf<Int>()
        if (supportsUnprocessed) {
            sourcesToTry.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sourcesToTry.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sourcesToTry.add(MediaRecorder.AudioSource.MIC)

        for (source in sourcesToTry) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    android.util.Log.i("UltrasonicProximity", "AudioRecord initialized using source: $source")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                android.util.Log.w("UltrasonicProximity", "Source $source unavailable: ${e.message}")
            }
        }
        return null
    }

    // Starts live continuous ultrasonic radar to measure real-time distance and SNR
    @SuppressLint("MissingPermission")
    fun startLiveDiagnostic(
        calibratedRefPowerDb: Double = GoertzelDetector.CALIBRATED_REF_POWER_DB,
        onMetric: (UltrasonicLiveMetric) -> Unit
    ) {
        if (isDiagnosticRunning.get()) return

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return

        isDiagnosticRunning.set(true)
        val diagnosticReader = Thread {
            // R11 (review §2.7): LINUX niceness for THIS reader thread only —
            // AudioRecord.read starvation was the diagnostic's failure mode on
            // loaded devices. Replaces the old java-side
            // Thread.MAX_PRIORITY, which fought every other thread (the OS
            // scheduler maps niceness properly for audio-critical paths).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            // R11 (review §2.6): 44.1 kHz devices init AudioRecord at their
            // HAL rate; 48 kHz previously failed every source fallback.
            val sampleRate = UltrasonicToneSynthesizer.EFFECTIVE_SAMPLE_RATE
            var record: AudioRecord? = null
            try {
                record = createAudioRecord(sampleRate)

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    isDiagnosticRunning.set(false)
                    return@Thread
                }

                record.startRecording()
                val audioBuffer = ShortArray(1024)
                val tracker = BurstEnvelopeTracker()
                var lastMetricPostMs = 0L

                while (isDiagnosticRunning.get()) {
                    val readSamples = record.read(audioBuffer, 0, audioBuffer.size)
                    if (readSamples > 0) {
                        val detection = GoertzelDetector.evaluateChirp(
                            samples = audioBuffer,
                            targetFreqHz = UltrasonicToneSynthesizer.DEFAULT_FREQUENCY_HZ,
                            sampleRate = sampleRate,
                            snrThresholdDb = 6.0,
                            refPowerDb = calibratedRefPowerDb
                        )

                        val now = System.currentTimeMillis()
                        val burstEnded = tracker.update(detection, now)

                        var finalizedDist = -1.0
                        var isCoLocated = false
                        var emitFinalized = false

                        if (burstEnded) {
                            finalizedDist = GoertzelDetector.calculateDistanceCm(
                                powerDb = tracker.burstPeakPowerDb,
                                refPowerDb = calibratedRefPowerDb
                            )
                            isCoLocated = finalizedDist in 0.1..20.0
                            emitFinalized = true
                            tracker.reset()
                        }

                        val metric = UltrasonicLiveMetric(
                            snrDb = detection.snrDb,
                            targetPower = detection.targetPower,
                            powerLevelDb = detection.powerLevelDb,
                            noisePower = detection.noisePower,
                            estimatedDistanceCm = if (emitFinalized) finalizedDist else -1.0,
                            isChirpDetected = detection.isDetected,
                            isCoLocatedSameDesk = isCoLocated,
                            isBurstFinalized = emitFinalized,
                            burstPeakPowerDb = tracker.burstPeakPowerDb,
                            burstPeakSnrDb = tracker.burstPeakSnrDb
                        )
                        // Diagnostic metric stream: audio buffers arrive every
                        // ~21 ms (48 Hz). Posting at that cadence floods the
                        // main looper and thrashes recompositions — coalesce
                        // to ~5 Hz, but keep finalized burst results immediate.
                        if (emitFinalized || now - lastMetricPostMs >= 200) {
                            lastMetricPostMs = now
                            mainHandler.post { onMetric(metric) }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UltrasonicDiagnostic", "Diagnostic listening error: ${e.message}", e)
            } finally {
                try {
                    record?.stop()
                    record?.release()
                } catch (_: Exception) {}
                isDiagnosticRunning.set(false)
            }
        }
        // R11 (review §2.7): no java-side MAX_PRIORITY — the urgent-audio
        // niceness above is set inside the body and is the correct mechanism.
        // Assign then start the SAME object: a stopLiveDiagnostic() racing in
        // between must see at most one started Thread, and a thread it clears
        // before start() exits immediately via the flag anyway.
        diagnosticThread = diagnosticReader
        diagnosticReader.start()
    }

    // Stops live diagnostic radar
    fun stopLiveDiagnostic() {
        isDiagnosticRunning.set(false)
        diagnosticThread?.interrupt()
        diagnosticThread = null
    }

    // Starts emitting continuous 19.2 kHz acoustic pings at regular intervals
    fun startBeaconEmitter(intervalMs: Long = 700L, onPingSent: () -> Unit = {}) {
        if (isBeaconEmitting.get()) return
        isBeaconEmitting.set(true)
        beaconThread = Thread {
            while (isBeaconEmitting.get()) {
                emitChirp()
                mainHandler.post { onPingSent() }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
    }

    // Stops emitting acoustic beacon
    fun stopBeaconEmitter() {
        isBeaconEmitting.set(false)
        beaconThread?.interrupt()
        beaconThread = null
    }

    // Emits a rapid burst sequence (e.g. 50 or 100 bursts) for statistical ensemble testing
    fun emitBurstSequence(
        count: Int = 100,
        intervalMs: Long = 130L,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: () -> Unit = {}
    ): Boolean {
        // R11 (review §2.4): report whether the sequence actually STARTED. A
        // busy emitter previously swallowed the request silently — the caller
        // marked the peer as tested without a single chirp played. Busy now
        // returns false so the peer can be un-blacklisted and retried on its
        // next close-proximity sighting.
        if (isBeaconEmitting.get()) return false
        isBeaconEmitting.set(true)
        beaconThread = Thread {
            for (i in 1..count) {
                if (!isBeaconEmitting.get()) break
                emitChirp()
                mainHandler.post { onProgress(i, count) }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
            isBeaconEmitting.set(false)
            beaconThread = null
            mainHandler.post { onComplete() }
        }.also { it.start() }
        return true
    }

    // Transmits an inaudible Hann-windowed 19.2 kHz acoustic ping
    fun emitChirp(onComplete: (() -> Unit)? = null) {
        UltrasonicToneSynthesizer.playChirp(
            frequencyHz = UltrasonicToneSynthesizer.DEFAULT_FREQUENCY_HZ,
            // R11 (review §2.6): match the mic side — one device-wide rate.
            sampleRate = UltrasonicToneSynthesizer.EFFECTIVE_SAMPLE_RATE,
            durationMs = UltrasonicToneSynthesizer.DEFAULT_DURATION_MS,
            onComplete = onComplete
        )
    }

    // Listens for incoming ultrasonic chirps within a bounded listening window
    @SuppressLint("MissingPermission")
    fun listenForChirp(
        listenDurationMs: Long = 400L,
        onResult: (UltrasonicDetectionResult) -> Unit
    ) {
        if (isListening.get()) {
            mainHandler.post {
                onResult(UltrasonicDetectionResult(isDetected = false, snrDb = 0.0, targetPower = 0.0))
            }
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            mainHandler.post {
                onResult(UltrasonicDetectionResult(isDetected = false, snrDb = 0.0, targetPower = 0.0))
            }
            return
        }

        executor.execute {
            isListening.set(true)
            // R11 (review §2.6): effective device rate — 44.1 kHz HALs no
            // longer fail AudioRecord init at a forced 48 kHz.
            val sampleRate = UltrasonicToneSynthesizer.EFFECTIVE_SAMPLE_RATE
            var record: AudioRecord? = null
            var bestDetection = UltrasonicDetectionResult(isDetected = false, snrDb = 0.0, targetPower = 0.0)

            try {
                record = createAudioRecord(sampleRate)

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    mainHandler.post { onResult(bestDetection) }
                    return@execute
                }

                audioRecord = record
                record.startRecording()

                val audioBuffer = ShortArray(1024)
                val startTime = System.currentTimeMillis()
                var burstStarted = false
                var burstQuietCount = 0

                while (isListening.get() && (System.currentTimeMillis() - startTime) < listenDurationMs) {
                    val readSamples = record.read(audioBuffer, 0, audioBuffer.size)
                    if (readSamples > 0) {
                        val detection = GoertzelDetector.evaluateChirp(
                            samples = audioBuffer,
                            targetFreqHz = UltrasonicToneSynthesizer.DEFAULT_FREQUENCY_HZ,
                            sampleRate = sampleRate
                        )

                        if (detection.targetPower > bestDetection.targetPower) {
                            bestDetection = detection
                        }

                        if (detection.isDetected) {
                            burstStarted = true
                            burstQuietCount = 0
                        } else if (burstStarted) {
                            burstQuietCount++
                            // If burst completed, finish early with peak detection
                            if (burstQuietCount >= 3) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UltrasonicManager", "Audio listening error: ${e.message}", e)
            } finally {
                try {
                    record?.stop()
                    record?.release()
                } catch (_: Exception) {}
                audioRecord = null
                isListening.set(false)
                mainHandler.post { onResult(bestDetection) }
            }
        }
    }

    // Listens for incoming ultrasonic burst sequence and computes statistical ensemble consensus
    @SuppressLint("MissingPermission")
    fun collectBurstEnsemble(
        listenDurationMs: Long = 1800L,
        calibratedRefPowerDb: Double = GoertzelDetector.CALIBRATED_REF_POWER_DB,
        onComplete: (AcousticStatisticalSummary) -> Unit
    ): Boolean {
        if (isListening.get()) {
            // R11 (review §2.4): busy — return false WITHOUT posting a
            // fabricated empty summary. The empty summary used to run the
            // caller's verdict path and mark the pair code-separated while
            // nothing had been recorded; the VM now un-blacklists the peer and
            // retries once the mic is idle.
            return false
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            // Permission missing: not retriable by definition — retrying a
            // permanently denied or withdrawn mic can never produce a verdict.
            // Keep the historical behavior (empty summary + true) so the
            // caller does not un-blacklist-loop this peer forever.
            val emptySummary = GoertzelDetector.computeTrimmedMeanStats(emptyList(), calibratedRefPowerDb)
            mainHandler.post { onComplete(emptySummary) }
            return true
        }

        executor.execute {
            isListening.set(true)
            // R11 (review §2.6): effective device rate for the ensemble mic
            // read; Goertzel coefficients accept either verified rate.
            val sampleRate = UltrasonicToneSynthesizer.EFFECTIVE_SAMPLE_RATE
            var record: AudioRecord? = null
            val burstPowers = mutableListOf<Double>()

            try {
                record = createAudioRecord(sampleRate)

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    val emptySummary = GoertzelDetector.computeTrimmedMeanStats(emptyList(), calibratedRefPowerDb)
                    mainHandler.post { onComplete(emptySummary) }
                    return@execute
                }

                audioRecord = record
                record.startRecording()

                val audioBuffer = ShortArray(1024)
                val tracker = BurstEnvelopeTracker()
                val startTime = System.currentTimeMillis()

                while (isListening.get() && (System.currentTimeMillis() - startTime) < listenDurationMs) {
                    val readSamples = record.read(audioBuffer, 0, audioBuffer.size)
                    if (readSamples > 0) {
                        val detection = GoertzelDetector.evaluateChirp(
                            samples = audioBuffer,
                            targetFreqHz = UltrasonicToneSynthesizer.DEFAULT_FREQUENCY_HZ,
                            sampleRate = sampleRate,
                            snrThresholdDb = 6.0,
                            refPowerDb = calibratedRefPowerDb
                        )

                        val now = System.currentTimeMillis()
                        val burstEnded = tracker.update(detection, now)
                        if (burstEnded) {
                            burstPowers.add(tracker.burstPeakPowerDb)
                            tracker.reset()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UltrasonicProximity", "Ensemble listening error: ${e.message}", e)
            } finally {
                try {
                    record?.stop()
                    record?.release()
                } catch (_: Exception) {}
                audioRecord = null
                isListening.set(false)

                val summary = GoertzelDetector.computeTrimmedMeanStats(burstPowers, calibratedRefPowerDb)
                mainHandler.post { onComplete(summary) }
            }
        }
        // Accepted: the executor job owns completion now (finally → onComplete).
        return true
    }

    // Stops any active listening and releases resources
    fun release() {
        stopLiveDiagnostic()
        stopBeaconEmitter()
        isListening.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        try {
            executor.shutdownNow()
        } catch (_: Exception) {}
        UltrasonicToneSynthesizer.release()
    }
}

// Live real-time acoustic signal telemetry container
data class UltrasonicLiveMetric(
    val snrDb: Double,
    val targetPower: Double,
    val powerLevelDb: Double = 0.0,
    val noisePower: Double,
    val estimatedDistanceCm: Double,
    val isChirpDetected: Boolean,
    val isCoLocatedSameDesk: Boolean,
    val isBurstFinalized: Boolean = false,
    val burstPeakPowerDb: Double = 0.0,
    val burstPeakSnrDb: Double = 0.0
)
