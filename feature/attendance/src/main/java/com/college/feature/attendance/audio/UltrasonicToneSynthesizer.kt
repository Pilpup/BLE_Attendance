package com.college.feature.attendance.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Synthesizes and transmits Hann-windowed ultrasonic acoustic bursts
object UltrasonicToneSynthesizer {

    const val DEFAULT_FREQUENCY_HZ = 19200.0
    const val DEFAULT_SAMPLE_RATE = 48000
    const val DEFAULT_DURATION_MS = 80

    // Generates a Hann-windowed PCM-16 audio buffer for an inaudible tone burst
    fun generateToneBuffer(
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_DURATION_MS,
        amplitudeScale: Float = 0.85f
    ): ShortArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(totalSamples)
        val angularFrequency = 2.0 * PI * frequencyHz / sampleRate

        for (i in 0 until totalSamples) {
            // Apply Hann window to eliminate transient speaker clicks
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (totalSamples - 1)))
            val sine = sin(angularFrequency * i)
            val sample = (sine * hann * Short.MAX_VALUE * amplitudeScale).toInt()
            buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buffer
    }

    // Emits a single silent ultrasonic pulse through the device loudspeaker
    fun playChirp(
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_DURATION_MS,
        amplitudeScale: Float = 0.85f,
        onComplete: (() -> Unit)? = null
    ) {
        try {
            val buffer = generateToneBuffer(frequencyHz, sampleRate, durationMs, amplitudeScale)
            val bufferSizeBytes = buffer.size * 2

            val audioTrack = AudioTrack.Builder()
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
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.setVolume(1.0f)
            audioTrack.setNotificationMarkerPosition(buffer.size)
            audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    try {
                        track?.stop()
                        track?.release()
                    } catch (_: Exception) {
                    }
                    onComplete?.invoke()
                }

                override fun onPeriodicNotification(track: AudioTrack?) {}
            })
            audioTrack.play()
        } catch (_: Exception) {
            onComplete?.invoke()
        }
    }
}
