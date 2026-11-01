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

    private const val MIN_BRIGHTNESS = 15.0
    private const val MAX_BRIGHTNESS = 245.0
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

        // Sample pixel luminance to determine average scene brightness
        var brightnessSum = 0L
        var sampleCount = 0

        var y = 0
        while (y < height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < width) {
                val pixel = buffer.get(rowOffset + (x * pixelStride)).toInt() and 0xFF
                brightnessSum += pixel
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

        val meanBrightness = brightnessSum.toDouble() / sampleCount

        // Enforce acceptable exposure thresholds
        if (meanBrightness < MIN_BRIGHTNESS) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = meanBrightness,
                rejectionReason = "Underexposed: Scene too dark"
            )
        }

        if (meanBrightness > MAX_BRIGHTNESS) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = meanBrightness,
                rejectionReason = "Overexposed: Scene washed out"
            )
        }

        // Measure edge sharpness using discrete 2D Laplacian operator
        var laplacianSum = 0.0
        var laplacianSqSum = 0.0
        var edgeCount = 0

        y = sampleStep
        while (y < height - sampleStep) {
            val rowOffset = y * rowStride
            val rowAbove = (y - 1) * rowStride
            val rowBelow = (y + 1) * rowStride

            var x = sampleStep
            while (x < width - sampleStep) {
                val center = buffer.get(rowOffset + x * pixelStride).toInt() and 0xFF
                val up = buffer.get(rowAbove + x * pixelStride).toInt() and 0xFF
                val down = buffer.get(rowBelow + x * pixelStride).toInt() and 0xFF
                val left = buffer.get(rowOffset + (x - 1) * pixelStride).toInt() and 0xFF
                val right = buffer.get(rowOffset + (x + 1) * pixelStride).toInt() and 0xFF

                val laplacian = (up + down + left + right) - (4 * center)
                val lapDouble = laplacian.toDouble()

                laplacianSum += lapDouble
                laplacianSqSum += lapDouble * lapDouble
                edgeCount++

                x += sampleStep
            }
            y += sampleStep
        }

        if (edgeCount == 0) {
            return FrameQuality(
                isAcceptable = true,
                sharpnessScore = 0.0,
                meanBrightness = meanBrightness
            )
        }

        // Calculate statistical variance of Laplacian responses
        val meanLaplacian = laplacianSum / edgeCount
        val variance = (laplacianSqSum / edgeCount) - (meanLaplacian * meanLaplacian)
        val sharpness = if (variance > 0.0) variance else 0.0

        return FrameQuality(
            isAcceptable = true,
            sharpnessScore = sharpness,
            meanBrightness = meanBrightness,
            rejectionReason = null
        )
    }
}
