package com.chloeyeo.friendorfoe.cross.fof

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The camera pane (the native app's `CameraController`): CameraX Preview in a `PreviewView` (FILL_CENTER, the
 * mapping `src/camera/frameTransform.ts` mirrors) plus one RGBA_8888 ImageAnalysis stream, KEEP_ONLY_LATEST,
 * 1280x720 at 16:9, analysed on its own camera thread:
 *
 * - every 100 ms a small upright gray frame ([GraySampler], 160 px wide) goes to TypeScript as `onGray`, for the
 *   10 fps motion tracker that moves the rings between detections (the native app's motion tick);
 * - when the runtime lane is idle and [intervalMs] has passed, the frame is copied upright and handed to
 *   [ClikaEngine.lane] for detection, so inference taking seconds never stalls the gray frames.
 *
 * Both carry the camera sensor timestamp (`ts`, ms) so a detection that arrives seconds late is fast-forwarded on
 * the gray frames buffered since its capture.
 *
 * Each analysed frame is rotated upright (`imageInfo.rotationDegrees`, landscape-locked activity) and then, by
 * [detector]: `clikart` runs [ClikaEngine.detect] in-process; `http` posts a JPEG to `clikart-cli serve`
 * (`POST {serveUrl}/v1/detections`, field `image`) and hands the body to TypeScript, which parses it with the same
 * rules as the native `DetectionsJson`; `none` only reports the frame size. `onFrame` carries
 * `{width, height, detected, ms, rows | body, error, t}`; boxes are in frame pixels.
 */
class FofCameraView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  override val shouldUseAndroidLayout = true

  private val onFrame by EventDispatcher<Map<String, Any?>>()
  private val onGray by EventDispatcher<Map<String, Any?>>()
  private val onCameraReady by EventDispatcher<Map<String, Any?>>()
  private val onCameraError by EventDispatcher<Map<String, Any?>>()

  private val previewView = PreviewView(context).apply {
    scaleType = PreviewView.ScaleType.FILL_CENTER
    // TextureView: the React overlay drawn above it composites reliably on every device.
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
  }

  @Volatile var detector: String = "none"
  @Volatile var serveUrl: String = ""
  @Volatile var intervalMs: Long = 1000L
  @Volatile var thumbnailUrl: String = ""
  /** The model tags to run (Settings: which classes to detect); null runs both. */
  @Volatile var models: Set<String>? = null
  @Volatile private var active = true

  private var provider: ProcessCameraProvider? = null
  private var camera: Camera? = null

  /** Digital zoom of the capture (CameraX crops the sensor), so preview and analysed frames stay one picture. */
  private var zoom = 1f
  private var bound = false
  private var lastRun = 0L
  private var lastGray = 0L
  @Volatile private var busy = false
  private var lastThumbnail = 0L
  private val cameraExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fof-camera") }
  private val thumbnailExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fof-thumbnail") }

  init {
    addView(previewView)
  }

  /** Main thread (a prop setter). Clamped to the camera's range and [MAX_ZOOM]. */
  fun setZoom(value: Float) {
    zoom = value
    applyZoom()
  }

  private fun applyZoom() {
    val cam = camera ?: return
    val state = cam.cameraInfo.zoomState.value
    val lo = state?.minZoomRatio ?: 1f
    val hi = minOf(state?.maxZoomRatio ?: 1f, MAX_ZOOM)
    cam.cameraControl.setZoomRatio(zoom.coerceIn(lo, maxOf(lo, hi)))
  }

  fun setActive(value: Boolean) {
    active = value
    if (value) bindIfReady() else unbind()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    bindIfReady()
  }

  override fun onDetachedFromWindow() {
    unbind()
    super.onDetachedFromWindow()
  }

  fun release() {
    unbind()
    cameraExecutor.shutdown()
    thumbnailExecutor.shutdown()
  }

  private fun bindIfReady() {
    if (bound || !active || !isAttachedToWindow) return
    val owner = appContext.currentActivity as? LifecycleOwner
    if (owner == null) {
      emitError("NO_ACTIVITY", "no activity to bind the camera to")
      return
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      emitError("CAMERA_PERMISSION", "camera permission not granted")
      return
    }
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      try {
        if (bound || !active || !isAttachedToWindow) return@addListener
        val p = future.get()
        provider = p
        val selector = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
          .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
          .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
          .setResolutionSelector(selector)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .build()
        analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }
        p.unbindAll()
        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        camera = cam
        bound = true
        applyZoom()
        val state = cam.cameraInfo.zoomState.value
        onCameraReady(mapOf("minZoom" to (state?.minZoomRatio ?: 1f).toDouble(), "maxZoom" to minOf(state?.maxZoomRatio ?: 1f, MAX_ZOOM).toDouble()))
      } catch (e: Exception) {
        emitError("CAMERA_BIND", ClikaEngine.noteFor(e))
      }
    }, ContextCompat.getMainExecutor(context))
  }

  private fun unbind() {
    if (!bound) return
    try {
      provider?.unbindAll()
      camera = null
    } catch (e: Exception) {
      Log.w(TAG, "unbind failed: ${e.message}")
    }
    bound = false
  }

  /** Camera thread. Always closes the proxy. */
  private fun analyze(proxy: ImageProxy) {
    try {
      if (!active) return
      val now = SystemClock.elapsedRealtime()
      val tsMs = proxy.imageInfo.timestamp / 1_000_000.0
      if (detector != "none" && now - lastGray >= GRAY_INTERVAL_MS) {
        lastGray = now
        emitGray(proxy, tsMs)
      }
      if (busy || now - lastRun < intervalMs) return
      lastRun = now
      val frame = upright(proxy)
      busy = true
      ClikaEngine.lane.execute {
        try {
          detectOnLane(frame, tsMs, now)
        } finally {
          frame.recycle()
          busy = false
        }
      }
    } catch (e: Throwable) {
      Log.w(TAG, "analyze failed: ${ClikaEngine.noteFor(e)}")
    } finally {
      proxy.close()
    }
  }

  private fun emitGray(proxy: ImageProxy, tsMs: Double) {
    val plane = proxy.planes[0]
    val g = GraySampler.sample(plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride, proxy.imageInfo.rotationDegrees)
    val payload = mapOf(
      "width" to g.width,
      "height" to g.height,
      "step" to g.step,
      "frameWidth" to g.frameWidth,
      "frameHeight" to g.frameHeight,
      "t" to tsMs,
      "luma" to Base64.encodeToString(g.luma, Base64.NO_WRAP),
    )
    post { onGray(payload) }
  }

  /** Runtime lane. */
  private fun detectOnLane(frame: Bitmap, tsMs: Double, startedAt: Long) {
    val w = frame.width
    val h = frame.height
    val t0 = SystemClock.elapsedRealtime()
    var rows: List<Map<String, Any>>? = null
    var body: String? = null
    var error: String? = null
    var note: String? = null
    var detected = false
    try {
      when (detector) {
        "clikart" -> if (ClikaEngine.isOpen) {
          val rgba = ByteArray(w * h * 4)
          frame.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
          rows = ClikaEngine.detect(rgba, w, h, models)
          note = ClikaEngine.timingNote()
          detected = true
        }
        "http" -> if (serveUrl.isNotBlank()) {
          body = postToServe(frame, serveUrl)
          detected = true
        }
      }
    } catch (e: Throwable) {
      error = ClikaEngine.noteFor(e)
    }
    val ms = SystemClock.elapsedRealtime() - t0
    maybePostThumbnail(frame, startedAt)
    val payload = mapOf(
      "width" to w,
      "height" to h,
      "detected" to detected,
      "ms" to ms,
      "rows" to rows,
      "body" to body,
      "error" to error,
      "note" to note,
      "ts" to tsMs,
      "t" to System.currentTimeMillis().toDouble(),
    )
    post { onFrame(payload) }
  }

  /** `toBitmap()` handles the row stride; rotate so boxes come back in upright-frame pixels. */
  private fun upright(proxy: ImageProxy): Bitmap {
    val bmp = proxy.toBitmap()
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation == 0) return bmp
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (rotated !== bmp) bmp.recycle()
    return rotated
  }

  /** multipart/form-data like the native HttpDetector: `image` (JPEG 80), `confidence` 0.25, `max_detections` 20. */
  private fun postToServe(frame: Bitmap, baseUrl: String): String {
    val jpeg = ByteArrayOutputStream().use { out ->
      frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
      out.toByteArray()
    }
    val boundary = "fof" + System.nanoTime()
    val c = URL(baseUrl.trimEnd('/') + "/v1/detections").openConnection() as HttpURLConnection
    try {
      c.connectTimeout = 1000
      c.readTimeout = 2000
      c.requestMethod = "POST"
      c.doOutput = true
      c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      c.outputStream.use { out ->
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        text("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
        out.write(jpeg)
        text("\r\n")
        for ((name, value) in listOf("confidence" to "0.25", "max_detections" to "20")) {
          text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        text("--$boundary--\r\n")
      }
      val code = c.responseCode
      check(code in 200..299) { "serve $code" }
      return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
      c.disconnect()
    }
  }

  /**
   * About once a second, a small JPEG of what the preview shows (the frame center-cropped to this view's aspect,
   * as FILL_CENTER does) to the laptop dashboard, so it can draw the rings over the picture. Fire-and-forget.
   */
  private fun maybePostThumbnail(frame: Bitmap, now: Long) {
    val base = thumbnailUrl
    if (base.isBlank() || now - lastThumbnail < 1000L || width <= 0 || height <= 0) return
    lastThumbnail = now
    val viewAspect = width.toFloat() / height
    val frameAspect = frame.width.toFloat() / frame.height
    val (cw, ch) = if (frameAspect > viewAspect) Pair((frame.height * viewAspect).toInt(), frame.height) else Pair(frame.width, (frame.width / viewAspect).toInt())
    val cropped = Bitmap.createBitmap(frame, (frame.width - cw) / 2, (frame.height - ch) / 2, cw, ch)
    val scaled = Bitmap.createScaledBitmap(cropped, 480, (480 * ch / cw.toFloat()).toInt().coerceAtLeast(1), true)
    if (cropped !== frame && cropped !== scaled) cropped.recycle()
    val jpeg = ByteArrayOutputStream().use { out ->
      scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
      out.toByteArray()
    }
    if (scaled !== frame) scaled.recycle()
    thumbnailExecutor.execute {
      try {
        val c = URL(base.trimEnd('/') + "/api/thumbnail").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 500
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.outputStream.use { it.write(jpeg) }
        c.responseCode
        c.disconnect()
      } catch (_: Exception) {
        // The dashboard is a side-car; a missing laptop never affects the phone.
      }
    }
  }

  private fun emitError(code: String, message: String) {
    post { onCameraError(mapOf("code" to code, "message" to message)) }
  }

  private companion object {
    const val TAG = "FoF"
    const val GRAY_INTERVAL_MS = 100L
    const val MAX_ZOOM = 10f
  }
}
