# Architecture Research

**Domain:** Two-phone offline IFF demo (on-device drone detection + optical challenge/response), plus an optional laptop (dashboard, eval, detector fallback)
**Researched:** 2026-10-10
**Confidence:** HIGH for everything ClikaRT (local binding sources, official samples, live `clikart-cli serve` probes); MEDIUM for CameraX coordinate handling (androidx Javadoc + official guides, one detail unverified on a device); LOW for hotspot/adb-push behaviour on specific phones (must be tested at H0)

> **Demo surface correction (user, 2026-10-10):** detection runs on the Android phone, and the drone + IFF method is what the team builds, so the demo runs on the connected devices (phone A observer, phone B responder), not on a laptop. Everything below puts the two phones on the critical path. The laptop is Tier 2: `clikart-cli serve` for the eval table and the SC1 "second device type", a judges' dashboard, and the H4 detector contingency. Nothing in the IFF demo may depend on the laptop being reachable.

## Standard Architecture

### System Overview

```
┌──────────────────────────────── phone A · OBSERVER (same APK, mode = Observer) ────────────────────────────────┐
│  camera/            detect/                  track/            session/ (state machine)        ui/            │
│  CameraX Preview ─┐ Detector (interface)      Tracker          IffCoordinator                  CameraScreen    │
│  ImageAnalysis    ├─► ClikaRtDetector ───────► IoU match ────► per-track IFF state ──────────► rings + tags     │
│  RGBA_8888, 1 fps │   (ClikaRT AAR, lane)     ids, coast       UNCHALLENGED→CHALLENGED→…       tap = challenge │
│  ~10 fps QR loop  │   HttpDetector (laptop)                      ▲          │                  VerdictLog      │
│                   └─► optical/QrReader (ML Kit, crop of box) ────┘          │ ChallengeClient  Settings/Debug  │
│                                                                             ▼ EventClient (fire-and-forget)   │
│  app/AppRuntime: Modelverse.load → model open → RuntimeLane (one thread)   ┌────────── :iff (pure Kotlin JVM) ┐│
│                                                                             │ Codec Keys Challenger Responder ││
└─────────────────────────────────────────────────────────────────────────────│ Verifier NonceStore Geo/PosCheck│┘
                 │ HTTP POST /challenge (hotspot LAN, cleartext)              └──────────────────────────────────┘
                 ▼                                                     ▲ optical: QR read inside the box
┌──────────────── phone B · RESPONDER (same APK, mode = Responder) ─────┴──────────────────────────────────────┐
│  responder/ResponderServer (NanoHTTPD :8129) → :iff Responder.verify(cmac) → build response → QrRenderer   │
│  ResponderScreen: own IP, drone picture, QR over it, buttons Honest / Replay / Relay / Silent / Impostor / Late │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                 │ HTTP POST /api/events, /api/frame  (optional, never awaited)
                 ▼
┌──────────────── laptop · TIER 2 (optional) ───────────────────────────────────────────────────────────────┐
│  laptop/dashboard (FastAPI + uvicorn, GET /dashboard polls GET /api/state at 1 Hz)                        │
│  clikart-cli … serve --offline (POST /v1/detections) ← eval/run_eval.py, ← HttpDetector (H4 fallback)     │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation (this project) |
|-----------|----------------|------------------------|
| `:iff` module | FOF-1 protocol: byte layouts, key derivation, HMAC, nonce lifecycle, verdicts, bearing/elevation geometry | Plain `kotlin("jvm")` module, zero runtime dependencies (`javax.crypto.Mac`, `java.util.Base64`, `SecureRandom`); JUnit 4 tests + JSON vectors |
| `app/` (runtime bring-up) | Load ClikaRT once per process on the runtime lane, place the license, pick the model directory, set CPU threads, route logs, register memory trim | Copy of the samples' `SampleRuntime` + `RuntimeLane` + `LicenseStore` patterns |
| `detect/` | `Detector` interface, frame type, `ClikaRtDetector` (production), `HttpDetector` (dev/contingency), label-id → class mapping | `ObjectDetectionPipeline` on the lane; OkHttp multipart to `/v1/detections` |
| `camera/` | CameraX binding (Preview + ImageAnalysis in one `UseCaseGroup` with the PreviewView's `ViewPort`), RGBA frame copy, throttling, frame↔view transforms, compass/pitch, FOV | CameraX 1.4.1 (already declared), `setOutputImageRotationEnabled(true)` |
| `track/` | Keep an identity per object across 1 fps results so the IFF tag stays on the same drone; coast a challenged track while the QR hides the drone picture | Greedy IoU matching on normalized boxes, EMA smoothing, miss counter |
| `session/` | The per-track IFF state machine and the glue: tap → challenge → QR read → verify → verdict → event; also the physical-layer gates of PROJECT.md (challenge→QR round-trip ≤ 2.5 s, hotspot RSSI vs near-field claim, QR persistence) | `IffCoordinator` owning a `NonceStore`, `Challenger`, `Verifier` from `:iff`; `PhysicalGates` (RTT clock, `WifiInfo.getRssi()` reader) |
| `optical/` | Decode a QR **only inside the challenged box** (attribution); require it to persist for two consecutive frames at a plausible size (PROJECT.md optical gate) | ML Kit `barcode-scanning` 17.3.0 (bundled model), `InputImage.fromBitmap(crop, 0)`, read `Barcode.getRawBytes()` (the QR carries the raw 37-byte response in byte mode — PROJECT.md decision, not base64url text) |
| `net/` | Challenge POST with tight timeouts; event/frame POST fire-and-forget | OkHttp 5.5.0, `org.json` for bodies |
| `responder/` | Embedded HTTP endpoint, attack-mode state, QR rendering, own-IP display | NanoHTTPD 2.3.1, ZXing core 3.5.4 |
| `ui/` | Observer camera screen with ring overlay, responder screen, settings, mode switch, debug harness | Jetpack Compose (BOM 2026.02.01), `AndroidView(PreviewView)` + `Canvas` overlay |
| laptop `dashboard/` | Collect events and the latest annotated frame; render a live page for judges | FastAPI 0.143.0 + uvicorn 0.51.0 (installed), in-memory ring buffer, 1 Hz polling page |
| laptop `eval/` | Precision/recall of RT-DETR v2 r18 vs OWLv2 on 10 images via `clikart-cli serve` | Python `requests`, IoU ≥ 0.5 matching |

## Recommended Project Structure

### Gradle modules

```
FriendOrFoe/                               (folder on disk is still FieldReport)
├── settings.gradle.kts                    include(":app"); include(":iff")      ← add :iff
├── build.gradle.kts                       + alias(libs.plugins.kotlin.jvm) apply false
├── gradle/libs.versions.toml              + kotlin-jvm plugin, okhttp, nanohttpd, zxing, serialization-json (test), coroutines
├── app/                                   com.android.application (AGP 9.2.1, built-in Kotlin 2.2.10)
│   └── src/main/java/com/chloeyeo/friendorfoe/…   (Studio generated src/main/java; keep it, Kotlin files live there)
├── iff/                                   org.jetbrains.kotlin.jvm 2.2.10 — NO Android dependency
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/chloeyeo/friendorfoe/iff/…, src/test/resources/vectors/*.json
├── laptop/                                dashboard/ (FastAPI), eval/ (run_eval.py, images/, labels.json), scripts/ (serve_*.ps1, push_model.ps1)
└── docs/PROTOCOL.md, README.md
```

`:iff` build file (the whole thing):

```kotlin
// iff/build.gradle.kts
plugins { alias(libs.plugins.kotlin.jvm) }          // kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }  (kotlin = "2.2.10")
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }   // matches :app compileOptions (VERSION_11)
dependencies {
    testImplementation(libs.junit)                                                     // junit 4.13.2, already in the catalog
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")     // JsonElement tree API only → no compiler plugin needed
}
```

Why these choices: AGP 9.0+ has "a runtime dependency on Kotlin Gradle plugin (KGP) 2.2.10" and upgrades lower KGP versions to it (AGP 9.0 release notes; the AGP 9.2.0 compatibility table also lists KGP 2.2.10, and `./gradlew buildEnvironment` on this project resolves `com.android.tools.build:gradle:9.2.1 → kotlin-gradle-plugin:2.2.10`, verified 2026-10-10), so `org.jetbrains.kotlin.jvm` at exactly `2.2.10` on a non-Android module coexists with the app's built-in Kotlin; do not apply `org.jetbrains.kotlin.android` anywhere ("not compatible with the new DSL"). Note: the ClikaRT AAR's POM depends on `kotlin-stdlib:2.3.0`, so `:app`'s runtime classpath already resolves `kotlin-stdlib 2.2.10 → 2.3.0` (verified with `:app:dependencies`); the 2.2.10 compiler accepts it (the project compiles today), and `:iff` keeps its own 2.2.10 stdlib. JUnit 4 avoids Gradle 9's JUnit-Platform launcher wiring entirely (the catalog already pins `junit = 4.13.2`). `:iff` main has zero dependencies, so it also builds as a plain jar for a future laptop/ESP32 reference check. The app module needs no `kotlinx.serialization` plugin: the wire payloads are base64url of fixed binary layouts produced by `:iff`, and the only JSON (dashboard events, `/v1/detections` replies) goes through Android's built-in `org.json`.

### Package layout (`com.chloeyeo.friendorfoe`)

```
app/src/main/java/com/chloeyeo/friendorfoe/
├── app/
│   ├── FriendOrFoeApplication.kt   # Application: nothing heavy; holds Settings
│   ├── AppRuntime.kt               # load(context, license) once on RuntimeLane; Os.setenv(CLIKA_RT_NUM_THREADS) BEFORE load; bridgeLogs; trimOnMemoryPressure
│   ├── RuntimeLane.kt              # verbatim pattern: single-thread dispatcher "clika-runtime-lane"
│   ├── LicenseStore.kt             # BuildConfig.CLIKA_RT_LICENSE (local.properties → clika.runtimeLicenseFile) else pasted once into filesDir/clika_rt_license
│   ├── ModelLocator.kt             # first existing dir of: getExternalFilesDir(null)/models/<name>, filesDir/models/<name>
│   └── Settings.kt                 # SharedPreferences: mode, responderBaseUrl, dashboardBaseUrl, masterKeyHex, challengerId, droneId, detectorKind, fovX/fovY, posOverride, headingOverride
├── detect/
│   ├── Detector.kt                 # interface Detector { suspend fun detect(frame: Frame): List<Detection>; fun close() } + Frame, Detection, PixelBox, ObjectClass
│   ├── ClikaRtDetector.kt          # AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions(offline=true, device="cpu")) → ObjectDetectionPipeline, all on RuntimeLane
│   ├── HttpDetector.kt             # JPEG → multipart "image" → POST <laptop>/v1/detections → same Detection list
│   ├── Detectors.kt                # fun create(settings): Detector  ← THE one-line swap (settings.detectorKind)
│   └── LabelMap.kt                 # RT-DETR label ids: 0→PERSON; 4,14,33→DRONE (aeroplane, bird, kite); OWLv2 phrases → DRONE
├── camera/
│   ├── CameraController.kt         # bind Preview + ImageAnalysis in a UseCaseGroup with previewView.viewPort; portrait-locked
│   ├── FrameAnalyzer.kt            # ImageAnalysis.Analyzer: RGBA copy (rowStride-aware), close ImageProxy at once, QR loop ≤10 fps, detector gate ≈1 fps
│   ├── FrameTransform.kt           # F→V matrix (cropRect→view, official setPolyToPoly recipe), V→F inverse for taps, N↔F helpers
│   ├── Orientation.kt              # TYPE_ROTATION_VECTOR → remapCoordinateSystem(AXIS_X, AXIS_Z) → azimuth (heading) + pitch of the back camera
│   └── CameraFov.kt                # fovX/fovY of the upright frame from Camera2 characteristics, else Settings defaults
├── track/
│   ├── Track.kt                    # id, NormBox, cls, score, lastSeenNs, misses, iff: IffState
│   ├── Tracker.kt                  # update(detections, frameSize, nowNs): greedy IoU ≥ 0.3 per class, EMA 0.6, drop after 3 misses unless CHALLENGED (coast ≤ 10 s)
│   └── Iou.kt
├── session/
│   ├── IffState.kt                 # sealed: Unchallenged | Challenged(nonce, t0) | Friend(droneId) | Foe(reason) | Unknown(reason)
│   ├── IffCoordinator.kt           # tap→Challenger.issue→ChallengeClient; QR bytes→Codec→Verifier→verdict; 10 s timeout; emits events
│   └── PhysicalGates.kt            # PROJECT.md third layer: RTT (POST sent → first QR decode) > 2.5 s → FOE·late response; hotspot RSSI vs near-field claim → FOE·signal mismatch; QR must persist 2 consecutive frames at plausible size → else FOE·optical mismatch
├── optical/
│   ├── Crop.kt                     # RGBA ByteArray + PixelBox (+25 % padding, clamped) → Bitmap
│   └── QrReader.kt                 # BarcodeScanning.getClient(FORMAT_QR_CODE); process(InputImage.fromBitmap(crop, 0)) with an executor → ByteArray? via Barcode.getRawBytes() (+ boundingBox size for the persistence gate)
├── net/
│   ├── ChallengeClient.kt          # POST {responder}/challenge, text/plain base64url, connect 1 s / read 2 s, one retry
│   ├── EventClient.kt              # POST {dashboard}/api/events (JSON) and /api/frame (image/jpeg); never awaited, dropped on failure
│   └── Json.kt                     # org.json helpers
├── responder/
│   ├── ResponderServer.kt          # NanoHTTPD(8129): POST /challenge, GET /status, GET /debug/last-response (debug builds)
│   ├── ResponderState.kt           # StateFlow: mode HONEST|REPLAY|RELAY|SILENT|IMPOSTOR|LATE, lastChallenge, lastResponse, log
│   ├── QrRenderer.kt               # ZXing QRCodeWriter → Bitmap; raw 37 bytes in byte mode: encode(String(bytes, ISO_8859_1), QR_CODE, 600, 600, hints{CHARACTER_SET="ISO-8859-1", ERROR_CORRECTION=M, MARGIN=1}) → version 3-M (42-byte capacity)
│   └── LocalIp.kt                  # first non-loopback IPv4 (ServeService.wifiAddress pattern)
└── ui/
    ├── MainActivity.kt             # setContent { FriendOrFoeApp() }; FLAG_KEEP_SCREEN_ON
    ├── FriendOrFoeApp.kt           # mode switch Observer | Responder | Settings | Debug
    ├── CameraScreen.kt             # AndroidView(PreviewView, FILL_CENTER) + Canvas overlay + pointerInput taps + VerdictLog + status banner
    ├── OverlayPainter.kt           # ring (green/red/grey) + "drone · ?" / "FRIEND · RQ-07" / "FOE · replayed nonce" / "UNKNOWN · no response"
    ├── ResponderScreen.kt          # IP:port, drone picture with QR drawn over its centre, 6 buttons (Honest/Replay/Relay/Silent/Impostor/Late), last requests
    ├── SettingsScreen.kt
    └── DebugScreen.kt              # static-image detector harness (assets JPEG → ImageInput.Encoded), loopback protocol test (127.0.0.1)

iff/src/main/kotlin/com/chloeyeo/friendorfoe/iff/
├── Bytes.kt        # big-endian put/get, base64url (no padding) via java.util.Base64
├── Hmac.kt         # HMAC-SHA256 via javax.crypto.Mac; truncate(n)
├── Keys.kt         # dayKey(master, yyyymmdd) = HMAC(master, "FOF-day"‖YYYYMMDD) · droneKey(dayKey, droneId) = HMAC(dayKey, "FOF-drone"‖droneId)   (PROJECT.md FOF-1; see "Key hierarchy" note below)
├── Codec.kt        # Challenge(v, nonce16, t, challengerId, cmac8) / Response(v, droneId, noncePrefix8, lat, lon, alt, tag16): encode/decode (37 raw bytes ↔ QR byte mode; base64url only for HTTP/debug/vectors) + MAC inputs
├── Clock.kt        # fun interface Clock { fun nowSeconds(): Long }  (injected → deterministic tests)
├── NonceStore.kt   # issue(nonce, t, challengerId) · lookupByPrefix · markUsed · age; TTL 10 min for "replayed" vs "unknown" reasons
├── Challenger.kt   # issue(challengerId, clock, random): Challenge + record in NonceStore
├── Responder.kt    # handle(challenge, keys, clock, mode, pos): Accepted(response) | Rejected(reason)  — verifies cmac (dayKey), |t−now| ≤ 10 s, nonce unseen; IMPOSTOR mode tags with a wrong key, LATE mode is a 3 s delay applied by the Android caller
├── Verifier.kt     # verify(responseBytes, store, keys, boxGeometry, clock): Verdict.Friend(droneId) | Foe(reason) ; reasons: unknown nonce, replayed nonce, expired, bad tag (tried with dayKey of today ±1), position mismatch — the physical gates (late response, signal mismatch, optical mismatch) are applied by session/PhysicalGates, outside :iff
├── Geo.kt          # equirectangular range, initial bearing, elevation, wrapDeg
└── PositionCheck.kt# PROJECT.md gate: range ≤ R_max 3 km AND (near-field pass range < 30 m OR cone ±20° in bearing and elevation); a relayed 5 km position always fails the range gate
iff/src/test/kotlin/…/ CodecTest, KeysTest, RoundTripTest, ReplayTest, RelayTest, ImpostorTest, TimeoutTest, GeoTest, TestVectorsTest, VectorGenerator (main, regenerates vectors)
iff/src/test/resources/vectors/{friend,foe_replay,foe_relay,unknown_silent}.json (+ foe_bad_tag.json once the Impostor button is built)
```

### Structure Rationale

- **`:iff` is a separate JVM module, not a package in `:app`:** the hour plan runs it H4–7 "tests first", and the tests must run without a phone (`./gradlew :iff:test`). Zero Android imports also means the same jar can later check a Python/ESP32 responder against the vectors.
- **`detect/` owns the swap, not `ui/` or `session/`:** `Detectors.create(settings)` is the only place that knows which implementation exists. Both implementations return pixel boxes on the frame they were given — exactly the convention of ClikaRT (`VisionModel.detect`: "the boxes in the picture's pixels", `VisionModel.kt:37-38`) and of the serve API ("float-pixel boxes on the ORIGINAL image", `detection_engine.h:6-8`) — so `track/`, `session/`, `optical/` and the eval never change.
- **`track/` and `FrameTransform` are Android-free:** they use their own `PixelBox`/`NormBox` data classes rather than `android.graphics.RectF`, so they run as plain JVM unit tests inside `:app` too.
- **`responder/` is a package in the same APK, selected by `Settings.mode`:** one build, one install per phone; phone B never loads ClikaRT (the responder path touches no `io.clika` class), so B can be any arm64 phone and its RAM is free for the screen.
- **`session/` separates the protocol (`:iff`) from Android time/threads/network:** `IffCoordinator` is the only class that holds both a `Track` and a `NonceStore` record.

## Architectural Patterns

### Pattern 1: One runtime lane (mirrors the official samples)

**What:** Every ClikaRT call — `Modelverse.load`, model open, each detect, close — runs on one single-thread coroutine dispatcher, in order; nothing of the runtime ever runs on the main thread. This is the samples' `RuntimeLane` verbatim: "a single-thread dispatcher every load, close, generation and cache release runs on, in order. Two loads never race, nothing of the runtime runs on the main thread, and a close never runs beside the next open" (`examples-0.6.4/android/shared/.../RuntimeLane.kt:7-16`). The serve sample builds its whole `CoroutineScope(SupervisorJob() + RuntimeLane.dispatcher)` on it (`ServeService.kt:50`).
**When to use:** always for `ClikaRtDetector`; `HttpDetector` uses `Dispatchers.IO` instead but behind the same `suspend fun detect`.
**Trade-offs:** inference blocks the lane for 300–700 ms, which is fine because the lane does nothing else; the UI reads results through `StateFlow`. Inside ClikaRT, `VisionModel` has its own worker thread ("modelverse-vision", one request at a time, `VisionModel.kt:27-32, 183`) and `ObjectDetectionPipeline.invoke` blocks the caller until `onDone` (`Pipelines.kt:260-273, 297-317`), so the lane + the vision worker are the only two threads involved per request.

```kotlin
object RuntimeLane {  // app/RuntimeLane.kt — same as the sample
    val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "clika-runtime-lane") }.asCoroutineDispatcher()
}
object AppRuntime {   // app/AppRuntime.kt — SampleRuntime.load + the Android extras (SampleRuntime.kt:30-34, ClikaRtAndroid.kt:41-44, 101-104, 173)
    fun load(context: Context, license: String, threads: Int) {
        if (Modelverse.isLoaded) return
        Os.setenv("CLIKA_RT_NUM_THREADS", threads.toString(), true)   // read once at the first compute; set BEFORE load
        Modelverse.load(context.applicationContext, license)          // io.clika.modelverse.load extension (ModelverseAndroid.kt:18-30)
        ClikaRtAndroid.bridgeLogs()                                    // runtime log lines → logcat tag "ClikaRT"
        ClikaRtAndroid.trimOnMemoryPressure(context)
    }
}
class ClikaRtDetector(private val modelDir: File, private val opts: DetectOptions) : Detector {
    private lateinit var pipeline: ObjectDetectionPipeline
    suspend fun open() = withContext(RuntimeLane.dispatcher) {       // "Blocks for the load; call it off the main thread" (VisionModel.kt:196-197)
        val model = AutoModelForObjectDetection.fromPretrained(modelDir.absolutePath, VisionLoadOptions(offline = true, device = "cpu"))
        pipeline = Modelverse.pipeline("object-detection", model) as ObjectDetectionPipeline
    }
    override suspend fun detect(frame: Frame): List<Detection> = withContext(RuntimeLane.dispatcher) {
        pipeline(ImageInput.Rgba8888(frame.rgba, frame.width, frame.height), opts).map { it.toDetection() }   // boxes in frame pixels
    }
    override fun close() { runBlocking(RuntimeLane.dispatcher) { pipeline.close() } }   // close waits for a running request (VisionModel.kt:86-103)
}
```

`ImageInput.Rgba8888` is defined as "the pixels of an `ARGB_8888` bitmap as `Bitmap.copyPixelsToBuffer` writes them: four bytes per pixel in the order red, green, blue, alpha … The alpha is dropped" (`Types.kt:798-807`) — which is byte-for-byte what CameraX's `OUTPUT_IMAGE_FORMAT_RGBA_8888` delivers ("a single-plane with the order of R, G, B, A in increasing byte index", androidx `ImageAnalysis.java` Javadoc). No conversion is needed between the camera and the detector. `DetectOptions` defaults are `maxResults = 100`, `scoreThreshold = 0.5f` (`Types.kt:851-869`); start at 0.35 for drones and let the eval tune it.

### Pattern 2: Detector interface + factory = the H4 one-line swap

**What:** `Detector` is a two-method interface; the production and contingency implementations return identical `Detection(cls, label, labelId, score, box: PixelBox)` lists in the coordinates of the frame passed in. `Detectors.create(settings)` reads `settings.detectorKind` (`CLIKART_RTDETR` | `CLIKART_OWLV2` | `HTTP`).
**When to use:** from H1 on; the H4 checkpoint decision is literally flipping that setting (or its default constant).
**Trade-offs:** `HttpDetector` costs a JPEG encode (`Bitmap.createBitmap(ARGB_8888)` + `copyPixelsFromBuffer(rgba)` works directly because the byte layout matches) and a LAN round trip (laptop measured 0.37 s per 3840×2400 JPEG on CPU; a 720×1280 JPEG will be faster). Both implementations map labels **by id**: the RT-DETR table served by ClikaRT spells `0 person, 4 aeroplane, 14 bird, 33 kite` (snapshot `config.json` and the serve's `/props.id2label`), not `airplane`.

### Pattern 3: Attribution by construction — decode QR only inside the challenged box

**What:** the QR reader never sees the whole frame. For each track in `CHALLENGED`, `FrameAnalyzer` crops the track's box (+25 % padding, clamped to the frame) out of the same RGBA frame and hands only that bitmap to ML Kit. A QR shown anywhere else in the scene is invisible to the verifier, which is the "read from inside the box" check of FOF-1 at zero extra logic.
**When to use:** always; also gives the speed-up (a 400×400 crop decodes in tens of ms, so the QR loop can run at ~10 fps independent of the 1 fps detector).
**Trade-offs:** needs the tracker to coast: when phone B swaps its drone picture for the QR, RT-DETR may stop seeing a "drone" for a few frames. The tracker keeps a `CHALLENGED` track alive for the full 10 s window without matches, and the responder screen draws the QR **over the centre of the drone picture** (picture still visible around it) so detection usually continues anyway. Per PROJECT.md's optical gate, a decode counts only when the same raw bytes are read in **two consecutive crops** and the QR's `boundingBox` is a plausible fraction of the track box (e.g. 15–90 % of its width); a one-frame flash or an implausibly small/large code is `FOE · optical mismatch`.

### Pattern 4: Same APK, two roles, one protocol module

**What:** `Settings.mode` picks the Composable tree and which services start: Observer starts camera + detector (+ `IffCoordinator`); Responder starts `ResponderServer` and never touches ClikaRT. Both roles call the same `:iff` code (`Challenger`/`Verifier` on A, `Responder` on B), so a bug fixed once is fixed for both and the JSON vectors prove interoperability.
**Trade-offs:** the 300 MB native payload ships to phone B too (unused); acceptable — install over USB once.

### Pattern 5: Fire-and-forget telemetry

**What:** `EventClient` posts detections/verdicts and a small annotated JPEG to the dashboard from `Dispatchers.IO` with 0.5 s connect / 1 s write timeouts; failures are counted and dropped, never retried in the loop, never awaited by the detector or the state machine.
**Why:** the laptop is Tier 2; the demo must look identical with the laptop off.

## Data Flow

### Coordinate systems (named, with the transforms between them)

| Name | Definition | Who produces / consumes |
|------|------------|-------------------------|
| **F — frame pixels** | The upright RGBA analysis frame, origin top-left, `W_F × H_F` (portrait: e.g. 720 × 1280). Upright because `ImageAnalysis.Builder.setOutputImageRotationEnabled(true)` rotates the buffer and then "`ImageInfo.getRotationDegrees()` will return 0" (androidx Javadoc; cost "about 10-15ms for 640x480 image on a mid-range device"). | Detector input and output (`Detection.box` in F); QR crop origin; ClikaRT and serve both answer in F. |
| **crop ⊆ F** | `imageProxy.cropRect`: the part of F that the preview shows. Guaranteed consistent when Preview and ImageAnalysis are bound in one `UseCaseGroup` with the `PreviewView`'s `ViewPort`: "CameraX guarantees that the crop rects of all the use cases in the group point to the same area in the camera sensor … matches what the end user sees in `PreviewView`, assuming the `PreviewView`'s scale type is set to the default, `FILL_CENTER`" (CameraX configuration guide). | `FrameTransform` |
| **N — normalized frame** | `(x/W_F, y/H_F)` ∈ [0,1]². | `Track.box`; dashboard events; survives a detector/resolution swap. |
| **V — view pixels** | The `PreviewView`/Compose `Canvas` size. | Overlay drawing; tap input. |
| **C — crop bitmap pixels** | The QR crop; `C → F` is `+ (crop.left, crop.top)`. | ML Kit corner points (debug only). |
| **A — angles** | Bearing/elevation of a box centre: `bearing = heading + (cx_N − 0.5)·fovX`, `elevation = pitch + (0.5 − cy_N)·fovY`, with `fovX`/`fovY` the horizontal/vertical FOV **of the upright frame** (portrait: the narrow sensor axis is X ≈ 40°, the wide one is Y ≈ 66°; `fovShort = 2·atan(tan(fovLong/2)·short/long)`). | `PositionCheck` (in `:iff`, given the angles only). |

`F → V` is the official CameraX recipe: map the four corners of `cropRect` onto the four corners of the view with `Matrix.setPolyToPoly`, shifting the destination corners by `rotationDegrees/90` positions (the "transform output" guide's `getCorrectionMatrix`). With output rotation enabled, `rotationDegrees == 0`, so the matrix reduces to a uniform scale `s = viewW / crop.width()` and the offset `(−crop.left·s, −crop.top·s)`; invert it for taps. Request the analysis size with `ResolutionSelector` (`AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY` + `ResolutionStrategy(Size(1280, 720), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`) — the default analysis resolution is 640×480, too coarse for a QR ("the smallest meaningful unit of the barcode should be at least 2 pixels wide … it's recommended to provide a higher resolution image, such as 1280x720 or 1920x1080", ML Kit docs). **Verify on the device at H1–4 that `cropRect` is reported in rotated coordinates when output rotation is on** (MEDIUM confidence); if it is not, fall back to the FILL_CENTER centre-crop formula with equal 16:9 aspect strategies on both use cases, which gives the same numbers.

### Request flow (observer, one frame)

```
CameraX (30 fps, STRATEGY_KEEP_ONLY_LATEST, RGBA_8888, rotation enabled)
    ↓ camera executor (single thread)
FrameAnalyzer.analyze(imageProxy)
    ├─ copy planes[0] row by row (PlaneProxy.getRowStride() may exceed width·4 on some devices; the androidx Javadoc only promises a single R,G,B,A plane, not a stride) → Frame(rgba, W_F, H_F, cropRect, tNs); imageProxy.close() immediately
    ├─ if any track CHALLENGED and tNs − lastQr ≥ 100 ms → Crop → QrReader.process (ML Kit, async, callback on camera executor)
    │       ↓ rawBytes? → IffCoordinator.onQrBytes(trackId, bytes, qrBox, tNs) → PhysicalGates (2 consecutive frames, RTT) → Verifier → verdict → Track.iff, VerdictLog, EventClient
    └─ if detectorBusy.compareAndSet(false, true) && tNs − lastSubmit ≥ 700 ms → scope.launch { detector.detect(frame) } else drop
            ↓ RuntimeLane (ClikaRT) or Dispatchers.IO (HTTP), 300–700 ms
        Tracker.update(detections → N, nowNs) → StateFlow<Scene(tracks, frameSize, cropRect)>; detectorBusy = false; EventClient.post(scene)
            ↓ main thread (collectAsStateWithLifecycle)
        CameraScreen: Canvas draws each track with FrameTransform (N → F → V): ring colour from Track.iff; tap → V → F → N hit test → IffCoordinator.challenge(track)
```

At 1 fps with 300–700 ms inference the camera never waits: `analyze()` returns in ~1–3 ms (copy + dispatch), so ML Kit and the UI keep running; the overlay shows the last result (≤ 1 s old) and the tracker's EMA hides jitter. Boxes lag a moving target by up to one period — hold phone A still during the challenge. If inference is slower than 1 s on the phone, the gate simply yields a lower rate; nothing queues (a queue on the lane would grow without bound — the busy flag is what prevents that).

### State management: IFF state machine per track

```
                         tap (any state)                       QR decoded in box
UNCHALLENGED ──────────────────────────► CHALLENGED(nonce16, t0, challengerId) ──────────────► Verifier
     ▲                                     │        │                                    │  ok  → FRIEND(droneId)        green ring  "FRIEND · RQ-07"
     │ track dies (3 misses, not challenged)│        │ HTTP POST failed/refused           │  fail→ FOE(reason)            red ring    "FOE · replayed nonce" | "FOE · position mismatch" | "FOE · bad tag" | "FOE · unknown nonce" | "FOE · late response" | "FOE · signal mismatch" | "FOE · optical mismatch"
     │                                     │        └──────────────────────────────────► UNKNOWN("unreachable")  red ring  (tap to retry)
     └── new track                         └─ now − t0 ≥ 10 s and no verdict ─────────► UNKNOWN("no response")  red ring  "UNKNOWN · no response"  — never FRIEND
```

Rules: a track may be re-challenged from any state (each tap = a fresh nonce; the old nonce stays in `NonceStore` as *used* for 10 min so a replay is reported as "replayed nonce" rather than "unknown nonce"); verdicts stick to the track until it dies or is re-challenged (verdict expiry is a documented product rule, not built); while `CHALLENGED`, the tracker coasts the track without detections for the full window.

Verify order (FOF-1 as in PROJECT.md, plus its physical layer): (1) attribution is already guaranteed by the crop, and `PhysicalGates` requires the same bytes in two consecutive crops at a plausible size → else `optical mismatch`; (2) `PhysicalGates`: `tQr − tPostSent > 2.5 s` → `late response`; (3) inside `Verifier.verify`: `noncePrefix` → record in `NonceStore`: missing → `unknown nonce`; already used → `replayed nonce`; `now − t > 10 s` → `expired`; (4) recompute `tag` with `droneKey(dayKey(day), droneId)` for today, yesterday and tomorrow (PROJECT.md: "accepts yesterday/today/tomorrow") over the fixed binary layout → no match → `bad tag`; mark nonce used; (5) `PositionCheck(claimedPos, observerPos, boxBearing, boxElevation)` → `position mismatch (…)`; (6) `PhysicalGates`: hotspot RSSI inconsistent with a near-field (< 30 m) claim → `signal mismatch`; else `FRIEND`.

### Sequence (observer ↔ responder ↔ dashboard)

```
phone A (observer)                                 phone B (responder)                      laptop dashboard (optional)
│ detector → track#3 DRONE "?"                     │                                        │
│── POST /api/events {objects:[…]} (1 Hz) ───────────────────────────────────────────────►│ list + counts
│ tap #3 → Challenger.issue → nonce,t,cid,cmac     │                                        │
│── POST /challenge  "AQ…"(base64url, 42 ch) ─────►│ Responder.handle: cmac(dayKey(today))? │
│                                                  │   |t−now|≤10 s? nonce unseen?          │
│                                                  │   HONEST → response(pos, tag) → QR     │
│◄── 200 {"accepted":true}  (401 {"error":…} if cmac fails → no QR, nothing lights up) ─────│
│ track#3 := CHALLENGED(nonce, t0)                 │   [QR over the drone picture]          │
│ QR loop (≤10 fps) on crop of #3 → raw bytes ×2   │                                        │
│ RTT ≤ 2.5 s, Verifier → FRIEND·RQ-07 → green ring + VerdictLog                            │
│── POST /api/events {verdict…} + POST /api/frame (annotated JPEG) ──────────────────────►│ ring thumbnail
│ tap again → new nonce N2 ──────────────────────►│ REPLAY → shows previous QR (N1)         │
│ Verifier: prefix N1 found, used → FOE·replayed nonce                                      │
│ tap again → N3 ───────────────────────────────►│ RELAY → honest tag over a position 5 km away
│ Verifier: tag ok, PositionCheck: range 5 km > R_max 3 km → FOE·position mismatch           │
│ tap again → N4 ───────────────────────────────►│ SILENT → 200 accepted, screen stays blank│
│ t0 + 10 s → UNKNOWN·no response                 │                                        │
│ tap again → N5 ───────────────────────────────►│ IMPOSTOR → tag over a wrong drone key    │
│ Verifier: tag recomputes with no day key → FOE·bad tag                                     │
│ tap again → N6 ───────────────────────────────►│ LATE → honest QR shown after a 3 s delay │
│ PhysicalGates: RTT 3.x s > 2.5 s → FOE·late response                                       │
```

### Key data flows

1. **Frame → boxes → overlay:** F pixels from CameraX → `Detector` (F) → `Tracker` (N) → `Canvas` (V). Only `FrameTransform` knows V; only `Crop` goes back from N to F for the QR.
2. **Challenge:** `Challenger.issue` (31 bytes: `v u8=1 | nonce 16 | t u32 | challengerId u16 | cmac 8`, cmac = HMAC-SHA256(dayKey, "C"‖nonce‖t‖cid)[0:8] as PROJECT.md specifies) → base64url (42 chars) → `POST /challenge` → `Responder.handle`.
3. **Response:** `Responder` (37 bytes: `v u8 | droneId u16 | nonce[0:8] | lat i32 (deg·1e4) | lon i32 (deg·1e4) | alt i16 (m/10) | tag 16`, tag = HMAC-SHA256(droneKey, "R"‖nonce16‖t‖cid‖droneId‖lat‖lon‖alt)[0:16]) → **the raw 37 bytes in QR byte mode** (PROJECT.md decision: version 3-M holds 42 bytes, version 3 = 29 modules; at 1280×720 and 1–2 m a box ≥ 300 px gives ≥ 9 px per module) → `QrReader` (`Barcode.getRawBytes()`) → `Verifier`. base64url (50 chars) is used only for `GET /debug/last-response` and the JSON vectors. The MAC input is always the fixed byte layout, never text.
4. **Keys (follows PROJECT.md FOF-1; one flag for the user's confirmation):** `dayKey = HMAC(master, "FOF-day"‖YYYYMMDD)`, `droneKey = HMAC(dayKey, "FOF-drone"‖droneId)`, challenge cmac under `dayKey`, response tag under `droneKey`; the observer tries today ±1 for the tag (clock drift), the responder checks `|t − now| ≤ 10 s`. **Flag:** because the responder must hold `dayKey` to verify challenge MACs, and every `droneKey` derives from `dayKey`, a captured drone leaks *all* drones' keys for that day, not "only its own key for one day" as PROJECT.md states. The one-line fix, if the user confirms it, is a separate `chKey = HMAC(dayKey, "FOF-chal")` on the drones (they then never hold `dayKey`) and per-drone keys placed by the observer; byte layouts are unchanged either way, so the decision can be taken at H4 without touching `Codec`.
5. **Events:** `Scene` → JSON → dashboard; verdict changes are separate events so the dashboard can count FRIEND/FOE/UNKNOWN without diffing.

## Threading Model

| Lane | Thread(s) | Runs | Never |
|------|-----------|------|-------|
| Main | UI | Compose, overlay, taps, `StateFlow` collection, Settings | ClikaRT, ML Kit `process`, network, bitmap copies |
| Camera | `Executors.newSingleThreadExecutor("camera-analysis")` passed to `setAnalyzer` | RGBA copy, `close()`, crop, ML Kit submit (callbacks via the same executor), detector gate | Blocking on inference or on HTTP |
| Runtime lane | `RuntimeLane.dispatcher` ("clika-runtime-lane") | `AppRuntime.load`, model open, every `pipeline()` call, `close()` — in order (samples' rule) | Anything else; two models at once |
| modelverse-vision | created by `VisionModel` (`VisionModel.kt:27-32`) | the native detect for the one in-flight request | — (ClikaRT-internal) |
| ML Kit | its own | QR decode of a crop | — |
| Net | OkHttp dispatcher / `Dispatchers.IO` | challenge POST (awaited by the coordinator), events/frames (never awaited) | — |
| Responder server | NanoHTTPD accept + request threads | `Responder.handle` (microseconds), `ResponderState` update → Compose re-renders the QR | ClikaRT (phone B never loads it) |

CPU threads for ClikaRT: `CLIKA_RT_NUM_THREADS` "is read once at the first compute: an app that sets it does so in its own process (`Os.setenv`) before `load`. A phone with a few large cores and several small ones runs best at the large cores' count; measure on the device" (`ClikaRtAndroid.kt:41-44`). Default to the big-core count (typically 3–4) in `AppRuntime.load`, leaving cores for the camera, ML Kit and the UI.

## Networking Topology (hotspot LAN, no internet)

| Decision | Recommendation | Confidence / fallback |
|----------|----------------|------------------------|
| Who hosts the hotspot | **Phone B (responder)**, mobile data off. B is then the gateway, so the critical path A→B is client→gateway traffic, which works even if the hotspot isolates clients. The laptop joins as a client. | LOW on airplane-mode + hotspot coexistence (OEM-specific) — test at H0; fallback: airplane mode off, mobile data off, Wi-Fi only. Phone A hosting also works (B then shows its DHCP lease). Do **not** rely on the Windows Mobile Hotspot: Windows refuses to start one without an upstream connection ("if Windows 11 is not connected to any wireless network or Ethernet, then you cannot create a mobile hotspot", Microsoft Q&A). |
| Addresses | **Shown, not static.** `LocalIp` enumerates `NetworkInterface`s and shows the first non-loopback IPv4 (the serve sample's `wifiAddress()`, `ServeService.kt:154-163`) as `http://<ip>:8129` on B's screen (optionally also as a QR phone A can scan with the same ML Kit reader). Phone A stores `responderBaseUrl` and `dashboardBaseUrl` in Settings; a "Ping" button hits `GET /status` and `GET /health`. | HIGH |
| Cleartext | HTTP on a LAN is cleartext; "Starting with Android 9 (API level 28), cleartext support is disabled by default" (network security config docs). Add `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true"/>` and reference it from `<application android:networkSecurityConfig=…>` (IP targets have no domain to whitelist). Add `INTERNET` permission (the serve sample manifest does). | HIGH |
| Timeouts | Challenge: connect 1 s, read 2 s, one retry, then `UNKNOWN(unreachable)`. QR window: 10 s from `t0`. Events/frames: connect 0.5 s, write 1 s, dropped on failure. Responder: NanoHTTPD socket read timeout 5 s. | HIGH (design) |
| Windows firewall | The hotspot network is usually profiled *Public*; allow inbound TCP 8000 (serve) and 8080 (dashboard) for `clikart-cli.exe` and `python.exe`, or set the profile to Private, at H0. | MEDIUM |
| Responder lifetime | Run the server in the Responder screen's scope with `FLAG_KEEP_SCREEN_ON`; no foreground service needed while the screen is on. Upgrade path if the screen must sleep: the serve sample's foreground service (`foregroundServiceType="connectedDevice"`, `ServeService.kt:64`). | HIGH |
| RSSI gate (PROJECT.md physical layer) | Phone A reads the RSSI of the hotspot it is attached to (phone B): API 31+ `ConnectivityManager.getNetworkCapabilities(activeNetwork).transportInfo as WifiInfo` → `getRssi()`, else `WifiManager.connectionInfo.rssi`; needs `ACCESS_WIFI_STATE` (+ `ACCESS_FINE_LOCATION` on API 29+ for the SSID/BSSID fields, not for RSSI). Compare against a calibrated near-field band (e.g. ≥ −65 dBm at ≤ 5 m, measured at H0); outside the band with a near-field claim → `FOE · signal mismatch`. This is why B hosts the hotspot. | MEDIUM — API behaviour per phone/OEM unverified here; calibrate at H0 |

### Responder endpoint contract (phone B, port 8129)

| Route | Request | Response |
|-------|---------|----------|
| `POST /challenge` | `Content-Type: text/plain`; body = base64url challenge (42 chars) | `200 {"accepted":true,"mode":"honest"}` and the QR appears (Honest/Replay/Relay/Impostor at once, Late after 3 s) or nothing appears (Silent); `401 {"error":"bad cmac"}` / `400 {"error":"stale"|"replayed challenge"|"malformed"}` and no QR |
| `GET /status` | — | `200 {"droneId":7,"label":"RQ-07","mode":"honest","ip":"192.168.x.y","challenges":3}` |
| `GET /debug/last-response` | debug builds only | `200 text/plain` base64url of the last response (for the one-phone loopback test) |

Attack buttons set `ResponderState.mode` (the six of PROJECT.md): **Honest** = fresh response over the configured honest position; **Replay** = show the previous response's QR again (and still answer 200); **Relay** = fresh, correctly tagged response over `honestPos + 5 km` at a configurable bearing (default 180°) — beyond `R_max` 3 km so the range gate fails deterministically indoors; **Silent** = accept, show nothing; **Impostor** = fresh response tagged with a wrong key (`FOE · bad tag`); **Late** = honest response shown after a 3 s delay (`FOE · late response` by the 2.5 s RTT gate). All six accept the HTTP request, so the observer's HTTP step always succeeds and every verdict comes from the optical/verify path — exactly the six demo outcomes (FRIEND, replayed nonce, position mismatch, no response, bad tag, late response).

### Dashboard contract (laptop, port 8080, Tier 2)

| Route | Body | Notes |
|-------|------|-------|
| `POST /api/events` | `{"t":1760000000.123,"device":"phoneA","frame":{"w":720,"h":1280},"objects":[{"trackId":3,"class":"drone","label":"kite","score":0.62,"box":[x0,y0,x1,y1],"iff":"FRIEND","droneId":"RQ-07","reason":null}],"verdict":{"trackId":3,"iff":"FOE","reason":"replayed nonce"}}` (box in N) | `204`; one POST per detector result (≈1 Hz) |
| `POST /api/frame` | `image/jpeg` body, ≤ 1 Hz, ~480 px wide, rings already drawn by the phone | `204` |
| `GET /api/state?since=<seq>` | — | `{"seq":N,"counts":{"drones":1,"people":1,"friend":1,"foe":0,"unknown":0},"events":[…last 50…],"frameSeq":M}` |
| `GET /api/frame.jpg` | — | latest JPEG |
| `GET /dashboard` | — | one HTML page, `setInterval(fetch('/api/state'), 1000)`; **polling, not SSE** — 1 Hz matches the detector, no `sse-starlette` dependency, no long-lived connections to debug on a flaky hotspot |

Run: `uvicorn app:app --host 0.0.0.0 --port 8080` from `laptop/dashboard` (FastAPI 0.143.0 / uvicorn 0.51.0 are installed). Because judging happens on the phones, this page is a bonus view; a `VerdictLog` panel on phone A's own screen (last five verdicts) is the primary judges' history. If a bigger picture is wanted, mirror phone A over USB (e.g. scrcpy) — no network needed, zero code.

### `clikart-cli serve` contract (laptop, port 8000) — used by `HttpDetector` and `eval/run_eval.py` (verified live 2026-10-10)

```
clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd serve --host 0.0.0.0 --port 8000 --device cpu
clikart-cli --offline --cache-dir C:/Users/C/clika/models google/owlv2-base-patch16-ensemble serve --host 0.0.0.0 --port 8001 --device cpu
```

| Route | Request | Response |
|-------|---------|----------|
| `POST /v1/detections` | `multipart/form-data` with the file in field **`image`** (not `file`: a wrong name answers `400 {"error":{"message":"multipart/form-data with an 'image' field is required",…}}`); optional form fields `confidence` (float, default 0.5), `max_detections` (int, default 100); **`prompt`** required by OWLv2 (`400 "this detection model requires a 'prompt' text field naming the classes to find"`), refused by RT-DETR (`400 "this detection model takes no text prompt; it serves a fixed vocabulary"`); comma-separated phrases accepted (`"drone,person,tree"`, echoed back) | `200 {"model":"rt-detr","image":{"width":3840,"height":2400},"detections":[{"box":{"xmin":1788.07,"ymin":1259.93,"xmax":3299.73,"ymax":2385.38},"score":0.503,"label_id":46,"label":"banana"}]}` — pixels of the uploaded image, score-descending; OWLv2 adds `"prompt":"…"` |
| `GET /health` | — | `{"status":"ok"}` |
| `GET /props` | — | `id2label` (80 COCO classes, `4 aeroplane / 14 bird / 33 kite`), `open_vocabulary`, `controls` (`confidence`, `max_detections`, `prompt`) |
| `GET /dashboard` | — | the serve's own live page (SC1 "second device type" evidence) |

Measured on this laptop (12 CPU threads): RT-DETR loads in ~0.3 s and answers a 3840×2400 JPEG in **0.37 s**; OWLv2 base loads in ~1 s and answers in **~5 s per image**. Implication: OWLv2 is an eval/accuracy option only (and, through `HttpDetector`, ~0.2 fps); the live on-device detector is RT-DETR v2 r18 (640×640 input per `preprocessor_config.json`).

## Runtime Files and Startup

| Artefact | Where at runtime | How it gets there |
|----------|------------------|-------------------|
| Native payload (`libClikaRT.so` 99.7 MB, `libClikaRT_vulkan.so` 139.3 MB, `libClikaRT_modelverse.so` 19.1 MB, `libclika_rt_jni.so` 14.3 MB, `libclika_modelverse_jni.so` 11.9 MB, all `jni/arm64-v8a/`) | Inside the APK (`lib/arm64-v8a/`), loaded by `System.loadLibrary` from `ClikaRt.load()` ("ClikaRT", "clika_rt_jni", `ClikaRt.kt:85-86`) and `Modelverse.load()` ("ClikaRT_modelverse", "clika_modelverse_jni", `Modelverse.kt:27-28, 58-59`) | The one dependency line `io.clika:clika-runtime:0.6.4` (AAR from the local Maven repo `C:/Users/C/clika`); keep `ndk { abiFilters += "arm64-v8a" }` (as `hello/build.gradle.kts`) and the default uncompressed jniLibs packaging (do not set `useLegacyPackaging = true`: it would extract 300 MB on install) |
| Model snapshot (`config.json`, `preprocessor_config.json`, `model.safetensors` = 80.9 MB for RT-DETR r18; 593 MB for OWLv2) | `getExternalFilesDir(null)/models/rtdetr_v2_r18vd/` (first choice) or `filesDir/models/rtdetr_v2_r18vd/`; `ModelLocator` checks both and the Debug screen shows which one it found | `adb push C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/. /sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd/`. If the device refuses writes into `Android/data` (reported on some Android 13+ builds; LOW confidence either way — test at H0), use the debuggable-app route: `adb push <snapshot> /data/local/tmp/ffmodel && adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models && cp -r /data/local/tmp/ffmodel files/models/rtdetr_v2_r18vd'`. The load source is the **directory path** ("a local directory or a hub repo id", `Auto.kt:145-146`; the chat sample accepts "a snapshot directory on the phone", `chat/MainActivity.kt:47`) with `VisionLoadOptions(offline = true, device = "cpu")`, so no hub-cache layout is needed on the phone |
| License (`CLIKA1-…`, 297 chars) | `BuildConfig.CLIKA_RT_LICENSE` at build time from `local.properties` → `clika.runtimeLicenseFile=C:/Users/C/.clika/runtime/license` (gitignored; the `hello/build.gradle.kts` recipe), else pasted once and kept in `filesDir/clika_rt_license` (`LicenseStore.kt`) | Passed to `Modelverse.load(context, license)`, which sets `CLIKA_RT_LICENSE` for the process (`ClikaRtAndroid.kt:234-252`). The runtime binds a license to `Settings.Secure.ANDROID_ID` (`ClikaRtAndroid.kt:214-218`), and an online credential fails with `LICENSE_SERVER_UNREACHABLE` without network (`ClikaRtAndroid.kt:73-76`) — so **phone A in airplane mode must be tested at H0–1**; phone B never loads the runtime |
| Runtime cache (GPU probe evidence etc.) | `context.cacheDir` via `XDG_CACHE_HOME` pinned by `ClikaRtAndroid.load` (`ClikaRtAndroid.kt:188-210`) | automatic |
| Settings (mode, URLs, key, ids, overrides) | `SharedPreferences` | Settings screen |
| Test images for the static harness | `app/src/main/assets/eval/*.jpg` (same files as `laptop/eval/images`) | repo |

**Startup sequence (Observer):** `MainActivity` → read `Settings.mode` → request `CAMERA` (+ `ACCESS_FINE_LOCATION` optional, used only if no position override) → show "Loading detector…" → `scope.launch(RuntimeLane.dispatcher) { AppRuntime.load(ctx, license, threads); detector = Detectors.create(settings).also { it.open() } }` (≈1–3 s: 81 MB read; the laptop loads it in 0.3 s) → on success bind CameraX → `FrameAnalyzer` starts the loop; on `ClikaRtException`/`ModelverseException` show the code name (`LICENSE_FAILED`, `LICENSE_SERVER_UNREACHABLE`, missing model dir) with a button to switch to `HttpDetector`. **Startup (Responder):** `ResponderServer.start()` on port 8129 → show IP → `FLAG_KEEP_SCREEN_ON`; no runtime load.

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Demo: 1 observer, 1 responder, ≤ 3 tracks | As designed. One challenge in flight per track; `NonceStore` holds many; QR loop per `CHALLENGED` track (keep ≤ 2 concurrent crops). |
| Several friendly drones in view | Nothing changes on A (tracks + nonces are independent). On B-like responders each shows only its own QR; the box crop keeps attribution. Concurrent challenges from one observer are fine; the responder answers the latest challenge only. |
| Several observers | `challengerId` already binds the tag to one observer; a responder's single screen shows the newest QR, so a second observer re-challenges after the first verdict (documented limitation). |
| Range | QR is readable at 1–2 m; the pitch states it stands in for a modulated optical beacon (OOK blink, hundreds of metres). `optical/` is the only package to swap; `:iff` byte layouts stay. |

### Scaling priorities

1. **First bottleneck:** CPU on phone A (inference 300–700 ms + CameraX rotation ~30 ms/frame at 720p + ML Kit). Fix: cap analysis to ≤ 10 fps by returning early, 1 fps detector gate, big-core thread count, 1280×720 not 1080p.
2. **Second bottleneck:** the hotspot (latency spikes, client isolation). Fix: only the challenge is on the critical path (one 42-char POST); everything else is fire-and-forget.

## Anti-Patterns

### Anti-Pattern 1: Running inference on the camera executor
**What people do:** call `pipeline(...)` inside `analyze()`.
**Why it's wrong:** `analyze()` is the back-pressure point; a 700 ms call stalls frame delivery, the QR loop and (through `STRATEGY_KEEP_ONLY_LATEST`) makes every delivered frame old. "The application is responsible for calling `ImageProxy#close()`… failing to close the image will cause future images to be stalled or dropped" (androidx Javadoc).
**Do this instead:** copy, close, dispatch to the runtime lane behind a busy flag; drop frames while busy.

### Anti-Pattern 2: Touching ClikaRT from more than one thread (or from the main thread)
**What people do:** load in `onCreate`, detect from a coroutine on `Dispatchers.Default`, close in `onDestroy`.
**Why it's wrong:** `VisionModel.open` "blocks for the load; call it off the main thread" (`VisionModel.kt:196-197`); `close()` cancels and waits for a running request (`VisionModel.kt:86-103`); two loads racing is exactly what the samples' lane exists to prevent.
**Do this instead:** every runtime call on `RuntimeLane.dispatcher`, including `close()`.

### Anti-Pattern 3: Logic keyed on label strings
**What people do:** `if (label == "airplane")`.
**Why it's wrong:** the served table says `aeroplane`; OWLv2 returns the prompt phrase as the label and the phrase index as the id (`detection_engine.h:95-97`, `Types.kt:856-862`).
**Do this instead:** `LabelMap` by `labelId` per detector kind; `label` is display only.

### Anti-Pattern 4: Decoding QR codes in the whole frame
**Why it's wrong:** destroys attribution — any QR anywhere would be credited to the challenged drone; also 5–10× slower.
**Do this instead:** crop the challenged box (Pattern 3).

### Anti-Pattern 5: Treating a successful HTTP POST, or a timeout, as a verdict
**Why it's wrong:** the HTTP channel is the RF stand-in; only the optical response verified by `Verifier` may produce FRIEND. Timeout is UNKNOWN, never FRIEND; HTTP failure is UNKNOWN(unreachable).

### Anti-Pattern 6: JSON as MAC input
**Why it's wrong:** key order/whitespace make MACs non-reproducible across implementations; FOF-1 mandates the fixed byte layout. JSON appears only in dashboard events and test-vector files.

### Anti-Pattern 7: Unbundled ML Kit
**What people do:** `com.google.android.gms:play-services-mlkit-barcode-scanning`.
**Why it's wrong:** its model is "dynamically downloaded via Google Play Services … Requests you make before the download has completed produce no results" — fatal in airplane mode. Keep `com.google.mlkit:barcode-scanning:17.3.0` ("statically linked to your app at build time").

### Anti-Pattern 8: The laptop in the critical path
**Why it's wrong:** contradicts the user's demo correction and SC1's "network blocked" framing; a dead dashboard must not change what judges see on the phones.
**Do this instead:** `EventClient` fire-and-forget; `VerdictLog` on phone A.

## Suggested Build Order (mapped to the hour plan)

| Hours | Build (in dependency order) | Testable without a second phone | Exit criterion |
|-------|-----------------------------|--------------------------------|----------------|
| **H0–1 Foundation** | `settings.gradle.kts` + `:iff` skeleton (one passing test); `:app` deps (okhttp 5.5.0, nanohttpd 2.3.1, zxing-core 3.5.4, coroutines-android 1.11.0, project(":iff")); `abiFilters arm64-v8a`; `BuildConfig.CLIKA_RT_LICENSE`; manifest (`INTERNET`, `CAMERA`, network-security-config); `app/AppRuntime`, `RuntimeLane`, `LicenseStore`, `ModelLocator`; a "Hello runtime" Debug screen (version + backends, `hello/MainActivity.kt` pattern); push the RT-DETR snapshot; laptop serve + firewall rules; GitHub repo | `./gradlew :iff:test :app:assembleDebug`; on phone A **in airplane mode**: runtime version shown, no `LICENSE_SERVER_UNREACHABLE`; `curl -F image=@x.jpg http://<laptop>:8000/v1/detections` | ClikaRT loads offline on the phone; the model dir is found; serve answers |
| **H1–4 Detection** | `detect/Detector`, `LabelMap`, `ClikaRtDetector`; `camera/CameraController`, `FrameAnalyzer` (gate only, no QR yet), `FrameTransform`; `track/Tracker`; `ui/CameraScreen` + `OverlayPainter` ("drone · ?" / "person") | Static-image harness in `DebugScreen` (`ImageInput.Encoded(assets jpeg)` → boxes; compare with `clikart-cli rt-detr detect` on the same file = parity); JVM unit tests for `Tracker` and `FrameTransform` | **H4 checkpoint:** live boxes on a person and on a drone picture at ≈1 fps in airplane mode. If not: `Detectors.create` → `HttpDetector` (needs only `net/` multipart + the laptop on the hotspot) and continue |
| **H4–7 Protocol** | `:iff` complete, tests first: `Bytes`, `Hmac`, `Keys` (PROJECT.md derivation, today ±1), `Codec` (round trip, exact byte lengths 31/37), `NonceStore`, `Challenger`, `Responder` (incl. wrong-key mode), `Verifier`, `Geo`, `PositionCheck` (3 km / 30 m / ±20°); `VectorGenerator` → four JSON vectors (+ bad-tag); `TestVectorsTest` replays them; **user confirms the key-hierarchy flag here** | Entirely on the JVM | `./gradlew :iff:test` green; vectors committed |
| **H7–10 Responder + wiring** | `responder/` (server, state, QR renderer, IP), `ui/ResponderScreen` (drone picture + QR overlay + 6 buttons: Honest/Replay/Relay/Silent/Impostor/Late); `net/ChallengeClient`; `session/IffCoordinator` (tap → challenge → CHALLENGED, timeout → UNKNOWN); mode switch; Settings (URLs, key, ids, position/heading overrides) ; **then, only if on time,** `laptop/dashboard` + `EventClient` | One-phone loopback: Observer mode with `responderBaseUrl = http://127.0.0.1:8129` while the in-process server runs → tap → 200 → `GET /debug/last-response` → feed the string through `Verifier` in the Debug screen → FRIEND; Replay/Relay/Silent/Impostor buttons exercised the same way | Phone A challenges phone B over the hotspot and B's QR appears; UNKNOWN after 10 s on Silent |
| **H10–12 Optical + verdicts** | `optical/Crop` + `QrReader` (`rawBytes`) in the analyzer loop; `Verifier` wired; `session/PhysicalGates` (RTT 2.5 s, two-frame persistence, RSSI band); rings/tags/reasons; `VerdictLog`; event posting; beep/TTS (stretch) | QR of a known vector (raw bytes, ISO-8859-1 encoded) printed on paper or shown on the laptop screen inside a box | Full six-verdict demo on two phones in airplane mode (FRIEND, replayed nonce, position mismatch, no response, bad tag, late response) |
| **H14–17 Eval + hardening** | `laptop/eval/run_eval.py` (RT-DETR vs OWLv2 via serve; P/R at IoU ≥ 0.5), threshold tuning into `Settings`, three airplane-mode run-throughs, backup video, README | laptop only | Detector + threshold decided; demo script repeatable |
| **H17–20 Buffer** | `docs/PROTOCOL.md` (threat table + the key-hierarchy note above), pitch rehearsal, submission | — | — |

Cut order (unchanged from PROJECT.md): audio alert → OWLv2 option → Relay button → eval table. The dashboard sits after the Relay button in practice: it is Tier 2 by the user's correction.

## Integration Points

### External services / libraries

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| ClikaRT 0.6.4 AAR (`io.clika:clika-runtime`) | `Modelverse.load(context, license)` once on the lane; `AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions)`; `ObjectDetectionPipeline(image, DetectOptions)`; OWLv2 via `AutoModelForZeroShotObjectDetection` + `candidateLabels` (`Pipelines.kt:302-308`) | minSdk 28, arm64 only; `device = "cpu"` first, try `"vulkan"` only as a measured option (139 MB backend; driver faults are remembered in the cache root) |
| CameraX 1.4.1 (`camera-camera2`, `camera-lifecycle`, `camera-view`) | `ImageAnalysis` RGBA_8888 + output rotation + `ResolutionSelector`; `UseCaseGroup` with `previewView.viewPort`; `PreviewView` FILL_CENTER | 1.4.1 is what the project declares; current stable is 1.6.2 (Aug 2026) — no need to upgrade for these APIs (all present since 1.3) |
| ML Kit barcode-scanning 17.3.0 (bundled) | `BarcodeScanning.getClient(BarcodeScannerOptions(FORMAT_QR_CODE))`, `InputImage.fromBitmap(crop, 0)`, `process().addOnSuccessListener(executor, …)`, read `barcode.rawBytes` ("raw bytes as it was encoded in the barcode"; `rawValue` is only for UTF-8 payloads) and `barcode.boundingBox` | 2 px per module minimum; pass an executor so callbacks stay off the main thread; 17.3.0 is the current bundled artifact on the ML Kit page (unbundled is 18.3.1 — do not use) |
| ZXing core 3.5.4 (latest, 2025-11) | `QRCodeWriter().encode(String(bytes, ISO_8859_1), QR_CODE, 600, 600, hints{CHARACTER_SET="ISO-8859-1", MARGIN=1, ERROR_CORRECTION=M})` → `Bitmap`; ISO-8859-1 maps one char to one byte so arbitrary bytes ride byte mode | responder only |
| NanoHTTPD 2.3.1 | `class ResponderServer(port) : NanoHTTPD(port) { override fun serve(session) }`; `session.parseBody(map)` for the POST body | latest release (2016) is fine: plain Java, no deps |
| OkHttp 5.5.0 (latest stable on Maven Central) | `OkHttpClient` with per-use timeouts; `MultipartBody` for `/v1/detections`; `RequestBody.create(JSON)` for events | one client instance per app |
| `clikart-cli serve` (laptop) | see contract above | start with `--offline`, `--host 0.0.0.0`, firewall allowed |
| FastAPI 0.143.0 + uvicorn 0.51.0 | `POST /api/events`, `POST /api/frame`, `GET /api/state`, `GET /dashboard` | polling page; in-memory state |

### Internal boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `camera/FrameAnalyzer` ↔ `detect/Detector` | `suspend detect(Frame): List<Detection>` behind a busy flag | the only place frames are copied |
| `detect/` ↔ `track/` | `Tracker.update(List<Detection>, frameSize)`; F → N here | `track/` never sees pixels |
| `track/` ↔ `session/IffCoordinator` | `Track.iff` mutated only by the coordinator; the tracker reads it to decide coasting | single writer |
| `session/` ↔ `:iff` | `Challenger.issue`, `Verifier.verify`, `NonceStore` — pure functions + one store | `:iff` gets a `Clock`, never `System.currentTimeMillis()` directly |
| `session/` ↔ `net/ChallengeClient` | awaited `suspend post(url, text): Result` | the one awaited network call |
| `session/`, `track/` ↔ `net/EventClient` | fire-and-forget `post(scene)` / `postVerdict` / `postFrame(jpeg)` | never awaited |
| `optical/QrReader` ↔ `session/` | `onQrBytes(trackId, bytes, qrBox, tNs)` callback on the camera executor | the coordinator synchronises on its own mutex; `PhysicalGates` keeps the previous frame's bytes per track for the two-frame rule |
| `responder/ResponderServer` ↔ `:iff Responder` | `handle(challengeBytes, keys, clock, mode, pos)` | microseconds; no coroutine needed |
| `ui/` ↔ everything | `StateFlow<Scene>`, `StateFlow<ResponderState>`, Settings | main thread only |

## Sources

Local ground truth (ClikaRT 0.6.4), cited by file:line above:
- `C:/Users/C/clika/reference/examples-0.6.4/android/shared/src/main/kotlin/io/clika/examples/android/shared/RuntimeLane.kt` (7-16), `SampleRuntime.kt` (28-37), `LicenseStore.kt` (15-37); `serve/.../ServeService.kt` (50, 64, 76, 154-163); `hello/build.gradle.kts` (license BuildConfig, `abiFilters`), `hello/.../MainActivity.kt`; `chat/.../MainActivity.kt:47`; `android/README.md`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/VisionModel.kt` (27-52, 86-103, 183, 196-218), `Pipelines.kt` (260-273, 297-317), `Auto.kt` (145-146, 283-294), `Types.kt` (798-811 ImageInput, 832-845 VisionLoadOptions, 851-869 DetectOptions, 877-885 Box/Detection), `Listeners.kt` (115-131), `Modelverse.kt` (27-28, 58-59, 177-199); `androidMain/io/clika/modelverse/ModelverseAndroid.kt` (18-30); `androidMain/io/clika/runtime/ClikaRtAndroid.kt` (41-44, 73-76, 101-104, 173, 188-252); `commonMain/io/clika/runtime/ClikaRt.kt` (85-86)
- `C:/Users/C/clika/reference/modelverse-server-headers/serve_api.h` (27-31, 89-90, 128-131), `detection_engine.h` (6-10, 17-21, 65-77, 95-97)
- AAR contents: `~/.gradle/caches/modules-2/files-2.1/io.clika/clika-runtime-android/0.6.4/.../clika-runtime.aar` (`jni/arm64-v8a/*.so` sizes listed above)
- Live probes 2026-10-10 of `clikart-cli … serve` (RT-DETR on :8765/:8766, OWLv2 on :8767): `/health`, `/props`, `/v1/models`, `/v1/detections` with `image`/`file`/no field, `confidence`, `max_detections`, `prompt`; timings 0.37 s (RT-DETR) and ~5 s (OWLv2) per 3840×2400 JPEG; `clikart-cli rt-detr detect --help`, `owlvit detect --help`, `rt-detr serve --help`
- Snapshot `C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/{config.json (id2label), preprocessor_config.json (640×640)}`
- Project: `C:/Users/C/AndroidStudioProjects/FieldReport/{settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, gradle/libs.versions.toml, gradle/gradle-daemon-jvm.properties (toolchain 21), app/src/main/AndroidManifest.xml, .planning/PROJECT.md}`

Official documentation and registries (fetched 2026-10-10):
- AGP 9.0 release notes — built-in Kotlin, KGP 2.2.10 runtime dependency: https://developer.android.com/build/releases/agp-9-0-0-release-notes ; migration page (kapt, KMP, `enableKotlin`): https://developer.android.com/build/migrate-to-built-in-kotlin
- CameraX `ImageAnalysis.java` Javadoc (RGBA single plane, rotation → `getRotationDegrees()` 0, 10-15 ms, KEEP_ONLY_LATEST, close): https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageAnalysis.java ; `PreviewView.java` (FILL_CENTER default, `getOutputTransform` experimental/COMPATIBLE): https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-view/src/main/java/androidx/camera/view/PreviewView.java ; `ResolutionStrategy.java` constants: https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/resolutionselector/ResolutionStrategy.java
- CameraX guides — transform output (`getCorrectionMatrix`): https://developer.android.com/media/camera/camerax/transform-output ; configuration (ViewPort/UseCaseGroup crop-rect guarantee, 640×480 default analysis size): https://developer.android.com/media/camera/camerax/configuration ; releases (stable 1.6.2, Aug 2026): https://developer.android.com/jetpack/androidx/releases/camera
- ML Kit barcode scanning (bundled 17.3.0 vs unbundled 18.3.1, 2 px/module, 1280×720+, `InputImage.fromBitmap`): https://developers.google.com/ml-kit/vision/barcode-scanning/android
- Android network security config (cleartext off at API 28+, `base-config` opt-in): https://developer.android.com/privacy-and-security/security-config
- Maven Central listings: OkHttp 5.5.0 (latest/release in maven-metadata, 2026-10-10) https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/ ; NanoHTTPD 2.3.1 https://repo1.maven.org/maven2/org/nanohttpd/nanohttpd/ ; ZXing core 3.5.4 https://repo1.maven.org/maven2/com/google/zxing/core/ ; kotlinx-serialization-json 1.11.0 https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/ ; kotlinx-coroutines-android 1.11.0 https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/ ; JUnit Jupiter 5.14.4 / 6.1.3 https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/
- FastAPI 0.143.0 on PyPI: https://pypi.org/pypi/fastapi/json ; Gradle 9 upgrade guide (test defaults): https://docs.gradle.org/current/userguide/upgrading_major_version_9.html
- Hotspot/adb (LOW confidence, community sources): Windows hotspot needs an upstream connection — https://learn.microsoft.com/en-us/answers/questions/1477390/how-to-activate-mobile-wireless-coverage-zone-in-w ; Android hotspot gateway commonly 192.168.43.1 but subnet varies by vendor/version — https://code.briarproject.org/briar/briar/issues/1328 , https://eu.community.samsung.com/t5/altri-smartphone/indirizzo-di-rete-per-router-wi-fi/td-p/3792000 ; `adb push` into app-private dirs can be refused on non-rooted devices — https://anexplorer.io/solve/storage-permissions-android

## Verification notes (2026-10-10)

Adversarial review against the local ClikaRT 0.6.4 sources, the real `clikart-cli.exe`, Maven Central / PyPI / Google Maven metadata, the official Android docs and PROJECT.md. The template structure above is unchanged; corrections were made in place.

**Corrected (contradictions with PROJECT.md, fixed in PROJECT.md's favour):**
- QR payload: PROJECT.md decided the QR carries the **raw ~40-byte response in QR byte mode** and the observer reads `Barcode.getRawBytes()`; the draft had base64url text (50 chars) and a `String` reader. Fixed in `optical/`, `QrReader`, `QrRenderer` (ISO-8859-1 byte-mode trick), data flow 3, the ML Kit/ZXing rows, the request flow and the boundaries table. 37 raw bytes fit QR version 3-M (42-byte capacity); base64url remains only for HTTP, `/debug/last-response` and the JSON vectors.
- Responder buttons: PROJECT.md lists six (Honest / Replay / Relay / Silent / **Impostor** / **Late**) and six verdicts; the draft had four. Fixed in the overview, `ResponderState`, `ResponderScreen`, the endpoint contract, the sequence and the build order.
- Physical-layer evidence (PROJECT.md active requirement + Key Decisions): round-trip gate 2.5 s → `FOE · late response`, hotspot RSSI gate → `FOE · signal mismatch`, two-consecutive-frame QR persistence → `FOE · optical mismatch`. Absent from the draft; added as `session/PhysicalGates.kt`, a networking-table row (RSSI API + permissions, MEDIUM) and in the verify order.
- Position gate: PROJECT.md = range ≤ **3 km** plus near-field **< 30 m** or ±20° cone, relay demo at **5 km**; the draft had 1 km / 50 m / 2 km (2 km would have passed a 3 km range gate). Fixed in `PositionCheck`, the sequence and the Relay button.
- Keys: the draft silently replaced PROJECT.md's `cmac = HMAC(dayKey, …)` with a derived `chKey` and dropped the ±1-day search. Reverted to PROJECT.md's derivation (`dayKey`, `droneKey`, cmac under `dayKey`, tag tried for today ±1); the real security observation (a captured drone holding `dayKey` leaks every drone's key for that day, contradicting "leaks only its own key") is kept as an explicit flag for the user's FOF-1 confirmation at H4–7.
- Toolchain: the AGP ↔ KGP 2.2.10 claim is now backed by the project's own `./gradlew buildEnvironment` (AGP 9.2.1 → kotlin-gradle-plugin 2.2.10); added the fact that the ClikaRT AAR's POM pulls `kotlin-stdlib:2.3.0` onto `:app`'s runtime classpath (`kotlin-stdlib:2.2.10 -> 2.3.0`), which the project already compiles with.
- Row stride: the androidx `ImageAnalysis` Javadoc promises only "a single-plane with the order of R, G, B, A"; it says nothing about row stride, so the row-by-row copy is now described as a defensive measure, not a documented guarantee.

**Verified as written (HIGH):**
- ClikaRT API: `Modelverse.load(context, license)` (`ModelverseAndroid.kt:18-30`), `Modelverse.isLoaded`, `AutoModelForObjectDetection` / `AutoModelForZeroShotObjectDetection.fromPretrained(source, VisionLoadOptions)` → `VisionModel.open` ("Blocks for the load; call it off the main thread"), `VisionLoadOptions(offline, device, cacheDir, …)`, `DetectOptions(maxResults = 100, scoreThreshold = 0.5f, labels)`, `ImageInput.Rgba8888(pixels, width, height)` RGBA byte order with alpha dropped, `ImageInput.Encoded(bytes)`, `Modelverse.pipeline("object-detection", model)` → `ObjectDetectionPipeline` (its constructor is `internal`; `invoke(image, options, candidateLabels)`), `Detection(label, labelId: Long, score, Box(left, top, right, bottom))` in input pixels score-descending, worker thread `"modelverse-vision"`, `close()` cancels and waits, `ClikaRtAndroid.bridgeLogs()` / `trimOnMemoryPressure(context)`, `CLIKA_RT_NUM_THREADS` read once at first compute, `LICENSE_FAILED` / `LICENSE_EXPIRED` / `LICENSE_SERVER_UNREACHABLE`, `XDG_CACHE_HOME` pin, `ANDROID_ID` binding, `System.loadLibrary` names. All cited line ranges land within ±3 lines.
- Samples: `RuntimeLane` text verbatim; `SampleRuntime.load`; `LicenseStore` (`clika_rt_license` in `filesDir`, `BuildConfig.CLIKA_RT_LICENSE` from `local.properties` `clika.runtimeLicenseFile`); `ServeService` scope on the lane, `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, `wifiAddress()`; `abiFilters += "arm64-v8a"`; `clikaMinSdk=28` ("the one the binding's artifact declares in its manifest").
- AAR: `jni/arm64-v8a/` sizes 99.68 / 139.27 / 19.06 / 14.32 / 11.91 MB as listed; arm64-v8a only.
- CLI 0.6.4: `--offline`, `--cache-dir`, `--revision` are global options and also accepted per command; `rt-detr` commands `detect, bench, serve`; `owlvit detect --labels`; `serve` defaults `--host 127.0.0.1`, `--port 8000`, `--read-timeout 30`, `--max-upload-mib 256`, `--device` accepted; `rt-detr detect --confidence` default 0.5, `owlvit detect --confidence` default 0.3, `--max-detections` 100; serve routes `POST /v1/detections`, `GET /health`, `/v1/health`, `/props`, `/dashboard`, `/v1/models` (`serve_api.h:27-31`); detection boxes are float pixels on the original image (`detection_engine.h:6-8`); open-vocabulary `label_id` = prompt-phrase index.
- Model cache: RT-DETR snapshot `5650961749fa93567c0d46fc7f43ea4f9e914107` with `config.json` (80 labels; `0 person, 4 aeroplane, 14 bird, 33 kite`), `preprocessor_config.json` 640×640, `model.safetensors` 80,904,640 B; OWLv2 cache 593 MB.
- Library versions: OkHttp 5.5.0 (latest stable), NanoHTTPD 2.3.1 (latest), ZXing core 3.5.4 (latest), kotlinx-serialization-json 1.11.0 (latest stable; 1.12.0-RC is pre-release), kotlinx-coroutines-android 1.11.0 (latest stable), CameraX 1.4.1 exists and stable is 1.6.2 (2026-08-26), ML Kit `barcode-scanning:17.3.0` bundled ("statically linked to your app at build time") vs `play-services-mlkit-barcode-scanning:18.3.1` unbundled ("Requests you make before the download has completed produce no results"), Compose BOM 2026.02.01 exists (latest 2026.09.00), FastAPI 0.143.0 (PyPI `info.version`, and `pip show` locally) and uvicorn 0.51.0 (PyPI release 2026-07-08, `pip show` locally; latest 0.54.0), AGP 9.2.0 compatibility table Gradle 9.4.1 / KGP 2.2.10 / JDK 17, Kotlin 2.2.10 on the build classpath.
- Android platform: cleartext disabled by default from API 28 + `base-config cleartextTrafficPermitted="true"` + `android:networkSecurityConfig`; CameraX `OUTPUT_IMAGE_FORMAT_RGBA_8888` single plane R,G,B,A; `setOutputImageRotationEnabled` → `getRotationDegrees()` 0, "10-15ms for 640x480 image on a mid-range device"; default analysis resolution 640×480; `STRATEGY_KEEP_ONLY_LATEST`; unclosed images stall/drop; ViewPort + UseCaseGroup crop-rect guarantee and `FILL_CENTER` default quoted correctly; ML Kit 2 px/module and 1280×720 recommendation; `Barcode.getRawBytes()` exists ("Returns raw bytes as it was encoded in the barcode").
- Byte arithmetic: challenge 1+16+4+2+8 = 31 B → 42 base64url chars; response 1+2+8+4+4+2+16 = 37 B → 50 chars; QR version 3 = 29 modules, version 4 = 33.

**Unverified / confidence:**
- Timings measured by the researcher (RT-DETR 0.37 s and OWLv2 ~5 s per 3840×2400 JPEG, ~0.3 s / ~1 s load) and the live `/v1/detections` error texts and `/props` shape — not re-run here (MEDIUM; plausible against the headers, which define the fields but not the exact messages).
- License credential length (297 chars) — not inspected on purpose (the credential is never printed); taken from PROJECT.md (MEDIUM).
- Whether `imageProxy.cropRect` is reported in rotated coordinates with output rotation on — device test at H1–4 (MEDIUM, as the draft says).
- On-device inference 300–700 ms, big-core thread count, airplane-mode licence behaviour, `adb push` into `Android/data`, hotspot client isolation, Windows hotspot upstream requirement, Android RSSI availability per OEM — all LOW until H0–1 tests; none verified here.
- AGP 9.2.0 release notes also list a fixed issue "Update Kotlin Gradle plugin dependency to 2.3.10" while the compatibility table says 2.2.10; the project's resolved classpath (2.2.10) is what matters and is verified, but a future AGP 9.x bump may move built-in Kotlin to 2.3.x — re-run `buildEnvironment` after any AGP upgrade (MEDIUM).
- JUnit Jupiter versions in the Sources list (5.14.4 / 6.1.3) are not used by the design (JUnit 4 is) and were not checked.

---
*Architecture research for: FriendOrFoe — two-phone offline IFF demo with on-device ClikaRT detection*
*Researched: 2026-10-10; adversarially verified and corrected 2026-10-10*
