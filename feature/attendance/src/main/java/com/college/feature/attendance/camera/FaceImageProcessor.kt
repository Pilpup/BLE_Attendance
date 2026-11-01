package com.college.feature.attendance.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.min

// Normalizes and encodes raw candidate camera frames for server-side biometric verification
object FaceImageProcessor {

    private const val TAG = "FaceImageProcessor"
    private const val TARGET_DIMENSION = 224
    private const val JPEG_COMPRESSION_QUALITY = 80
    private const val INITIAL_JPEG_STREAM_CAPACITY = 16 * 1024

    // Transforms candidate frame into normalized Base64 JPEG string
    fun processCandidate(candidate: BestFrameCandidate): String? {
        val originalBitmap = candidate.bitmap
        if (originalBitmap.isRecycled || originalBitmap.width <= 0 || originalBitmap.height <= 0) {
            return null
        }

        var processedBitmap: Bitmap? = null

        return try {
            val width = originalBitmap.width
            val height = originalBitmap.height
            val squareDimension = min(width, height)
            val cropX = (width - squareDimension) / 2
            val cropY = (height - squareDimension) / 2

            // Single composite matrix: crops, rotates, and downscales in one operation
            val matrix = Matrix().apply {
                if (candidate.rotationDegrees != 0) {
                    postRotate(candidate.rotationDegrees.toFloat())
                }
                val scale = TARGET_DIMENSION.toFloat() / squareDimension.toFloat()
                postScale(scale, scale)
            }

            // Directly produces the 224x224 target bitmap, eliminating ~13MB of temporary intermediates
            processedBitmap = Bitmap.createBitmap(
                originalBitmap,
                cropX,
                cropY,
                squareDimension,
                squareDimension,
                matrix,
                true
            )

            val outputStream = ByteArrayOutputStream(INITIAL_JPEG_STREAM_CAPACITY)
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, outputStream)
            val jpegBytes = outputStream.toByteArray()

            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing face frame: ${e.message}", e)
            null
        } finally {
            if (processedBitmap != null && processedBitmap !== originalBitmap) {
                processedBitmap.recycle()
            }
        }
    }
}
