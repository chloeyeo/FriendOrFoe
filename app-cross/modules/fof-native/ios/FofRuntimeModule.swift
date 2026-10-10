import AVFoundation
import ExpoModulesCore

/// The iPhone side of the `FofRuntime` module: same JavaScript surface as Android, Core ML instead of ClikaRT.
public final class FofRuntimeModule: Module {
  public func definition() -> ModuleDefinition {
    Name("FofRuntime")

    Function("defaultModelDirs") { () -> [String: String] in
      ["drone": "bundled Core ML FofDrone", "person": "bundled Core ML FofCoco"]
    }

    AsyncFunction("requestCameraPermission") { (promise: Promise) in
      AVCaptureDevice.requestAccess(for: .video) { granted in
        promise.resolve(["granted": granted, "status": granted ? "granted" : "denied"])
      }
    }

    /// Loads the bundled detectors; the arguments exist for Android's ClikaRT and are ignored here.
    AsyncFunction("openDetector") { (_ droneDir: String, _ personDir: String, _ threads: Int) -> [String: Any] in
      try FofDetector.shared.open()
    }

    AsyncFunction("closeDetector") {
      FofDetector.shared.close()
    }

    View(FofCameraView.self) {
      Events("onFrame", "onGray", "onCameraReady", "onCameraError")

      Prop("detector") { (view: FofCameraView, value: String) in
        view.detector = value
      }
      Prop("serveUrl") { (_: FofCameraView, _: String) in }
      Prop("intervalMs") { (view: FofCameraView, value: Int) in
        view.intervalMs = max(100, value)
      }
      Prop("thumbnailUrl") { (_: FofCameraView, _: String) in }
      Prop("models") { (view: FofCameraView, value: [String]) in
        view.models = Set(value)
      }
      Prop("zoom") { (view: FofCameraView, value: Double) in
        view.zoom = value
      }
      Prop("active") { (view: FofCameraView, value: Bool) in
        view.active = value
      }
    }
  }
}
