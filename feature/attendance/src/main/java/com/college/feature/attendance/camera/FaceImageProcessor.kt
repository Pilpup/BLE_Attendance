package com.college.feature.attendance.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

// Normalizes and encodes raw candidate camera frames for server-side biometric verification
object FaceImageProcessor {

    private const val TARGET_DIMENSION = 224
    private const val JPEG_COMPRESSION_QUALITY = 80

    // Transforms candidate frame into normalized Base64 JPEG string
    fun processCandidate(candidate: BestFrameCandidate): String? {
        val originalBitmap = candidate.bitmap
        if (originalBitmap.isRecycled) {
            return null
        }

        var rotatedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            // Correct physical camera sensor orientation
            val rotationMatrix = Matrix().apply {
                postRotate(candidate.rotationDegrees.toFloat())
            }

            rotatedBitmap = Bitmap.createBitmap(
                originalBitmap,
                0,
                0,
                originalBitmap.width,
                originalBitmap.height,
                rotationMatrix,
                true
            )

            val squareDimension = min(rotatedBitmap.width, rotatedBitmap.height)
            val cropX = (rotatedBitmap.width - squareDimension) / 2
            val cropY = (rotatedBitmap.height - squareDimension) / 2

            croppedBitmap = Bitmap.createBitmap(
                rotatedBitmap,
                cropX,
                cropY,
                squareDimension,
                squareDimension
            )

            scaledBitmap = Bitmap.createScaledBitmap(
                croppedBitmap,
                TARGET_DIMENSION,
                TARGET_DIMENSION,
                true
            )

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, outputStream)
            val jpegBytes = outputStream.toByteArray()

            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("FaceImageProcessor", "Error processing face frame: ${e.message}", e)
            null
        } finally {
            if (rotatedBitmap != null && rotatedBitmap !== originalBitmap) {
                rotatedBitmap.recycle()
            }
            if (croppedBitmap != null && croppedBitmap !== rotatedBitmap) {
                croppedBitmap.recycle()
            }
            if (scaledBitmap != null && scaledBitmap !== croppedBitmap) {
                scaledBitmap.recycle()
            }
        }
    }
}
