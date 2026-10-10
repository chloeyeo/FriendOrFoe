import AVFoundation
import ExpoModulesCore

/// The iPhone side of the `FofRuntime` module: same JavaScript surface as Android, Core ML instead of ClikaRT.
public final class FofRuntimeModule: Module {
  public func definition() -> ModuleDefinition {
    Name("FofRuntime")

    Function("defaultModelDir") { () -> String in
      "bundled Core ML (FofCoco + FofDrone)"
    }

    AsyncFunction("requestCameraPermission") { (promise: Promise) in
      AVCaptureDevice.requestAccess(for: .video) { granted in
        promise.resolve(["granted": granted, "status": granted ? "granted" : "denied"])
      }
    }

    /// Loads the bundled detectors; the arguments exist for Android's ClikaRT and are ignored here.
    AsyncFunction("openDetector") { (_ modelDir: String, _ threads: Int) -> [String: Any] in
      try FofDetector.shared.open()
    }

    AsyncFunction("closeDetector") {
      FofDetector.shared.close()
    }

    View(FofCameraView.self) {
      Events("onFrame", "onCameraError")

      Prop("detector") { (view: FofCameraView, value: String) in
        view.detector = value
      }
      Prop("serveUrl") { (_: FofCameraView, _: String) in }
      Prop("intervalMs") { (view: FofCameraView, value: Int) in
        view.intervalMs = max(100, value)
      }
      Prop("thumbnailUrl") { (_: FofCameraView, _: String) in }
      Prop("active") { (view: FofCameraView, value: Bool) in
        view.active = value
      }
    }
  }
}
