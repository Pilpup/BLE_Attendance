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

        // Fast early exposure probe (49 points): rejects pitch-black or blinded frames instantly
        val probeStepX = width / 8
        val probeStepY = height / 8
        var probeSum = 0L
        for (py in 1..7) {
            val rOffset = (py * probeStepY) * rowStride
            for (px in 1..7) {
                probeSum += buffer.get(rOffset + (px * probeStepX * pixelStride)).toInt() and 0xFF
            }
        }
        val earlyMean = probeSum / 49.0
        if (earlyMean < MIN_BRIGHTNESS / 2.0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = earlyMean,
                rejectionReason = "Underexposed: Scene too dark"
            )
        }
        if (earlyMean > (MAX_BRIGHTNESS + 255.0) / 2.0) {
            return FrameQuality(
                isAcceptable = false,
                sharpnessScore = 0.0,
                meanBrightness = earlyMean,
                rejectionReason = "Overexposed: Scene washed out"
            )
        }

        // Unified single pass: computes luminance brightness and discrete 2D Laplacian simultaneously
        var brightnessSum = 0L
        var laplacianSum = 0L
        var laplacianSqSum = 0L
        var sampleCount = 0

        var y = sampleStep
        val maxY = height - sampleStep
        val maxX = width - sampleStep

        while (y < maxY) {
            val rowOffset = y * rowStride
            var x = sampleStep
            while (x < maxX) {
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

        // Calculate statistical variance of Laplacian responses
        val meanLap = laplacianSum.toDouble() / sampleCount
        val variance = (laplacianSqSum.toDouble() / sampleCount) - (meanLap * meanLap)
        val sharpness = if (variance > 0.0) variance else 0.0

        return FrameQuality(
            isAcceptable = true,
            sharpnessScore = sharpness,
            meanBrightness = meanBrightness,
            rejectionReason = null
        )
    }
}
