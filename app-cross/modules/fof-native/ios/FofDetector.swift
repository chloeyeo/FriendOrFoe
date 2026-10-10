import CoreML
import CoreVideo
import QuartzCore
import Vision

/**
 On-device detection for the iPhone build. ClikaRT ships no iOS runtime (docs.clika.io, system requirements), so the
 iPhone runs Core ML detectors through Vision instead (this is not the SC1 path; phone A's ClikaRT is):

 - `FofCoco` (YOLO11n, COCO 80): `person`, `bird`, and the `airplane` / `kite` proxies the native app also uses;
 - `FofDrone` (sapoepsilon/yolov11s-drone-detector, Apache-2.0): `drone`, trained on real drone photos.

 FofDrone found 12 of 13 real drone photos (Phantom, Inspire, Mavic, FPV racers, a VTOL fixed-wing) at the 0.6 floor
 the iPhone build uses (see scripts/export_coreml.py). Both are exported with NMS (`scripts/export-coreml.sh`), so
 Vision returns labelled boxes; rows go to TypeScript with the model tag and label, and `src/detect/labelMap.ts`
 decides the class and the floor.
 */
final class FofDetector {
  static let shared = FofDetector()

  private static let models: [(tag: String, resource: String)] = [("coco", "FofCoco"), ("drone", "FofDrone")]
  private let lock = NSLock()
  private var requests: [(tag: String, request: VNCoreMLRequest)] = []

  var isOpen: Bool {
    lock.lock()
    defer { lock.unlock() }
    return !requests.isEmpty
  }

  /// Loads both models (Neural Engine where available) and warms them up; idempotent. Returns load/warm-up ms.
  func open() throws -> [String: Any] {
    if isOpen { return ["loadMs": 0, "warmupMs": 0, "threads": 0] }
    let t0 = CACurrentMediaTime()
    let config = MLModelConfiguration()
    config.computeUnits = .all
    var loaded: [(tag: String, request: VNCoreMLRequest)] = []
    for (tag, resource) in Self.models {
      guard let url = Bundle.main.url(forResource: resource, withExtension: "mlmodelc") else {
        throw FofException("model missing: \(resource).mlmodelc (run scripts/export-coreml.sh, then pod install)")
      }
      let model = try VNCoreMLModel(for: MLModel(contentsOf: url, configuration: config))
      let request = VNCoreMLRequest(model: model)
      // Letterboxed like the YOLO training images (aspect kept, no squeeze); boxes come back normalised to the frame.
      request.imageCropAndScaleOption = .scaleFit
      loaded.append((tag, request))
    }
    let t1 = CACurrentMediaTime()
    if let blank = Self.blankBuffer(width: 640, height: 640) {
      try VNImageRequestHandler(cvPixelBuffer: blank, orientation: .up, options: [:]).perform(loaded.map { $0.request })
    }
    let t2 = CACurrentMediaTime()
    lock.lock()
    requests = loaded
    lock.unlock()
    return ["loadMs": Int((t1 - t0) * 1000), "warmupMs": Int((t2 - t1) * 1000), "threads": 0]
  }

  func close() {
    lock.lock()
    requests = []
    lock.unlock()
  }

  /// Raw rows of one upright frame, in frame pixels (top-left origin), both models together.
  func detect(_ buffer: CVPixelBuffer, width: Int, height: Int) throws -> [[String: Any]] {
    lock.lock()
    let current = requests
    lock.unlock()
    guard !current.isEmpty else { return [] }
    try VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:]).perform(current.map { $0.request })
    let w = Double(width)
    let h = Double(height)
    var rows: [[String: Any]] = []
    for (tag, request) in current {
      for case let observation as VNRecognizedObjectObservation in request.results ?? [] {
        guard let best = observation.labels.first else { continue }
        // Vision's normalised rect has a bottom-left origin.
        let b = observation.boundingBox
        rows.append([
          "model": tag,
          "label": best.identifier,
          "labelId": -1,
          "score": Double(best.confidence),
          "left": Double(b.minX) * w,
          "top": Double(1 - b.maxY) * h,
          "right": Double(b.maxX) * w,
          "bottom": Double(1 - b.minY) * h,
        ])
      }
    }
    return rows
  }

  private static func blankBuffer(width: Int, height: Int) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    let attrs = [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary
    CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attrs, &buffer)
    guard let buffer else { return nil }
    CVPixelBufferLockBaseAddress(buffer, [])
    if let base = CVPixelBufferGetBaseAddress(buffer) {
      memset(base, 0, CVPixelBufferGetDataSize(buffer))
    }
    CVPixelBufferUnlockBaseAddress(buffer, [])
    return buffer
  }
}
