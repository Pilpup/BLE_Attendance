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

    companion object {
        // Minimum sharpness variance to qualify as a valid face candidate
        const val MIN_ACCEPTABLE_SHARPNESS = 30.0

        // Required relative improvement (10%) to justify allocating a new high-res Bitmap
        private const val SHARPNESS_IMPROVEMENT_RATIO = 1.10
    }

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

            // Ignore frames that are unacceptable or too blurry to qualify as face candidates
            if (!quality.isAcceptable || quality.sharpnessScore < MIN_ACCEPTABLE_SHARPNESS) {
                return
            }

            var updatedScore: Double? = null
            var oldBitmapToRecycle: Bitmap? = null

            synchronized(lock) {
                val currentBest = bestCandidate
                val shouldUpdate = currentBest == null ||
                    (quality.sharpnessScore >= currentBest.sharpnessScore * SHARPNESS_IMPROVEMENT_RATIO)

                if (shouldUpdate) {
                    val rawBitmap = imageProxy.toBitmap()
                    oldBitmapToRecycle = currentBest?.bitmap

                    bestCandidate = BestFrameCandidate(
                        bitmap = rawBitmap,
                        sharpnessScore = quality.sharpnessScore,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        timestamp = now
                    )
                    updatedScore = quality.sharpnessScore
                }
            }

            // Recycle previous bitmap outside the synchronized block to prevent lock contention
            oldBitmapToRecycle?.recycle()

            // Dispatch listener outside the lock to avoid lock inversion with Compose UI thread
            updatedScore?.let { score ->
                onBestScoreUpdated?.invoke(score)
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
