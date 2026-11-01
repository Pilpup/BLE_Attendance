package com.college.feature.attendance.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

// Stores the best candidate frame captured during an attendance challenge
data class BestFrameCandidate(
    val bitmap: Bitmap,
    val sharpnessScore: Double,
    val rotationDegrees: Int,
    val timestamp: Long
)

// Samples incoming camera frames, measures brightness and edge sharpness, and retains the sharpest candidate
class BestFrameAnalyzer(
    private val sampleIntervalMillis: Long = 400L,
    private val onBestScoreUpdated: ((Double) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val isPaused = AtomicBoolean(false)
    private var lastSampleTimestamp = 0L

    @Volatile
    private var bestCandidate: BestFrameCandidate? = null
    private val lock = Any()

    // Evaluates incoming camera frames against lighting and sharpness criteria
    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (isPaused.get()) {
                imageProxy.close()
                return
            }

            val now = System.currentTimeMillis()
            // Throttle sampling rate to minimize CPU and battery consumption on budget devices
            if (now - lastSampleTimestamp < sampleIntervalMillis) {
                imageProxy.close()
                return
            }
            lastSampleTimestamp = now

            val yPlane = imageProxy.planes[0]
            val quality = FrameQualityScorer.scoreBuffer(
                buffer = yPlane.buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride
            )

            synchronized(lock) {
                val currentBest = bestCandidate
                val shouldUpdate = currentBest == null || (quality.isAcceptable && quality.sharpnessScore > currentBest.sharpnessScore)
                if (shouldUpdate) {
                    val rawBitmap = imageProxy.toBitmap()
                    val oldBitmap = currentBest?.bitmap

                    bestCandidate = BestFrameCandidate(
                        bitmap = rawBitmap,
                        sharpnessScore = quality.sharpnessScore,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        timestamp = now
                    )

                    oldBitmap?.recycle()
                    onBestScoreUpdated?.invoke(quality.sharpnessScore)
                }
            }
        } catch (_: Exception) {
            // Fail gracefully without crashing camera stream
        } finally {
            imageProxy.close()
        }
    }

    // Pauses or resumes frame analysis
    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
    }

    // Returns a copy of the winning candidate frame
    fun getBestCandidate(): BestFrameCandidate? {
        synchronized(lock) {
            return bestCandidate
        }
    }

    // Purges stored candidate bitmap from memory
    fun reset() {
        synchronized(lock) {
            bestCandidate?.bitmap?.recycle()
            bestCandidate = null
            lastSampleTimestamp = 0L
        }
    }

    // Releases all allocated resources
    fun release() {
        reset()
    }
}
