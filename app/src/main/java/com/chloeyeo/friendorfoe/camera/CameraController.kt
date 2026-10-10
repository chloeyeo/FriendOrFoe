package com.chloeyeo.friendorfoe.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.chloeyeo.friendorfoe.app.RuntimeLane
import com.chloeyeo.friendorfoe.detect.Frame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX Preview + ImageAnalysis feeding upright, tightly packed [Frame]s to the runtime lane (D-01, D-02).
 *
 * - D-01: one `Preview` and one `ImageAnalysis` bound in a single `UseCaseGroup` with the [PreviewView]'s
 *   `ViewPort`, back camera; both request 16:9 (`RATIO_16_9_FALLBACK_AUTO_STRATEGY`), the analysis targets
 *   [targetSize] (1280x720 default), so [FrameTransform]'s FILL_CENTER math on the full frame matches the preview.
 *   The activity is landscape-locked, so `rotationDegrees` is a per-device constant (0 expected); it is logged on
 *   the first frame and applied by [RgbaPacker], never by CameraX's output-rotation option.
 * - D-02: `STRATEGY_KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_RGBA_8888`, own `camera-analysis` thread. The analyzer
 *   copies the plane into a tight frame on that thread, closes the `ImageProxy` in `finally`, and hands the frame
 *   to [RuntimeLane] only when the lane is idle (`busy` gate): at most one frame in flight, the rest dropped, never
 *   queued.
 */
class CameraController(private val context: Context, private val targetSize: Size = Size(1280, 720)) {
    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "camera-analysis").apply { isDaemon = true } }

    @Volatile private var provider: ProcessCameraProvider? = null
    private val busy = AtomicBoolean(false)
    private val logged = AtomicBoolean(false)

    /** The first frame's geometry (size, rotation, cropRect, strides, format) for the checkpoint record. */
    @Volatile var firstFrameInfo: String? = null
        private set

    /** Binds preview and analysis; [onFrame] is called on the runtime lane with each upright, tightly packed frame. */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onFrame: (Frame) -> Unit) {
        // Pitfall C1: PreviewView.viewPort is null until the view has a size.
        previewView.doOnLayout {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = future.get()
                provider = p
                val aspect16x9 = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect16x9).build())
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect16x9)
                            .setResolutionStrategy(ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy, onFrame) }
                val group = UseCaseGroup.Builder()
                    .setViewPort(requireNotNull(previewView.viewPort) { "PreviewView has no ViewPort after layout" })
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build()
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                Log.i(TAG, "camera bound target=$targetSize")
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun analyze(proxy: ImageProxy, onFrame: (Frame) -> Unit) {
        try {
            logOnce(proxy)
            if (!busy.compareAndSet(false, true)) return
            val plane = proxy.planes[0]
            val frame = try {
                RgbaPacker.pack(
                    plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride,
                    proxy.imageInfo.rotationDegrees, proxy.imageInfo.timestamp,
                )
            } catch (e: IllegalArgumentException) {
                busy.set(false)
                Log.w(TAG, "frame rejected: ${e.message}")
                return
            }
            RuntimeLane.executor.execute {
                try {
                    onFrame(frame)
                } finally {
                    busy.set(false)
                }
            }
        } finally { proxy.close() }
    }

    private fun logOnce(proxy: ImageProxy) {
        if (!logged.compareAndSet(false, true)) return
        val plane = proxy.planes[0]
        val rotation = proxy.imageInfo.rotationDegrees
        val info = "first frame ${proxy.width}x${proxy.height} rotation=$rotation cropRect=${proxy.cropRect} " +
            "rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} format=${proxy.format}"
        firstFrameInfo = info
        Log.i(TAG, info)
        if (proxy.width * 9 != proxy.height * 16) {
            Log.w(TAG, "analysis aspect is not 16:9 — FrameTransform needs the cropRect form (RESEARCH section C)")
        }
        if (rotation != 0) {
            Log.w(TAG, "rotation $rotation != 0 — packer rotates; verify boxes upright")
        }
    }

    /** Unbinds every use case; the analysis executor stays alive so [start] can be called again. */
    fun stop() {
        ContextCompat.getMainExecutor(context).execute { provider?.unbindAll() }
    }

    private companion object {
        const val TAG = "FoF"
    }
}
