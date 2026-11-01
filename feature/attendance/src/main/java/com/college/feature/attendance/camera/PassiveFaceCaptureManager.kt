package com.college.feature.attendance.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    companion object {
        private const val TAG = "PassiveFaceCapture"
    }

    private val isCapturing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewUseCase: Preview? = null

    private var analyzer: BestFrameAnalyzer? = null

    val isRunning: Boolean get() = isCapturing.get()

    // Starts sampling without a preview
    fun startCapture(onScoreUpdate: ((Double) -> Unit)?) {
        startCapture(onScoreUpdate, null)
    }

    // Binds front-facing camera to lifecycle and starts sampling frames
    fun startCapture(
        onScoreUpdate: ((Double) -> Unit)?,
        previewSurfaceProvider: Preview.SurfaceProvider?
    ) {
        // Prevent concurrent or duplicate initialization
        if (!isCapturing.compareAndSet(false, true)) {
            return
        }

        // Verify camera permission before accessing hardware
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            isCapturing.set(false)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            // Abort if capture was cancelled while provider was loading
            if (!isCapturing.get()) {
                return@addListener
            }

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Verify front camera sensor is available on device
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(cameraSelector)) {
                    isCapturing.set(false)
                    return@addListener
                }

                // Modern 640x480 resolution selector for CameraX 1.3+
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Configure non-blocking frame analysis pipeline
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
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

                val camera = if (previewSurfaceProvider != null) {
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewSurfaceProvider)
                    }
                    previewUseCase = preview
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                }

                // Prioritize auto-exposure and auto-focus on the central face region
                try {
                    val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val centerPoint = factory.createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(
                        centerPoint,
                        FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AF
                    ).build()
                    camera.cameraControl.startFocusAndMetering(action)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Camera start error: ${e.message}", e)
                stopCamera()
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
        if (!isCapturing.get()) {
            return null
        }
        val bestCandidate = analyzer?.getBestCandidate()
        val base64Result = bestCandidate?.let { FaceImageProcessor.processCandidate(it) }
        stopCamera()
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
        imageAnalysis = null
        previewUseCase = null
    }

    // Releases all hardware and memory resources
    fun release() {
        stopCamera()
    }
}
