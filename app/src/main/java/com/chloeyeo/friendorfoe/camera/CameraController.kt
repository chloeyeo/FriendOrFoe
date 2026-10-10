package com.chloeyeo.friendorfoe.camera

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.chloeyeo.friendorfoe.detect.Frame

/**
 * TODO(stream 1 / Chloe): CameraX Preview + ImageAnalysis feeding [Frame]s to the detector at about 1 fps.
 *
 * Verified facts (`.planning/research/STACK.md` section 2):
 * - `Preview` bound to a [PreviewView] with `ScaleType.FILL_CENTER`; the activity is landscape-locked by the
 *   manifest, so the frame and the view share the orientation and [FrameTransform] only needs rotation 0.
 * - `ImageAnalysis.Builder().setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
 *   .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)` with a `ResolutionSelector` for 1280x720 (or 1920x1080);
 *   `setOutputImageRotationEnabled(true)` so frames arrive upright (`rotationDegrees == 0`, about 10-15 ms).
 * - The analyzer runs on the runtime lane's executor (the same single thread that loads and runs the model), so
 *   the frame copy, the blocking detect and the ~1 s pacing live on one thread. Hold the `ImageProxy` until the
 *   one-second tick, then `close()` it: an early close makes CameraX convert 30 fps for nothing.
 * - RGBA plane: `planes[0]` is a single plane with `rowStride` possibly larger than `width * 4`; copy it
 *   **row by row** into a tight `ByteArray(width * height * 4)` (or `toBitmap()` + `copyPixelsToBuffer`) before
 *   constructing the [Frame] — `ImageInput.Rgba8888` wants tight rows.
 * - `bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)`; CAMERA runtime
 *   permission is requested by the activity before [start].
 */
class CameraController(private val context: Context) {
    /** Binds preview and analysis; [onFrame] is called on the runtime lane with each upright, tightly packed frame. */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onFrame: (Frame) -> Unit): Unit =
        throw NotImplementedError("stream 1: CameraX Preview + ImageAnalysis (RGBA_8888, KEEP_ONLY_LATEST)")

    /** Unbinds every use case. */
    fun stop(): Unit = throw NotImplementedError("stream 1: ProcessCameraProvider.unbindAll()")
}
