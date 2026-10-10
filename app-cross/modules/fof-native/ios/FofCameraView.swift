import AVFoundation
import ExpoModulesCore
import UIKit

/**
 The iPhone camera pane, the counterpart of the Android `FofCameraView`: an `AVCaptureVideoPreviewLayer` with
 aspect-fill (the FILL_CENTER mapping `src/camera/frameTransform.ts` mirrors) and one BGRA video-data output at
 1280x720. Frames are rotated upright by the device's rotation coordinator, so boxes come back in upright-frame
 pixels. Late frames are discarded and frames inside `intervalMs` of the last run are skipped, so inference paces
 itself. `onFrame` carries the same `{width, height, detected, ms, rows, error, t}` as on Android.
 */
final class FofCameraView: ExpoView, AVCaptureVideoDataOutputSampleBufferDelegate {
  private let onFrame = EventDispatcher()
  private let onCameraError = EventDispatcher()

  private let session = AVCaptureSession()
  private let output = AVCaptureVideoDataOutput()
  private let previewLayer: AVCaptureVideoPreviewLayer
  private let sessionQueue = DispatchQueue(label: "fof.camera.session")
  private let frameQueue = DispatchQueue(label: "fof.camera.frames")
  private var configured = false
  private var rotation: AVCaptureDevice.RotationCoordinator?
  private var rotationObservers: [NSKeyValueObservation] = []
  private var lastRun: CFTimeInterval = 0

  var detector = "none"
  var intervalMs = 1000
  var active = true {
    didSet { active ? start() : stop() }
  }

  required init(appContext: AppContext? = nil) {
    previewLayer = AVCaptureVideoPreviewLayer(session: session)
    super.init(appContext: appContext)
    previewLayer.videoGravity = .resizeAspectFill
    layer.addSublayer(previewLayer)
    clipsToBounds = true
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer.frame = bounds
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window != nil && active { start() } else { stop() }
  }

  private func start() {
    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      emitError("CAMERA_PERMISSION", "camera permission not granted")
      return
    }
    sessionQueue.async { [weak self] in
      guard let self else { return }
      if !self.configured {
        do {
          try self.configure()
        } catch {
          self.emitError("CAMERA_BIND", error.localizedDescription)
          return
        }
      }
      if !self.session.isRunning { self.session.startRunning() }
    }
  }

  private func stop() {
    sessionQueue.async { [weak self] in
      guard let self, self.session.isRunning else { return }
      self.session.stopRunning()
    }
  }

  /// Session queue.
  private func configure() throws {
    session.beginConfiguration()
    defer { session.commitConfiguration() }
    session.sessionPreset = .hd1280x720
    guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
      throw FofException("no back camera")
    }
    let input = try AVCaptureDeviceInput(device: device)
    guard session.canAddInput(input) else { throw FofException("camera input refused") }
    session.addInput(input)
    output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
    output.alwaysDiscardsLateVideoFrames = true
    output.setSampleBufferDelegate(self, queue: frameQueue)
    guard session.canAddOutput(output) else { throw FofException("camera output refused") }
    session.addOutput(output)
    configured = true
    DispatchQueue.main.async { [weak self] in self?.followRotation(of: device) }
  }

  /// Main queue. Keeps both the preview and the analysed frames level with the horizon as the phone turns.
  private func followRotation(of device: AVCaptureDevice) {
    let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
    rotation = coordinator
    apply(coordinator)
    rotationObservers = [
      coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak self] c, _ in
        DispatchQueue.main.async { self?.apply(c) }
      },
      coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.new]) { [weak self] c, _ in
        DispatchQueue.main.async { self?.apply(c) }
      },
    ]
  }

  private func apply(_ coordinator: AVCaptureDevice.RotationCoordinator) {
    let preview = coordinator.videoRotationAngleForHorizonLevelPreview
    if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(preview) {
      connection.videoRotationAngle = preview
    }
    let capture = coordinator.videoRotationAngleForHorizonLevelCapture
    sessionQueue.async { [weak self] in
      if let connection = self?.output.connection(with: .video), connection.isVideoRotationAngleSupported(capture) {
        connection.videoRotationAngle = capture
      }
    }
  }

  /// Frame queue.
  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    let now = CACurrentMediaTime()
    guard active, now - lastRun >= Double(intervalMs) / 1000.0, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    lastRun = now
    let width = CVPixelBufferGetWidth(buffer)
    let height = CVPixelBufferGetHeight(buffer)
    var payload: [String: Any] = ["width": width, "height": height, "detected": false, "ms": 0, "t": Date().timeIntervalSince1970 * 1000]
    switch detector {
    case "coreml":
      if FofDetector.shared.isOpen {
        let t0 = CACurrentMediaTime()
        do {
          payload["rows"] = try FofDetector.shared.detect(buffer, width: width, height: height)
          payload["detected"] = true
        } catch {
          payload["error"] = error.localizedDescription
        }
        payload["ms"] = Int((CACurrentMediaTime() - t0) * 1000)
      }
    case "clikart", "http":
      payload["error"] = "\(detector) detector is Android-only"
    default:
      break
    }
    DispatchQueue.main.async { [weak self] in self?.onFrame(payload) }
  }

  private func emitError(_ code: String, _ message: String) {
    DispatchQueue.main.async { [weak self] in self?.onCameraError(["code": code, "message": message]) }
  }
}

final class FofException: GenericException<String>, @unchecked Sendable {
  init(_ message: String) {
    super.init(message)
  }

  override var reason: String {
    param
  }
}
