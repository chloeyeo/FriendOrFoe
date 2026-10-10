import AVFoundation
import ExpoModulesCore
import UIKit

/**
 The iPhone camera pane, the counterpart of the Android `FofCameraView`: an `AVCaptureVideoPreviewLayer` with
 aspect-fill (the FILL_CENTER mapping `src/camera/frameTransform.ts` mirrors) and one BGRA video-data output at
 1280x720, rotated upright by the device's rotation coordinator. Like Android:

 - every 100 ms a 160 px wide gray frame goes to TypeScript as `onGray` for the 10 fps motion tracker;
 - when the detection queue is idle and `intervalMs` has passed, the frame is detected on that queue, and `onFrame`
   carries `{width, height, detected, ms, rows, error, ts, t}`, `ts` being the capture time on the gray frames' clock.
 */
final class FofCameraView: ExpoView, AVCaptureVideoDataOutputSampleBufferDelegate {
  private let onFrame = EventDispatcher()
  private let onGray = EventDispatcher()
  private let onCameraError = EventDispatcher()

  private let session = AVCaptureSession()
  private let output = AVCaptureVideoDataOutput()
  private let previewLayer: AVCaptureVideoPreviewLayer
  private let sessionQueue = DispatchQueue(label: "fof.camera.session")
  private let frameQueue = DispatchQueue(label: "fof.camera.frames")
  private let detectQueue = DispatchQueue(label: "fof.camera.detect")
  private var busy = false
  private var lastGray: CFTimeInterval = 0
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
    guard active, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    let now = CACurrentMediaTime()
    let tsMs = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1000
    if detector != "none" && now - lastGray >= 0.1 {
      lastGray = now
      if let gray = FofCameraView.gray(buffer, tsMs: tsMs) {
        DispatchQueue.main.async { [weak self] in self?.onGray(gray) }
      }
    }
    guard !busy, now - lastRun >= Double(intervalMs) / 1000.0 else { return }
    lastRun = now
    busy = true
    detectQueue.async { [weak self] in
      guard let self else { return }
      defer { self.frameQueue.async { self.busy = false } }
      self.detect(buffer, tsMs: tsMs)
    }
  }

  /// Detection queue. The pixel buffer is retained by the closure, so it stays valid here.
  private func detect(_ buffer: CVPixelBuffer, tsMs: Double) {
    let width = CVPixelBufferGetWidth(buffer)
    let height = CVPixelBufferGetHeight(buffer)
    var payload: [String: Any] = ["width": width, "height": height, "detected": false, "ms": 0, "ts": tsMs, "t": Date().timeIntervalSince1970 * 1000]
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

  /// A 160 px wide gray frame of a BGRA buffer: mean luma (77 R + 150 G + 29 B) >> 8 of the 2x2 block at the start of
  /// each step cell, the Android GraySampler's arithmetic; base64 so it crosses the bridge as one string.
  private static func gray(_ buffer: CVPixelBuffer, tsMs: Double, targetWidth: Int = 160) -> [String: Any]? {
    guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA else { return nil }
    CVPixelBufferLockBaseAddress(buffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
    let width = CVPixelBufferGetWidth(buffer)
    let height = CVPixelBufferGetHeight(buffer)
    let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
    let step = max(1, width / max(1, targetWidth))
    let gw = width / step
    let gh = height / step
    let block = step >= 2 ? 2 : 1
    let src = base.assumingMemoryBound(to: UInt8.self)
    var out = [UInt8](repeating: 0, count: gw * gh)
    for gy in 0..<gh {
      for gx in 0..<gw {
        var sum = 0
        for r in 0..<block {
          let row = src + (gy * step + r) * rowBytes
          for c in 0..<block {
            let p = row + (gx * step + c) * 4
            sum += (77 * Int(p[2]) + 150 * Int(p[1]) + 29 * Int(p[0])) >> 8
          }
        }
        out[gy * gw + gx] = UInt8(sum / (block * block))
      }
    }
    return [
      "width": gw, "height": gh, "step": step, "frameWidth": width, "frameHeight": height, "t": tsMs,
      "luma": Data(out).base64EncodedString(),
    ]
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
