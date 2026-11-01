package com.college.feature.attendance.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Manages background front-camera lifecycle, lightweight frame analysis, and candidate extraction
class PassiveFaceCaptureManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private val isCapturing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewUseCase: Preview? = null

    private var analyzer: BestFrameAnalyzer? = null

    val isRunning: Boolean get() = isCapturing.get()

    // Starts sampling without a preview
    @Suppress("DEPRECATION")
    fun startCapture(onScoreUpdate: ((Double) -> Unit)?) {
        startCapture(onScoreUpdate, null)
    }

    // Binds front-facing camera to lifecycle and starts sampling frames
    @Suppress("DEPRECATION")
    fun startCapture(
        onScoreUpdate: ((Double) -> Unit)?,
        previewSurfaceProvider: Preview.SurfaceProvider?
    ) {
        if (isCapturing.get()) {
            return
        }

        // Verify camera permission before accessing hardware
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Verify front camera sensor is available on device
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(cameraSelector)) {
                    return@addListener
                }

                // Configure non-blocking frame analysis pipeline
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                val frameAnalyzer = BestFrameAnalyzer(
                    sampleIntervalMillis = 400L,
                    onBestScoreUpdated = onScoreUpdate
                )
                analyzer = frameAnalyzer

                cameraExecutor = Executors.newSingleThreadExecutor()
                frameAnalyzer.reset()
                frameAnalyzer.setPaused(false)
                imageAnalysis?.setAnalyzer(cameraExecutor!!, frameAnalyzer)

                provider.unbindAll()

                if (previewSurfaceProvider != null) {
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewSurfaceProvider)
                    }
                    previewUseCase = preview
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                }

                isCapturing.set(true)
            } catch (e: Exception) {
                android.util.Log.e("PassiveFaceCaptureManager", "Camera start error: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Captures the current locked candidate without stopping the camera feed
    fun captureCurrentCandidate(): String? {
        val bestCandidate = analyzer?.getBestCandidate() ?: return null
        return FaceImageProcessor.processCandidate(bestCandidate)
    }

    // Unbinds camera, processes best candidate frame, and returns Base64 encoded payload
    fun stopAndExtract(): String? {
        if (!isCapturing.getAndSet(false)) {
            return null
        }

        analyzer?.setPaused(true)

        try {
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
            cameraExecutor = null
        } catch (_: Exception) {}

        val bestCandidate = analyzer?.getBestCandidate() ?: return null
        val base64Result = FaceImageProcessor.processCandidate(bestCandidate)
        analyzer?.reset()
        analyzer = null

        return base64Result
    }

    // Stops camera analysis without extracting candidate
    fun stopCamera() {
        if (!isCapturing.getAndSet(false)) {
            return
        }

        analyzer?.setPaused(true)

        try {
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
            cameraExecutor = null
        } catch (_: Exception) {}

        analyzer?.reset()
        analyzer = null
    }

    // Releases all hardware and memory resources
    fun release() {
        stopCamera()
        analyzer?.release()
        analyzer = null
    }
}
