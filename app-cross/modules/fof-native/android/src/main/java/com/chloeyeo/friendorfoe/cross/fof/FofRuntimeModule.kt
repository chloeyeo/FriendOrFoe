package com.chloeyeo.friendorfoe.cross.fof

import android.Manifest
import android.content.Context
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** ClikaRT bring-up, the camera pane and the camera permission. */
class FofRuntimeModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("FofRuntime")

    Function("defaultModelDirs") {
      ClikaEngine.defaultModelDirs(context)
    }

    AsyncFunction("requestCameraPermission") { promise: Promise ->
      Permissions.askForPermissionsWithPermissionsManager(appContext.permissions, promise, Manifest.permission.CAMERA)
    }

    /** Loads the runtime (license probe) and opens both models on the lane; rejects with the ClikaRT code name. */
    AsyncFunction("openDetector") { droneDir: String, personDir: String, threads: Int ->
      try {
        ClikaEngine.onLane { ClikaEngine.open(context, droneDir, personDir, threads) }
      } catch (e: Throwable) {
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        throw CodedException("FOF_RUNTIME", ClikaEngine.noteFor(cause), cause)
      }
    }

    AsyncFunction("closeDetector") {
      ClikaEngine.onLane { ClikaEngine.close() }
    }

    View(FofCameraView::class) {
      Events("onFrame", "onGray", "onCameraError")
      Prop("detector") { view: FofCameraView, value: String -> view.detector = value }
      Prop("serveUrl") { view: FofCameraView, value: String -> view.serveUrl = value }
      Prop("intervalMs") { view: FofCameraView, value: Int -> view.intervalMs = value.toLong().coerceAtLeast(100L) }
      Prop("thumbnailUrl") { view: FofCameraView, value: String -> view.thumbnailUrl = value }
      Prop("active") { view: FofCameraView, value: Boolean -> view.setActive(value) }
      OnViewDestroys { view: FofCameraView -> view.release() }
    }
  }
}

/** The reader gadget link: lines in and out, plus link state. */
class FofUsbModule : Module() {
  private var link: UsbGadgetLink? = null

  private fun link(): UsbGadgetLink {
    link?.let { return it }
    val context = appContext.reactContext ?: throw Exceptions.ReactContextLost()
    return UsbGadgetLink(
      context,
      onLine = { line -> sendEvent("onLine", mapOf("line" to line)) },
      onState = { connected, error -> sendEvent("onState", mapOf("connected" to connected, "error" to error)) },
    ).also { link = it }
  }

  override fun definition() = ModuleDefinition {
    Name("FofUsb")

    Events("onLine", "onState")

    AsyncFunction("listDevices") {
      link().devices()
    }

    AsyncFunction("open") { deviceId: Int?, promise: Promise ->
      link().open(deviceId) { result ->
        result.fold(
          onSuccess = { name -> promise.resolve(name) },
          onFailure = { e -> promise.reject(CodedException("FOF_USB", e.message ?: "usb open failed", e)) },
        )
      }
    }

    Function("write") { line: String ->
      link?.write(line) ?: false
    }

    Function("isOpen") {
      link?.isOpen ?: false
    }

    Function("close") {
      link?.close()
    }

    OnDestroy {
      link?.close()
      link = null
    }
  }
}
