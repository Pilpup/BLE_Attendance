package com.college.feature.attendance.camera

import java.nio.ByteBuffer

// Holds the evaluation result for a candidate camera frame
data class FrameQuality(
    val isAcceptable: Boolean,
    val sharpnessScore: Double,
    val meanBrightness: Double,
    val rejectionReason: String? = null
)

// Evaluates raw camera luminance buffers for blur and exposure
object FrameQualityScorer {

    private const val MIN_FACE_BRIGHTNESS = 15.0
    private const val MAX_FACE_BRIGHTNESS = 240.0
    private const val MAX_BACKLIGHT_RATIO = 2.5
    private const val DEFAULT_SAMPLE_STEP = 4

    // Evaluates a raw Y-plane buffer directly without allocating bitmaps or RGB conversions
    fun scoreBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1,
        sampleStep: Int = DEFAULT_SAMPLE_STEP
    ): FrameQuality {
        if (width < 8 || height < 8) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = 0.0,
                rejectionReason = "Frame dimensions too small"
            )
        }

        buffer.rewind()

        // Fast early exposure probe (49 points) comparing perimeter to center
        val probeStepX = width / 8
        val probeStepY = height / 8
        var perimeterSum = 0L
        var perimeterCount = 0
        var centerSum = 0L
        var centerCount = 0

        for (py in 1..7) {
            val rOffset = (py * probeStepY) * rowStride
            val isPerimeterY = (py == 1 || py == 7)
            for (px in 1..7) {
                val isPerimeterX = (px == 1 || px == 7)
                val pixel = buffer.get(rOffset + (px * probeStepX * pixelStride)).toInt() and 0xFF
                if (isPerimeterX || isPerimeterY) {
                    perimeterSum += pixel
                    perimeterCount++
                } else if (px in 3..5 && py in 3..5) {
                    centerSum += pixel
                    centerCount++
                }
            }
        }

        val earlyCenterMean = centerSum.toDouble() / centerCount
        val earlyPerimeterMean = perimeterSum.toDouble() / perimeterCount

        if (earlyCenterMean < MIN_FACE_BRIGHTNESS / 2.0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = earlyCenterMean,
                rejectionReason = "Underexposed: Face area too dark"
            )
        }
        if (earlyCenterMean > (MAX_FACE_BRIGHTNESS + 255.0) / 2.0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = earlyCenterMean,
                rejectionReason = "Overexposed: Face area washed out"
            )
        }
        if (earlyPerimeterMean > 150.0 && earlyCenterMean < 50.0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = earlyCenterMean,
                rejectionReason = "Backlit: Face silhouetted by background light"
            )
        }

        // Center ROI bounds where face resides (matches downstream square crop)
        val minRoiX = (width * 0.25).toInt().coerceAtLeast(sampleStep)
        val maxRoiX = (width * 0.75).toInt().coerceAtMost(width - sampleStep)
        val minRoiY = (height * 0.20).toInt().coerceAtLeast(sampleStep)
        val maxRoiY = (height * 0.80).toInt().coerceAtMost(height - sampleStep)

        // Single-pass center ROI evaluation: measures face luminance and edge sharpness
        var brightnessSum = 0L
        var laplacianSum = 0L
        var laplacianSqSum = 0L
        var sampleCount = 0

        var y = minRoiY
        while (y < maxRoiY) {
            val rowOffset = y * rowStride
            var x = minRoiX
            while (x < maxRoiX) {
                val centerIdx = rowOffset + (x * pixelStride)
                val center = buffer.get(centerIdx).toInt() and 0xFF
                val up = buffer.get(centerIdx - rowStride).toInt() and 0xFF
                val down = buffer.get(centerIdx + rowStride).toInt() and 0xFF
                val left = buffer.get(centerIdx - pixelStride).toInt() and 0xFF
                val right = buffer.get(centerIdx + pixelStride).toInt() and 0xFF

                // Center pixel IS the sampled luminance
                brightnessSum += center

                // 2D 5-point discrete Laplacian convolution stencil
                val laplacian = (up + down + left + right) - (center shl 2)
                laplacianSum += laplacian
                laplacianSqSum += laplacian.toLong() * laplacian
                sampleCount++

                x += sampleStep
            }
            y += sampleStep
        }

        if (sampleCount == 0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = 0.0,
                rejectionReason = "Empty sample buffer"
            )
        }

        val faceBrightness = brightnessSum.toDouble() / sampleCount

        // Enforce face region exposure thresholds
        if (faceBrightness < MIN_FACE_BRIGHTNESS) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = faceBrightness,
                rejectionReason = "Underexposed: Face area too dark"
            )
        }

        if (faceBrightness > MAX_FACE_BRIGHTNESS) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = faceBrightness,
                rejectionReason = "Overexposed: Face area washed out"
            )
        }

        // Reject silhouetted frames where background overpowers face
        if (earlyPerimeterMean > 140.0 && (earlyPerimeterMean / faceBrightness.coerceAtLeast(1.0)) > MAX_BACKLIGHT_RATIO) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = faceBrightness,
                rejectionReason = "Backlit: Face silhouetted by background light"
            )
        }

        // Calculate statistical variance of Laplacian responses strictly within face ROI
        val meanLap = laplacianSum.toDouble() / sampleCount
        val variance = (laplacianSqSum.toDouble() / sampleCount) - (meanLap * meanLap)
        val sharpness = if (variance > 0.0) variance else 0.0

        return FrameQuality(
            isAcceptable = true,
            sharpnessScore = sharpness,
            meanBrightness = faceBrightness,
            rejectionReason = null
        )
    }
}
