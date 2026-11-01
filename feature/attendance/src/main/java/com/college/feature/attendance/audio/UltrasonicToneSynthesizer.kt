package com.college.feature.attendance.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Synthesizes and transmits Hann-windowed ultrasonic acoustic bursts
object UltrasonicToneSynthesizer {

    const val DEFAULT_FREQUENCY_HZ = 19200.0
    const val DEFAULT_SAMPLE_RATE = 48000
    const val DEFAULT_DURATION_MS = 80
    const val DEFAULT_AMPLITUDE_SCALE = 0.85f

    // Cached PCM buffer and reusable AudioTrack for default 19.2 kHz chirps
    @Volatile
    private var cachedDefaultBuffer: ShortArray? = null
    @Volatile
    private var reusableTrack: AudioTrack? = null
    @Volatile
    private var activeCompletionCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Generates or returns cached Hann-windowed PCM-16 audio buffer
    fun generateToneBuffer(
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_DURATION_MS,
        amplitudeScale: Float = DEFAULT_AMPLITUDE_SCALE
    ): ShortArray {
        val isDefault = frequencyHz == DEFAULT_FREQUENCY_HZ &&
            sampleRate == DEFAULT_SAMPLE_RATE &&
            durationMs == DEFAULT_DURATION_MS &&
            amplitudeScale == DEFAULT_AMPLITUDE_SCALE

        if (isDefault) {
            cachedDefaultBuffer?.let { return it }
        }

        val totalSamples = (sampleRate * durationMs) / 1000
        val buffer = ShortArray(totalSamples)
        val angularFrequency = 2.0 * PI * frequencyHz / sampleRate
        // Hoist invariant amplitude calculation outside synthesis loop
        val peakAmplitude = Short.MAX_VALUE * amplitudeScale

        for (i in 0 until totalSamples) {
            // Hann window eliminates transient speaker clicks
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (totalSamples - 1)))
            val sine = sin(angularFrequency * i)
            val sample = (sine * hann * peakAmplitude).toInt()
            buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        if (isDefault) {
            cachedDefaultBuffer = buffer
        }
        return buffer
    }

    // Emits a silent ultrasonic pulse with pooled AudioTrack and timeout safety
    fun playChirp(
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_DURATION_MS,
        amplitudeScale: Float = DEFAULT_AMPLITUDE_SCALE,
        onComplete: (() -> Unit)? = null
    ) {
        val isDefault = frequencyHz == DEFAULT_FREQUENCY_HZ &&
            sampleRate == DEFAULT_SAMPLE_RATE &&
            durationMs == DEFAULT_DURATION_MS &&
            amplitudeScale == DEFAULT_AMPLITUDE_SCALE

        val completed = AtomicBoolean(false)
        val notifyComplete = {
            if (completed.compareAndSet(false, true)) {
                onComplete?.invoke()
            }
        }

        // Safety watchdog: guarantees callback fires even if hardware marker drops
        mainHandler.postDelayed({ notifyComplete() }, durationMs + 50L)

        try {
            val buffer = generateToneBuffer(frequencyHz, sampleRate, durationMs, amplitudeScale)

            if (isDefault) {
                playReusableChirp(buffer, sampleRate, notifyComplete)
            } else {
                playOneShotChirp(buffer, sampleRate, notifyComplete)
            }
        } catch (_: Exception) {
            notifyComplete()
        }
    }

    // Reuses static AudioTrack to prevent AudioFlinger track exhaustion during burst sequences
    @Synchronized
    private fun playReusableChirp(buffer: ShortArray, sampleRate: Int, onFinish: () -> Unit) {
        var track = reusableTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            track = buildAudioTrack(buffer, sampleRate)
            // Attach single persistent listener to avoid allocating callback objects on every chirp
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    activeCompletionCallback?.invoke()
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            reusableTrack = track
        }

        activeCompletionCallback = onFinish
        try {
            track.stop()
            track.setPlaybackHeadPosition(0)
            track.setNotificationMarkerPosition(buffer.size - 1)
            track.play()
        } catch (_: Exception) {
            onFinish()
        }
    }

    // Builds and plays a one-shot track for non-default frequency parameters
    private fun playOneShotChirp(buffer: ShortArray, sampleRate: Int, onFinish: () -> Unit) {
        var track: AudioTrack? = null
        try {
            track = buildAudioTrack(buffer, sampleRate)
            track.setNotificationMarkerPosition(buffer.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    try {
                        t?.stop()
                        t?.release()
                    } catch (_: Exception) {}
                    onFinish()
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (_: Exception) {
            try { track?.release() } catch (_: Exception) {}
            onFinish()
        }
    }

    // Creates an initialized static AudioTrack instance
    private fun buildAudioTrack(buffer: ShortArray, sampleRate: Int): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        return track
    }

    // Releases cached audio track hardware resources
    @Synchronized
    fun release() {
        try {
            reusableTrack?.stop()
            reusableTrack?.release()
        } catch (_: Exception) {}
        reusableTrack = null
        activeCompletionCallback = null
    }
}
