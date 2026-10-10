# Phase 2: On-device detection - Research

**Researched:** 2026-10-10
**Domain:** CameraX 1.4.1 RGBA frames into the ClikaRT 0.6.4 Kotlin binding (RT-DETR v2 r18vd, CPU, offline) on a Samsung Galaxy A06; box geometry, challenge-window lock, status strip; the H4 `HttpDetector` fallback
**Confidence:** HIGH for the binding API, the skeleton state and the CameraX API (all read from local sources this session); MEDIUM for on-phone behaviour (the phone was **not attached** during research, so every device fact is a checkpoint, not a finding); LOW for latency numbers until measured at H2.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Camera pipeline (DET-01)
- **D-01:** One CameraX `Preview` + one `ImageAnalysis` bound in a single `UseCaseGroup` with the `PreviewView`'s `ViewPort`; `STRATEGY_KEEP_ONLY_LATEST`; `OUTPUT_IMAGE_FORMAT_RGBA_8888`; target 1280×720 (1920×1080 only if ms/frame stays under 1.5 s); back camera only; the observer Activity is locked to landscape so `rotationDegrees` is 0 (assert and log it). No `CameraController`, no `MlKitAnalyzer`.
- **D-02:** The analyzer copies `planes[0]` row by row into a tightly packed `Frame` (`rowStride` may exceed `width·4`), closes the `ImageProxy` in `finally`, and hands the frame to the runtime lane only when it is idle (busy flag; frames are dropped, never queued). Inference never runs on the camera executor or the main thread.

#### ClikaRT detector (DET-02)
- **D-03:** `ClikaRtDetector` loads once per process on `RuntimeLane`: resolve the credential first (`BuildConfig.CLIKA_RT_LICENSE`, filled from `local.properties` → `clika.runtimeLicenseFile`, an Offline bundle), then `Modelverse.load(context, license)`, `ClikaRtAndroid.bridgeLogs()`, `ClikaRtAndroid.trimOnMemoryPressure(context)`, then `AutoModelForObjectDetection.fromPretrained(modelDir, VisionLoadOptions(offline = true, device = "cpu"))` and `Modelverse.pipeline("object-detection", model)` (the `ObjectDetectionPipeline` constructor is internal). The model is a process singleton, never an Activity-scoped object.
- **D-04:** The model directory is `context.filesDir/models/rtdetr_v2_r18vd` (the three snapshot files are already there on phone A: config.json, preprocessor_config.json, model.safetensors, md5 7037c890…); fall back to `getExternalFilesDir(null)/models/rtdetr_v2_r18vd`; a missing directory is shown on screen as "model dir missing", never crashes.
- **D-05:** `DetectOptions(scoreThreshold = 0.25f)` with `labels` left empty (RT-DETR refuses labels); per-class filtering happens in `LabelMap` (person ≥ 0.5, drone proxies ≥ 0.28). At load, assert `capabilities.labels[4] == "aeroplane"` (VOC spelling in the cached config) and fail loudly otherwise.
- **D-06:** Threads: set `CLIKA_RT_NUM_THREADS` via `Os.setenv` before `Modelverse.load` to the big-core count of the Helio G85 (2× Cortex-A75), measure ms/frame, and try 4 and 8 once; keep the best. Warm up on one black 640×640 frame behind the "model loading… N s" state. `device = "cpu"` only; never probe Vulkan in the demo build (139 MB library).
- **D-07:** A "model loading…" state with elapsed seconds is visible from app start until the first detection; the ClikaRT exception code name (`LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE`, …) is shown on screen on failure, never the credential text.

#### Class map and thresholds (DET-03)
- **D-08:** `LabelMap` keys on `labelId`: 0 → PERSON; 4 (aeroplane), 14 (bird), 33 (kite) → DRONE; everything else dropped; `cell phone` (67) is never mapped. Overlapping boxes are de-duplicated by IoU, keeping the drone-class one.

#### Tracking, box lock, overlay geometry (DET-04, DET-06)
- **D-09:** `Tracker` (IoU matching, stable ids, already in the skeleton) assigns ids per frame; a drone box stays sticky for 5 s after the detector loses it; `BoxLock` freezes the tapped box (id + rect) for the 10 s challenge window regardless of what the detector does.
- **D-10:** `FrameTransform` maps analysis-frame pixels to `PreviewView` pixels for `FILL_CENTER` (same 16:9 aspect on both use cases); it is checked on an object at the frame edge, not only the centre. `TrackUi.box` is in preview pixels (`BoxF`), as the contract says; the overlay drawing itself stays with the UI stream (the skeleton's `OverlayPainter` is enough for this phase).

#### Detector selection and the H4 checkpoint (DET-05 interplay)
- **D-11:** `Detectors.create(config)` reads `Prefs.detectorConfig` (ClikaRt by default); `HttpDetector` is selectable only when `BuildConfig.ALLOW_HTTP_DETECTOR` is true (debug builds). If criterion 1 is not met on the phone by H4, the config value flips to `HttpDetector` against the laptop `serve --offline` and the phase continues; the five-step on-device checklist (license code name → model files/path → RGBA stride/channel dump vs the CLI → label ids → threshold) is re-run in the Phase 6 buffer.

#### Status strip (OBS-04)
- **D-12:** The strip shows detector name, ms/frame, fps, model-ready state and an OFFLINE badge when airplane mode is on (`Settings.Global.AIRPLANE_MODE_ON`) and no network is validated (`NET_CAPABILITY_VALIDATED` false); the gadget dot stays grey until Phase 5.

#### Demo target decision (PIT-04)
- **D-13:** At H3 test three props at the demo distance (0.5–0.7 m for the prop, 2–3 m for the person): a second phone or tablet with a full-screen quadcopter image at 100 % brightness, a printed A3/A4 quadcopter photo, a toy quadcopter. Record which one RT-DETR boxes as aeroplane/bird/kite at score ≥ 0.25 with the box surviving occlusion, plus the score and distance, in README. If none works: change the prop first; only then promote DET-09 (OWLv2, benched first) and cut EVL-01/02 first.

#### Device facts that constrain this phase
- **D-14:** Phone A is a Galaxy A06 (SM-A065F, Android 16, Helio G85: 2× A75 @2.0 GHz + 6× A55, Mali-G52, 3.7 GB RAM with ~800 MB free at rest, single USB-C port, USB host supported). Expect CPU inference closer to 0.5 fps than 1 fps before tuning; if ms/frame exceeds 1.5 s, drop the analysis resolution to 960×540 before touching anything else. Keep memory low: one RGBA copy per frame, no Vulkan library load.

#### Verification approach
- **D-15:** Pure logic (LabelMap, Tracker, FrameTransform, frame copy) is covered by `:app:testDebugUnitTest`. Detector parity is checked with a static-image harness: a bundled test JPEG through `ClikaRtDetector` must give the same boxes as `clikart-cli … detect` on the laptop. Everything that needs the phone (license on device, live boxes, airplane mode, edge-of-frame mapping, prop test) is a human checkpoint with the phone unlocked and in airplane mode.

### Claude's Discretion
- Exact thread count, analysis resolution and warm-up details within the bounds above; how the "model loading… N s" state is rendered; where the parity harness lives (a debug-only screen or an instrumented test); the sticky-box timing implementation; whether the external files dir fallback is kept.

### Informational (not tracked as plan items)
- **UI contract:** no UI-SPEC is produced for this phase by decision: the visual design of rings, tags and the strip belongs to the design track (issues #37–#39) and the UI stream (#13, #14); Phase 2 only has to deliver correct geometry and the state fields through the skeleton's `CameraScreen`.
- **Security:** the credential never appears in logs, screenshots or the repo (read from `BuildConfig`, masked everywhere); model files stay in app-private storage; no network access is added in this phase.

### Deferred Ideas (OUT OF SCOPE)
- Audio alert and bearing banner (DET-08) — Phase 5.
- OWLv2 zero-shot option (DET-09) — Phase 6, bench first; promoted only if PIT-04 fails.
- Eval set and `run_eval.py` (EVL-01/02) — Phase 6; the static-image parity harness from this phase is its seed.
- Gadget dot in the status strip and the whole signal path — Phases 4–5.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DET-01 | Live back-camera preview with one continuous analysis stream (Preview + ImageAnalysis, KEEP_ONLY_LATEST, 1280×720 or 1920×1080, orientation locked) | §CameraX 1.4.1 (every API verified in the cached sources), Pattern 2 (frame path), Code Example 4 (`CameraController`), Pitfalls C1–C4 |
| DET-02 | On-device RT-DETR v2 r18 via the ClikaRT AAR at ~1 fps, snapshot from device storage with `offline = true`, one runtime lane, visible "model loading…" state, no network | §ClikaRT 0.6.4 binding API (signatures with file:line), Pattern 1 (bring-up with license probe), Code Examples 1–2, §Model location, Pitfalls A1–A4 |
| DET-03 | `labelId` map 0 → person, 4/14/33 → drone, per-class thresholds, `cell phone` never mapped | `LabelMap` already implemented and tested (skeleton §S2); `id2label` re-read from the cached `config.json` this session; de-dup rule (D-08) → Pattern 4 |
| DET-04 | Boxes drawn in correct preview coordinates (rotation + FILL_CENTER), class colour/label/score, sitting on the object | `FrameTransform` already implemented and tested; §Mapping with the worked 1280×720 → 1600×720 example; `cropRect`/`ViewPort` facts from `ViewPort.java`; edge-of-frame human checkpoint |
| DET-06 | Tapped drone box locked (id + rect) for the 10 s window even if the detector loses it | `BoxLock` exists (pure data); Pattern 5 (lock in `IffCoordinator.tick/challenge`), Pitfall B3 (`retainAll` drops the state), Wave 0 test |
| OBS-04 | Status strip: detector name, ms/frame, fps, model-ready, OFFLINE badge (airplane on + no validated network); gadget dot grey | `StatusUi` fields already exist; `tick()` already fills ms/fps/modelReady; §OFFLINE badge (constants verified from `android.jar` 36), Pattern 6 (`offlineProbe` + additive `modelNote`) |
| PIT-04 | Demo target decided at H3/H4 by testing which prop RT-DETR boxes as aeroplane/bird/kite ≥ 0.25 | §Prop test protocol (H3), the raw-row debug log and PNG dump (Pattern 8) give the score per prop; decision recorded in README |
</phase_requirements>

## Summary

The Phase 1 skeleton is in better shape than the roadmap assumes in some places and worse in one: `LabelMap`, `Tracker`, `FrameTransform`, `BoxLock`, `IffCoordinator.tick`, `ObserverUiState`, `CameraScreen`/`OverlayPainter` and `RuntimeLane` are real and 50 JVM unit tests are green (`./gradlew :app:testDebugUnitTest --offline` run this session, 7 classes, 0 failures). The stubs that Phase 2 must replace are exactly four: `ClikaRtDetector.detect` (`detect/ClikaRtDetector.kt:32`), `CameraController.start/stop` (`camera/CameraController.kt:28-32`), `FofApplication.loadRuntime` (`app/FofApplication.kt:41-42`) and — **not planned anywhere but required for the H4 checkpoint to be real** — `HttpDetector.detect` (`detect/HttpDetector.kt:22-23`), which still throws `NotImplementedError` although DET-05 was booked to Phase 1. Three more things must change for the success criteria: `IffCoordinator.challenge` (`session/IffCoordinator.kt:95`, throws; its test asserts the throw), `Prefs.detectorConfig` (defaults to `Fake`, `app/Prefs.kt:15-18`) and `MainActivity` (shows `DemoCameraScreen`, no permission request, no camera, `MainActivity.kt:14-24`).

The ClikaRT binding API in the roadmap flags is confirmed line by line from `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources` with two nuances the plan must respect: (1) a license failure does **not** surface at `Modelverse.load`; it surfaces at the first compute (`ClikaRt.kt:56-62`), and when that first compute is a pipeline call the exception is `ModelverseException(message)` with an **empty** `codeName` (`Pipelines.kt:103-120, 260-273`) — so the D-07 code name needs a 3-line tensor-op license probe right after load, the way the official hello sample does it (`hello/MainActivity.kt:92-99`); (2) the official camera-to-`Rgba8888` pattern exists in `examples-0.6.4/vision_lens/.../CameraFrames.kt:63-84` and `LivePane.android.kt:30-38` (RGBA_8888 + KEEP_ONLY_LATEST + `ResolutionSelector`, `toBitmap()` in try/finally, `copyPixelsToBuffer`, `AndroidView { PreviewView }` under a `Canvas`), which is the reference to mirror; D-02's row-by-row copy produces byte-identical output and is unit-testable, so use it. CameraX stays at the 1.4.1 the skeleton pinned (`gradle/libs.versions.toml:12-13`): every API D-01 needs exists in 1.4.1 (verified in the cached sources jars), the `setOutputImageRotationEnabled` bug is fixed only in 1.7.0-alpha01 and is avoided by design, and 1.6.2 sits in the Gradle cache as a drop-in if ever needed.

**Primary recommendation:** Build in this order so H4 is reachable: (0) JVM tests first for the three pure pieces that do not exist yet (`RgbaPacker`, sticky `Tracker`, lock in `IffCoordinator`); (1) `FofApplication.loadRuntime` + license probe + `ClikaRtDetector.open()` with capabilities assert and warm-up, published as `modelNote`/`modelReady`; (2) `HttpDetector` for real (30 min, OkHttp multipart + `org.json`), so the fallback exists before the phone work starts; (3) `CameraController` + busy gate + `MainActivity` wiring + preview slot under the `Canvas`; (4) phone checkpoints in airplane mode: license code name, first boxes, PNG dump vs `clikart-cli detect`, edge-of-frame mapping, prop test. Never touch Vulkan, never `adb uninstall`, never bump Kotlin/AGP.

## Project Constraints (from CLAUDE.md / AGENTS.md)

| Directive | Source | Consequence for this phase |
|-----------|--------|----------------------------|
| App built with the ClikaRT Kotlin AAR 0.6.4 in-process; the laptop path only counts as the second device type | AGENTS.md Constraints | `ClikaRtDetector` is the product; `HttpDetector` is dev/contingency only (`BuildConfig.ALLOW_HTTP_DETECTOR`, `app/build.gradle.kts:41-50`) |
| Whole demo offline (airplane mode); offline license, models on device | AGENTS.md Constraints | `VisionLoadOptions(offline = true)`, no hub id as `source`, no network calls added |
| Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.2.10 built-in; do not downgrade; never `org.jetbrains.kotlin.android`; never Kotlin 2.3.x | AGENTS.md Compatibility + What NOT to Use | No plugin or version changes in `gradle/libs.versions.toml` for this phase |
| arm64-v8a, minSdk 28, ~300 MB APK over USB; no emulator | AGENTS.md Devices | Every device test is a human checkpoint on phone A; iterate logic through JVM tests |
| Keys and the license never committed; `*.license`, `local.properties` gitignored | AGENTS.md Security | License only via `BuildConfig.CLIKA_RT_LICENSE`; never logged, never in `modelNote` |
| What NOT to use: `ObjectDetectionPipeline(model)` ctor; any ClikaRT call on the main thread; hub download in the app; Vulkan as default; `camera-compose`; `setTargetResolution`; handing the CameraX plane buffer straight to `Rgba8888`; `useLegacyPackaging`; `python -I` | AGENTS.md table | All honoured by the patterns below |
| CameraX **1.6.2 (replace 1.4.1)** | AGENTS.md stack table | **Conflicts with the Phase 1 decision** recorded in `gradle/libs.versions.toml:12-13` ("keep CameraX 1.4.1 as declared by the template"). Resolution in §CameraX: stay on 1.4.1 for Phase 2 (no API difference for this pipeline, zero re-sync/reinstall cost); the hour plan is unchanged either way, so no user question is needed |
| GSD workflow enforcement: edits only through `/gsd-execute-phase` etc. | AGENTS.md | Planner output is executed by the GSD executor |
| Commit messages: project skill `.agents/skills/commit-message/SKILL.md` (one-line summary + `## Summary` + `## Changes`, no `feat:`/`fix:` prefixes); user memory: `area: what`, no attribution trailer, commit + `pull --rebase` + push after each major step | Skill + memory | Executor commits per plan; the research/plan docs follow the GSD `docs(02): …` form already used in this repo |
| Contracts (`Detector`, `Frame`, `Detection`, `BoxF`; `ObserverUiState`, `TrackUi`, `StatusUi`) change only via a `[shared]` PR | README §3 | The one additive change recommended here (`StatusUi.modelNote: String? = null`) must be a `[shared]`-titled commit pushed immediately |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Camera capture, RGBA conversion, backpressure | Device / CameraX (`camera/`) | — | CameraX converts YUV→RGBA natively (`ImageAnalysisAbstractAnalyzer.java:211-221`) and drops frames under KEEP_ONLY_LATEST; nothing above should re-implement it |
| Row-stride repack + rotation into `Frame` | Device / pure Kotlin (`camera/RgbaPacker`) | — | Pure arithmetic on a `ByteBuffer`; JVM-testable; must run on the camera thread (D-02) |
| Runtime bring-up, license probe, model open, warm-up, per-frame inference | Inference lane (`app/RuntimeLane`, `detect/ClikaRtDetector`, `app/FofApplication`) | — | ClikaRT blocks for seconds and must never touch the main thread (`VisionModel.kt:192-200`, `Pipelines.kt:5-17`) |
| Class map, thresholds, de-dup, tracking, lock, coordinate mapping | Session (`detect/LabelMap`, `track/`, `camera/FrameTransform`, `session/IffCoordinator`) | — | Pure Kotlin, unit-tested; `tick()` is the one place frames become `ObserverUiState` |
| Status strip facts (ms/frame, fps, model state, OFFLINE) | Session (`IffCoordinator` + `app/OfflineProbe`) | UI renders | `StatusUi` is the contract; the UI stream (issue #14) renders it |
| Preview surface + overlay geometry | UI (`ui/CameraScreen` + `AndroidView { PreviewView }`) | Session supplies preview-pixel boxes | Compose `Canvas` sized like the `PreviewView` (vision_lens `LivePane.android.kt:29-38`) |
| Model files | Device storage (`filesDir/models/rtdetr_v2_r18vd`) | adb + `run-as` | App-private, survives `adb install -r`, lost on uninstall |
| H4 fallback inference | Laptop (`clikart-cli serve --offline`, port 8000) via `detect/HttpDetector` | — | Debug builds only; same `Detector` contract |

## S. Current skeleton state (what to replace versus create)

All paths under `app/src/main/java/com/chloeyeo/friendorfoe/`. Build state verified this session: `./gradlew :app:testDebugUnitTest --offline` → exit 0, 50 tests / 0 failures (FrameTransformTest 6, LabelMapTest 6, TrackerTest 9, IffCoordinatorTest 3, DemoScenarioTest 4, GadgetLineCodecTest 13, ReChecksTest 9). [VERIFIED: local gradle run]

### S1. Stubs to replace (keep the signatures)

| File | Lines | Current | Action |
|------|-------|---------|--------|
| `detect/ClikaRtDetector.kt` | 29-37 | `class ClikaRtDetector(private val modelDir: String) : Detector`; `detect` throws `NotImplementedError("stream 1")` (:32); `close()` empty (:34-36); `name = "clikart:rtdetr_v2_r18vd"` (:30); KDoc 1-6 is the verified call sequence | Implement; add `fun open(): Unit` (not on the interface) that opens the model + asserts capabilities + warms up; `detect` lazily opens as a safety net; add `fun detectRaw(frame): List<io.clika.modelverse.Detection>` for the parity log |
| `detect/HttpDetector.kt` | 19-24 | `class HttpDetector(private val baseUrl: String) : Detector`; `detect` throws (:22-23); KDoc shape verified against the live server; **KDoc says port 8129 (:5, :17) but the project standard is 8000** (`laptop/README.md`, issue #8, ROADMAP Phase 1) | Implement (OkHttp 5 multipart `image` + `confidence`, `org.json` parse); fix the KDoc port to 8000 |
| `camera/CameraController.kt` | 26-33 | `class CameraController(context)`; `start(lifecycleOwner, previewView, onFrame: (Frame) -> Unit)` throws (:28-29); `stop()` throws (:32). KDoc :17-19 proposes running the analyzer **on the runtime lane and holding the proxy** — **D-02 overrides this** (dedicated camera executor, busy flag, drop) | Implement per D-01/D-02; new `camera/RgbaPacker.kt` (pure) |
| `app/FofApplication.kt` | 40-42 | `fun loadRuntime(license: String?): Nothing = throw …`; `onTrimMemory` TODO (:34-38); KDoc :10-23 verified sequence except the model dir (says external files dir; **D-04 says `filesDir` first**) | Change to `fun loadRuntime(license: String?)` (Unit, throws on failure); hold the process-singleton detector/coordinator here |
| `session/IffCoordinator.kt` | 95 | `fun challenge(boxId: Int): Nothing = throw NotImplementedError("integration lead")` | Phase 2 implements **step 1 only** of the KDoc (:19-21): lock + `IffState.Challenged`; steps 2-3 remain Phase 5 |
| `MainActivity.kt` | 14-24 | `setContent { DemoCameraScreen() }`; TODO :12 "CAMERA permission request, CameraController + IffCoordinator wiring" | Rewrite: permission → start runtime on the lane → bind camera → render `CameraScreen(coordinator.state)` with the preview slot |
| `app/Prefs.kt` | 15-18 | `detectorConfig` getter decodes the stored string, falls back to `Fake` | Default to `DetectorConfig.ClikaRt(File(context.filesDir, "models/rtdetr_v2_r18vd").path)` when nothing is stored (D-11) |

### S2. Real code to reuse (do not rewrite)

| File | Lines | What it already does |
|------|-------|----------------------|
| `detect/Detector.kt` | 8-21 | Contract: `detect(frame): List<Detection>` (frame pixels, score-descending, already filtered by `LabelMap`), `name`, `close()` |
| `detect/Frame.kt` | 13-28 | `Frame(width, height, rgba, rotationDegrees = 0, timestampNanos = 0)`; `init` requires `rgba.size == w*h*4` (:20-25) — the tight-packing guarantee the binding needs |
| `detect/BoxF.kt`, `Detection.kt`, `ObjClass.kt` | all | Pure boxes with `iou`/`contains`; `Detection(cls, score, box)`; `enum ObjClass { PERSON, DRONE }` |
| `detect/LabelMap.kt` | 15-52 | `classOf(labelId: Long)`, `threshold(cls)`, `accept(labelId, score, personThreshold = 0.5, droneThreshold = 0.28)`; ids 0 / {4,14,33}; tests in `LabelMapTest` (6) |
| `detect/DetectorConfig.kt`, `Detectors.kt` | all | `Fake` / `ClikaRt(modelDir)` / `Http(baseUrl)` with `encode/decode`; `Detectors.create` refuses `Http` unless `BuildConfig.ALLOW_HTTP_DETECTOR` (:14-19) — the demo-build guard already exists |
| `detect/FakeDetector.kt` | 8-36 | Scripted drone + person boxes; keeps the UI stream unblocked |
| `camera/FrameTransform.kt` | 12-51 | FILL_CENTER: `scale = max(viewW/frameW, viewH/frameH)` (:27), centred offsets (:30-33), `toPreview(box)` (:36), `toFrame(x, y)` (:44); `require(rotationDegrees == 0)` (:23); 6 tests incl. 1280×720 → 2400×1080 |
| `track/Tracker.kt` | 14-72 | Greedy IoU matching, stable ids, coasting with `maxMisses = 3` frames (:14, :55-57); 9 tests. Sticky-by-time (D-09) is **not** there yet |
| `track/BoxLock.kt` | 10-19 | `BoxLock(boxId, rect /* frame px */, untilMillis)`, `isActive(now)`, `remainingMillis(now)`, `WINDOW_MS = 10_000` |
| `session/IffCoordinator.kt` | 31-101 | `tick(frame)` (:60-86): detect → `tracker.update` → `FrameTransform` from `setPreviewSize` (:54-57) → `TrackUi` list → `StatusUi(msPerFrame = detect time, fps = 1000/interval, modelReady = true)`; `lock` field (:50-51); `log(text)` (:89-92); `iffById.keys.retainAll(live ids)` (:72) — see Pitfall B3 |
| `session/ObserverUiState.kt` | 15-73 | `StatusUi(detectorName, msPerFrame, fps, modelReady = false, offline = true, gadgetConnected = false, rssiDbm = null)` (:26-34); `IffState.Unchallenged / Challenged(until, windowMs) / Verdict(...)` (:50-73) |
| `app/RuntimeLane.kt` | 14-19 | `executor` (single thread "clika-runtime-lane") + `dispatcher` — identical to the official `shared/RuntimeLane.kt:13-16` |
| `ui/CameraScreen.kt` | 52-93 | Renders one `ObserverUiState`: `Canvas` with tap hit-test on DRONE tracks (:72-84), `StatusStrip` chips (:96-113), `SignalPanel`; `DemoCameraScreen` (:144-159). KDoc :48-50 says the preview `AndroidView { PreviewView }` goes underneath the Canvas and reports its size to `IffCoordinator.setPreviewSize` |
| `ui/OverlayPainter.kt` | 24-104 | Ring colours/tags per state; countdown arc for `Challenged` (:75-88); "enough for this phase" (D-10) |

### S3. Gradle and manifest facts (`app/build.gradle.kts`, `AndroidManifest.xml`, `gradle/libs.versions.toml`)

| Fact | Where | Value |
|------|-------|-------|
| License embedding | `app/build.gradle.kts:12-19, 39` | `BuildConfig.CLIKA_RT_LICENSE` = first non-blank line of the file named by `local.properties` → `clika.runtimeLicenseFile`; `""` when absent. The actual file is **one line, 3258 bytes, starts with `CLIKA1`** (counted this session, content never read), so the whole bundle rides BuildConfig |
| Demo-build guard | `:41-50` | `ALLOW_HTTP_DETECTOR` false in `defaultConfig`, true in `debug` |
| ABI / SDK | `:27-37` | `minSdk 28`, `targetSdk 36`, `compileSdk 36.1`, `abiFilters arm64-v8a`, `buildConfig = true`, Java 11 |
| Dependencies declared | `:67-85` | `clika-runtime 0.6.4`, CameraX `camera-camera2/lifecycle/view` **1.4.1**, `kotlinx-coroutines-android 1.10.2`, `okhttp-jvm 5.5.0` (not the KMP coordinate: it would demand compileSdk 37, TOML :40-42), `usb-serial-for-android 3.9.0`, `core-ktx 1.10.1`, `activity-compose 1.8.0`, `lifecycle-runtime-ktx 2.6.1`, Compose BOM 2026.02.01; tests: `junit 4.13.2`, real `org.json 20250517` (TOML :44). **Not declared:** `lifecycle-runtime-compose` → use `collectAsState()`, not `collectAsStateWithLifecycle()` |
| Permissions | manifest `:7-10` | INTERNET, CAMERA, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE (enough for the OFFLINE probe) |
| Activity | `:33-37` | `.MainActivity`, `android:screenOrientation="landscape"`, `configChanges="orientation|screenSize|keyboardHidden"` → no recreation on rotation (D-01) |
| Application | `:22-32` | `.app.FofApplication`, `usesCleartextTraffic="true"` (HttpDetector over plain http works) |
| Assets | — | No `assets/` dir exists; the parity image must be added (`app/src/main/assets/parity/`) |

## Standard Stack

No new packages. Everything this phase needs is already declared and resolved (the green offline test run proves the Gradle cache is complete).

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.clika:clika-runtime` | 0.6.4 (AAR) | RT-DETR v2 in-process | SC1 requirement; the API below is from its sources jar [VERIFIED: `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources`] |
| `androidx.camera:camera-camera2/lifecycle/view` | 1.4.1 (declared; 1.6.2 also cached) | Preview + ImageAnalysis RGBA + `PreviewView` | Every API in D-01 exists in 1.4.1 [VERIFIED: `camera-core-1.4.1-sources.jar`, `camera-view-1.4.1-sources.jar`, `camera-lifecycle-1.4.1-sources.jar`] |
| `kotlinx-coroutines-android` | 1.10.2 | `RuntimeLane.dispatcher` | The version the official samples pair with the AAR (`vision_lens/src/androidApp/build.gradle.kts:67`) [VERIFIED] |
| `com.squareup.okhttp3:okhttp-jvm` | 5.5.0 | `HttpDetector` multipart POST | Declared in Phase 1; `MultipartBody`/`RequestBody` extension API [ASSUMED: OkHttp 4/5 Kotlin API from training, low risk] |
| `org.json` (platform) + `org.json:json 20250517` (tests) | — | Parse `/v1/detections` | Platform `org.json` is an empty stub in JVM tests; the real jar is already `testImplementation` (TOML :44) [VERIFIED] |
| JUnit 4 | 4.13.2 | `:app:testDebugUnitTest` | Already the project's test framework (50 green tests) [VERIFIED] |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| CameraX 1.4.1 (keep) | 1.6.2 (AGENTS.md table) | Same API for this pipeline; a bump costs a Gradle re-sync and a ~300 MB reinstall for nothing; neither contains the `setOutputImageRotationEnabled` fix (only 1.7.0-alpha01) [CITED: developer.android.com/jetpack/androidx/releases/camera] |
| Row-by-row `planes[0]` copy (D-02) | `proxy.toBitmap()` + `copyPixelsToBuffer` (official `CameraFrames.kt:74-77`) | Same bytes; `toBitmap()` is stride-aware (`ImageProxy.java:151`) but does two copies and is not JVM-testable; D-02 is locked |
| Blocking `pipeline(image, options)` | `model.detect(image, options, listener)` + latch | Listener gives `VisionReport.latencyMs/accelerator` (`Types.kt:898-905`); pipeline is simpler; use the pipeline, measure wall-clock |
| Polling the OFFLINE probe per tick | `registerDefaultNetworkCallback` + airplane `ContentObserver` | Callbacks are more code and lifecycle; 1 Hz polling from the lane is enough for a status chip |

## Package Legitimacy Audit

> This phase installs **no** external packages: all dependencies were added in Phase 1 and resolve from the Gradle cache (offline test run green). slopcheck was therefore not run; there is nothing to audit.

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## R. ClikaRT 0.6.4 binding API (verified from the local sources)

Root: `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/` (`commonMain/io/clika/...`, `androidMain/io/clika/...`). All [VERIFIED: file:line].

| Member | Signature / fact | Source |
|--------|------------------|--------|
| Android entry | `fun Modelverse.load(context: Context, license: String? = null)` — extension in package `io.clika.modelverse` (import `io.clika.modelverse.load`); `if (Modelverse.isLoaded) return` (idempotent: a wrong first load sticks until process death); wraps `UnsatisfiedLinkError` into `ModelverseException` | `androidMain/io/clika/modelverse/ModelverseAndroid.kt:17-30` |
| What it does | `ClikaRtAndroid.load(context, license)`: `pinCacheRoot` (`XDG_CACHE_HOME` = `cacheDir`), `placeLicense` (`Os.setenv("CLIKA_RT_LICENSE", license, true)`; `null` keeps the environment; text never logged), `ClikaRt.load()`, `attachApp` (hands the app Context; the runtime reads `Settings.Secure.ANDROID_ID` for license binding) | `androidMain/io/clika/runtime/ClikaRtAndroid.kt:80-87, 234-252, 212-225` |
| Threads | "`CLIKA_RT_NUM_THREADS` is read once at the first compute: an app that sets it does so in its own process (`Os.setenv`) before `load`. A phone with a few large cores and several small ones runs best at the large cores' count; measure on the device." | `ClikaRtAndroid.kt:41-45` |
| Logs | `ClikaRtAndroid.bridgeLogs(tag: String = "ClikaRT", level: LogLevel = LogLevel.INFO)` — call after load; `adb logcat -s ClikaRT` | `ClikaRtAndroid.kt:171-178` |
| Memory | `ClikaRtAndroid.trimOnMemoryPressure(context)` — registers ONE `ComponentCallbacks2` on the application; second call no-op; call after load | `ClikaRtAndroid.kt:100-104` |
| License codes | "Without a valid credential the runtime's calls fail (`LICENSE_FAILED`, or `LICENSE_EXPIRED` …). While the license server cannot be reached they fail with `LICENSE_SERVER_UNREACHABLE`" — **at the first operator/compute, not at load**: "Every call that runs an operator needs the license credential … the first operator throws a `ClikaRtException` whose `codeName` is `LICENSE_FAILED`" | `ClikaRtAndroid.kt:72-76`; `commonMain/io/clika/runtime/ClikaRt.kt:56-62` |
| Exceptions | `class ClikaRtException(val status: Status, val codeName: String, message)`; `class ModelverseException(message, val codeName: String = "", cause)` — "empty for a failure of the binding itself"; `BINDING_VERSION_MISMATCH` from `ClikaRt.load()`; `Modelverse.requireLoaded()` throws `ModelverseException("Modelverse.load has not succeeded in this process")` | `ClikaRt.kt:31-39, 95-97`; `modelverse/ModelverseException.kt:14-18`; `modelverse/Modelverse.kt:317-319` |
| License probe (hello sample) | `Tensors.of(floatArrayOf(1f,2f,3f), longArrayOf(3)).use { a -> Tensors.of(floatArrayOf(10f,20f,30f), longArrayOf(3)).use { b -> Ops.add(a, b).use { it.summary() } } }` → throws `ClikaRtException` with `codeName` on a license problem; `Tensor : AutoCloseable` | `examples-0.6.4/android/hello/.../MainActivity.kt:92-99`; `runtime/Tensors.kt:67, 150, 219`; `runtime/Ops.kt:646` |
| Open a detector | `AutoModelForObjectDetection.fromPretrained(source: String, options: VisionLoadOptions = VisionLoadOptions()): VisionModel` = `VisionModel.open(source, options)` — "Blocks for the load; call it off the main thread"; throws `ModelverseException` when no family claims the files | `modelverse/Auto.kt:283-287`; `modelverse/VisionModel.kt:192-218` |
| Load options | `data class VisionLoadOptions(modelArgs: Map<String,String> = emptyMap(), revision = "main", cacheDir: String? = null, token: String? = null, offline: Boolean = false, device: String? = null, weights: String? = null, dtype: String? = null)`; "A local source ignores the hub knobs"; device spellings `"cpu"`, `"vulkan:0"` | `modelverse/Types.kt:831-845, 5-14`; `runtime/Devices.kt:184-195` |
| Capabilities | `model.capabilities.labels: List<String>` ("the detector's own label table, in id order; empty for an open-vocabulary detector"), `.openVocabulary: Boolean`, `.visionTasks: List<String>` with `VisionTask.DETECT = "detect"` | `Types.kt:240-253, 813-829` |
| Pipeline | `Modelverse.pipeline(task: String, model: PreTrainedModel): Pipeline` → cast `as ObjectDetectionPipeline` (constructor `internal`); `Task.OBJECT_DETECTION = "object-detection"` | `modelverse/Modelverse.kt:197-202`; `modelverse/Pipelines.kt:297-300`; `Auto.kt:20` |
| Invoke | `operator fun invoke(image: ImageInput, options: DetectOptions = DetectOptions(), candidateLabels: List<String> = emptyList()): List<Detection>` — blocks the caller on a `CountDownLatch`; **a failure throws `ModelverseException(message)` with empty `codeName`** | `Pipelines.kt:301-317, 103-120, 260-273` |
| Detect options | `data class DetectOptions(maxResults: Int = 100, scoreThreshold: Float = 0.5f, labels: List<String> = emptyList())` — labels "refused on a detector with its own label table" | `Types.kt:851-869` |
| Inputs | `ImageInput.Rgba8888(pixels: ByteArray, width: Int, height: Int)` — "the pixels of an `ARGB_8888` bitmap as `Bitmap.copyPixelsToBuffer` writes them: four bytes per pixel in the order red, green, blue, alpha, row by row"; `ImageInput.Encoded(bytes: ByteArray)` PNG/JPEG; the native call has **no stride parameter** | `Types.kt:800-811`; `VisionModel.kt:235-246` |
| Outputs | `data class Detection(label: String, labelId: Long, score: Float, box: Box)`; `data class Box(left, top, right, bottom)` in input pixels, top-left origin; score-descending | `Types.kt:877-885`; `VisionModel.kt:36-42` |
| Direct door | `VisionModel.detect(image, options, listener: VisionListener): VisionHandle`; callbacks on the model's own worker thread `modelverse-vision`, one request at a time; `VisionReport(latencyMs, accelerator, canceled)` | `VisionModel.kt:43-52, 26-32, 183`; `modelverse/Listeners.kt:115-144`; `Types.kt:898-905` |
| Close | `VisionModel.close()` cancels in-flight, waits, idempotent; `Pipeline.close()` closes the model | `VisionModel.kt:85-103`; `Pipelines.kt:26` |
| Backends | `Backends.isAvailable(api)` **brings the backend up** (maps the 139 MB Vulkan lib); `Backends.isLoaded(api)` never does | `Devices.kt:364-378` (per PITFALLS #5) |

**Model facts (re-read this session):** `C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/` holds exactly `config.json` (5437 B), `model.safetensors` (80,904,640 B), `preprocessor_config.json` (444 B). `config.json`: `model_type = rt_detr_v2`, `architectures = [RtDetrV2ForObjectDetection]`, 80 labels, `id2label` `0 person`, `3 motorbike`, `4 aeroplane`, `14 bird`, `33 kite`, `67 cell phone`. `preprocessor_config.json`: resize to 640×640, `do_rescale` 1/255, `do_normalize false`, `do_pad false` → the binding does all preprocessing; hand it the raw camera RGBA. [VERIFIED: local files]

**Official patterns to mirror** (`C:/Users/C/clika/reference/examples-0.6.4/`): `vision_lens/src/shared/src/androidMain/.../CameraFrames.kt:63-84` (ImageAnalysis RGBA_8888 + KEEP_ONLY_LATEST + `ResolutionSelector`, analyzer on its own single thread, `toBitmap()` in `try { } finally { proxy.close() }`, rotate by `Matrix` when `rotationDegrees != 0`, `copyPixelsToBuffer` into `ByteArray(w*h*4)`); `LivePane.android.kt:29-38` (`Box { AndroidView(factory = { PreviewView(ctx).apply { scaleType = … } }, Modifier.fillMaxSize()); Canvas(Modifier.fillMaxSize()) { … } }`); `ModelverseEngine.kt:32-40, 85-91` (reuse one pipeline; `candidates = emptyList()` for a closed-set model; `guarded { }` maps both `ClikaRtException` and `ModelverseException` to `codeName`); `vision_lens/.../MainActivity.kt:24-34, 54-56, 63-81` (license attached in `Application.onCreate`, `trimOnMemoryPressure` in `onTrimMemory`, `RequestPermission` contract, **intent extras drive a self-test run from `adb shell am start`** — the model for the parity harness). [VERIFIED]

## C. CameraX 1.4.1 facts for this exact pipeline

Sources: `camera-core-1.4.1-sources.jar`, `camera-view-1.4.1-sources.jar`, `camera-lifecycle-1.4.1-sources.jar` in `C:/Users/C/.gradle/caches/modules-2/files-2.1/androidx.camera/`. All [VERIFIED] unless tagged.

| Need (D-01/D-02/D-10) | API in 1.4.1 | Source |
|-----------------------|--------------|--------|
| Keep only latest | `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST = 0` (default), `Builder.setBackpressureStrategy` | `ImageAnalysis.java:140, 1138` |
| RGBA output | `OUTPUT_IMAGE_FORMAT_RGBA_8888 = 2`: "a single-plane with the order of R, G, B, A in increasing byte index in the `ByteBuffer`" from `planes[0].buffer`; `Builder.setOutputImageFormat` | `ImageAnalysis.java:175-184, 1190` |
| Resolution | `Builder.setResolutionSelector(ResolutionSelector)`; `AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY`; `ResolutionStrategy(Size, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER = 1 / FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER = 3)` | `ImageAnalysis.java:1524`; `resolutionselector/AspectRatioStrategy.java:96`; `ResolutionStrategy.java:61, 72` |
| Group + viewport | `UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(analysis).build()`; `ProcessCameraProvider.bindToLifecycle(owner, selector, useCaseGroup)` | `UseCaseGroup.java:109, 169`; `ProcessCameraProvider.kt:199, 239` |
| ViewPort from the view | `PreviewView.getViewPort(): ViewPort?` — "null if the view is not currently attached or the view's width/height is zero" (built from the view's width/height, scale type and display rotation) | `PreviewView.java:587-607, 650-660` |
| Scale type | `PreviewView.ScaleType.FILL_CENTER` is the default; `setScaleType` | `PreviewView.java:488, 862` |
| Frame metadata | `ImageProxy.getCropRect()`, `ImageInfo.getRotationDegrees()`, `ImageProxy.toBitmap()` (RGBA_8888 supported, stride-aware) | `ImageProxy.java:49, 151`; `ImageInfo.java:69` |
| Not used | `Builder.setOutputImageRotationEnabled` (`@RequiresApi(23)`, +10-15 ms/frame; buggy for initial rotation 0 until 1.7.0-alpha01); `PreviewView.getOutputTransform()`/`CoordinateTransform` (`@TransformExperimental`) | `ImageAnalysis.java:1195-1225`; `PreviewView.java:1000-1002`; `view/transform/CoordinateTransform.java:78, 139`; [CITED: CameraX release notes, 1.7.0-alpha01: "Fixed a bug in `ImageAnalysis` where images were not correctly rotated when output image rotation is enabled and the initial relative rotation is 0 degrees"] |

**KEEP_ONLY_LATEST semantics (matters for the busy gate).** While the app holds an unclosed `ImageProxy`, the newest incoming frame is cached (the previously cached one is closed) and re-posted when the app closes its frame (`ImageAnalysisNonBlockingAnalyzer.java:73-108, 127-140`). The YUV→RGBA conversion runs at post time (`ImageAnalysisAbstractAnalyzer.java:211-221`). Consequence: D-02's fast copy-and-close means CameraX converts every camera frame (~30/s at 720p) even though the lane only consumes ~1/s; that is a few per cent of one core and is acceptable, but if ms/frame suffers, hold the proxy for ~200 ms when busy before closing (no frame is queued by that; it just skips conversions). [VERIFIED]

**Rotation with a landscape-locked Activity.** `rotationDegrees` is "the degrees by which the image needs to be rotated clockwise to match `ImageAnalysis`'s target rotation", the default target rotation is the display rotation at bind time, and a locked `screenOrientation` keeps the Activity alive across physical rotation [CITED: developer.android.com/media/camera/camerax/orientation-rotation]. With `android:screenOrientation="landscape"` the display rotation is constant, so `rotationDegrees` is a per-device constant: 0 when the back sensor is mounted at 90° (the common case), 180 when it is mounted at 270° [ASSUMED for the A06: sensor orientation unknown until the first run]. Do **not** add an `OrientationEventListener` (that follows the physical orientation, which the overlay must not do). Log the value once and handle 180 (and 90/270 for completeness) in the pure packer; `FrameTransform` then stays rotation-0 only.

**Mapping (D-10).** `ViewPort` makes CameraX "calculate output crop rects … mapped to the same camera sensor area" and "for `ImageAnalysis` … the output crop rect is `ImageProxy.getCropRect()`" (`ViewPort.java:35-48`) — the analysis **buffer is not cropped**, only the metadata is. When both use cases request 16:9 and the `PreviewView` is wider (A06 screen 1600×720, 20:9 [ASSUMED]), the viewport yields a centred 1280×576 `cropRect` on the 1280×720 frame, and FILL_CENTER of that crop into 1600×720 equals FILL_CENTER of the full frame into 1600×720: `scale = max(1600/1280, 720/720) = 1.25`, `offsetY = (720 − 900)/2 = −90` — exactly what `FrameTransform` computes (`FrameTransform.kt:27-33`). So the existing transform is right as long as the delivered analysis size is 16:9; log `image.width×height` and `cropRect` once at the first frame and assert the aspect; only if the device delivers a 4:3 analysis stream must the transform switch to the `cropRect`-based form (the official `getCorrectionMatrix` recipe: four corners of `cropRect` → view corners, shifted by `rotationDegrees/90` [CITED: developer.android.com/media/camera/camerax/transform-output]). Edge check on the phone: a static object at the left/right frame edge must sit under its ring; wrong at the edges only = scale, shifted everywhere = offset.

## O. OFFLINE badge: airplane mode + validated network (API 28+ path)

All constants verified with `javap -constants` against `C:/Users/C/AppData/Local/Android/Sdk/platforms/android-36/android.jar` and `data/api-versions.xml`: [VERIFIED]

- `Settings.Global.AIRPLANE_MODE_ON = "airplane_mode_on"` (`Settings.Global` since API 17): `Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0`.
- `NetworkCapabilities.NET_CAPABILITY_VALIDATED = 16` (API 23), `NET_CAPABILITY_INTERNET = 12`; `ConnectivityManager.getActiveNetwork()` (API 23), `getNetworkCapabilities(network)`; needs `ACCESS_NETWORK_STATE` (declared, manifest :9). `registerDefaultNetworkCallback(cb)` (API 24) exists but polling is enough.
- `Intent.ACTION_AIRPLANE_MODE_CHANGED = "android.intent.action.AIRPLANE_MODE"` exists; not needed with polling.
- Rule (D-12): `offline = airplaneOn && !validated`. In the demo, Wi-Fi may be re-enabled for the laptop LAN (no internet) → still not validated → badge stays OFFLINE, which is the intended semantics. The strip's `"online"` text for `offline = false` is the UI stream's wording.

Recommended shape: `IffCoordinator(..., offlineProbe: () -> Boolean = { true })` evaluated inside `tick()` (1 Hz, on the lane; `Settings.Global.getInt` and `getNetworkCapabilities` are cheap binder calls) and once at start; `app/OfflineProbe(context)` implements it; a pure `OfflineRule.isOffline(airplaneOn, validated)` carries the unit test.

## M. Model location on phone A and the adb commands

- Path (D-04): `context.filesDir/models/rtdetr_v2_r18vd` = `/data/user/0/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd/` (app-private, readable only by the app or `run-as`). A staged copy lives at `/data/local/tmp/models/rtdetr_v2_r18vd/` (pushed in Phase 1; `adb push` straight into `/sdcard/Android/data/<pkg>/files` hung on this phone). `model.safetensors` md5 `7037c890ae678aaba18f3555b3acf36d`. [VERIFIED: STATE.md "model copied into the app"; memory `project-phone-a-device`] — **the phone was not attached during this research, so re-verify first thing** (`adb devices` was empty).
- Git Bash mangles device paths (`/data/local/tmp` → `C:/Program Files/Git/...`): prefix every adb call with `MSYS_NO_PATHCONV=1`.

```bash
# adb = C:/Users/C/AppData/Local/Android/Sdk/platform-tools/adb.exe (1.0.41)
export MSYS_NO_PATHCONV=1
adb devices -l                                                     # R83YB0LR9GE must be listed
adb shell run-as com.chloeyeo.friendorfoe ls -l files/models/rtdetr_v2_r18vd        # 5437 / 80904640 / 444
adb shell run-as com.chloeyeo.friendorfoe md5sum files/models/rtdetr_v2_r18vd/model.safetensors   # 7037c890…

# Only if the app data was wiped (never `adb uninstall`; `installDebug` / `adb install -r` keep files/):
adb push "C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/." /data/local/tmp/models/rtdetr_v2_r18vd/
adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models && cp -r /data/local/tmp/models/rtdetr_v2_r18vd files/models/ && ls -l files/models/rtdetr_v2_r18vd'

# install / logs
./gradlew :app:installDebug          # ~300 MB over USB, 1-2 min; keeps app data
adb logcat -s ClikaRT FoF            # runtime channel (after bridgeLogs) + the app tag

# pull a dumped frame (binary-safe through run-as)
adb exec-out run-as com.chloeyeo.friendorfoe cat files/dumps/frame.png > frame.png
```

## Architecture Patterns

### System Architecture Diagram

```
                 phone A (landscape-locked Activity, airplane mode)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ CameraX back camera ── Preview ─────────────────────────► PreviewView         │
 │   (one UseCaseGroup, ViewPort from PreviewView, 16:9 on both use cases)       │
 │                     └─ ImageAnalysis RGBA_8888 1280×720 KEEP_ONLY_LATEST       │
 │                          │ ~30 fps, analyzer on "camera-analysis" thread      │
 │                          ▼                                                     │
 │             busy? ──yes──► proxy.close()  (frame dropped, never queued)        │
 │               │ no                                                             │
 │               ▼                                                                │
 │        RgbaPacker: planes[0] rows → tight ByteArray (+rotate if 180) → Frame   │
 │        proxy.close() in finally; busy = true; RuntimeLane.executor.execute    │
 │                          │                                                     │
 │                          ▼  "clika-runtime-lane" (single thread)              │
 │   IffCoordinator.tick(frame)                                                   │
 │     ├─ Detector.detect(frame)                                                  │
 │     │     ClikaRtDetector: pipeline(Rgba8888, DetectOptions(0.25)) → raw rows  │
 │     │       → LabelMap.accept (0→PERSON ≥.5, 4/14/33→DRONE ≥.28) → Dedup(IoU)  │
 │     │     HttpDetector (debug only): JPEG → POST laptop:8000/v1/detections      │
 │     ├─ Tracker.update(now)  (ids; drone sticky 5 s)                            │
 │     ├─ BoxLock: tapped box kept as a Challenged TrackUi for 10 s               │
 │     ├─ FrameTransform(frame→preview, FILL_CENTER)                              │
 │     ├─ offlineProbe() → StatusUi.offline; ms/frame, fps, modelReady, modelNote │
 │     └─ _state.update(...)  → StateFlow<ObserverUiState>; busy = false          │
 │                          │                                                     │
 │                          ▼  main thread (collectAsState)                       │
 │   CameraScreen: AndroidView{PreviewView} under Canvas(rings/tags) + StatusStrip│
 │     tap on a DRONE box → coordinator.challenge(id) → lock (Phase 5 adds signal)│
 └──────────────────────────────────────────────────────────────────────────────┘
   Process start (FofApplication / MainActivity, on the lane):
   license (BuildConfig) → Os.setenv(CLIKA_RT_NUM_THREADS) → Modelverse.load(ctx, license)
   → bridgeLogs → trimOnMemoryPressure → tensor-op license probe (codeName)
   → model dir check → fromPretrained(dir, offline, cpu) → assert labels[4]=="aeroplane"
   → pipeline("object-detection", model) → warm-up black 640×640 → modelReady
```

### Recommended Project Structure (files this phase touches)

```
app/src/main/java/com/chloeyeo/friendorfoe/
├── app/
│   ├── FofApplication.kt      # REPLACE loadRuntime; ADD process-singleton detector + coordinator
│   ├── OfflineProbe.kt        # NEW: airplane + validated-network → Boolean
│   ├── Prefs.kt               # EDIT: default detectorConfig = ClikaRt(filesDir/models/rtdetr_v2_r18vd)
│   └── RuntimeLane.kt         # keep
├── camera/
│   ├── CameraController.kt    # REPLACE: UseCaseGroup + ViewPort, RGBA analysis, busy gate
│   ├── RgbaPacker.kt          # NEW (pure Kotlin): stride repack + rotation → Frame
│   └── FrameTransform.kt      # keep (optionally log/assert the 16:9 aspect at first frame)
├── detect/
│   ├── ClikaRtDetector.kt     # REPLACE: open() / detect() / detectRaw() / close()
│   ├── HttpDetector.kt        # REPLACE: OkHttp multipart + DetectionsJson parser (pure, tested)
│   ├── Dedup.kt               # NEW (pure): IoU de-dup keeping the drone-class box (D-08)
│   └── LabelMap.kt            # keep
├── session/
│   ├── IffCoordinator.kt      # EDIT: lock in tick/challenge, offlineProbe, setStatus/modelNote
│   └── ObserverUiState.kt     # EDIT [shared]: StatusUi.modelNote: String? = null (additive)
├── track/Tracker.kt           # EDIT: wall-clock sticky per class (keep update(detections) overload)
├── ui/CameraScreen.kt         # EDIT (minimal): preview slot under the Canvas; modelNote chip
└── MainActivity.kt            # REPLACE: permission → runtime → camera → CameraScreen(state)
app/src/main/assets/parity/scene.png   # NEW: copy of examples-0.6.4/vision_lens/samples/scene.png (+ a prop photo later)
app/src/test/java/com/chloeyeo/friendorfoe/
├── camera/RgbaPackerTest.kt, detect/DedupTest.kt, detect/DetectionsJsonTest.kt, app/OfflineRuleTest.kt   # NEW
├── track/TrackerTest.kt (sticky cases), session/IffCoordinatorTest.kt (lock cases; replace the throw test)
```

### Pattern 1: Bring-up on the lane with a license probe (D-03, D-06, D-07)

**What:** One function, once per process, on `RuntimeLane.executor`, in this order: resolve credential → `Os.setenv("CLIKA_RT_NUM_THREADS", n, true)` → `Modelverse.load(appContext, license)` → `bridgeLogs()` → `trimOnMemoryPressure()` → **tensor-op probe** (surfaces `LICENSE_*` as a clean `ClikaRtException.codeName`; `ClikaRt.kt:56-62`, hello sample) → model dir check (three files, byte sizes) → `fromPretrained(dir, VisionLoadOptions(offline = true, device = "cpu"))` → assert `labels.size == 80 && labels[4] == "aeroplane" && !openVocabulary && VisionTask.DETECT in visionTasks` → `Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline` → warm-up `pipeline(Rgba8888(ByteArray(640*640*4), 640, 640), DetectOptions(scoreThreshold = 0.25f))` → `modelReady = true`, `modelNote = "load 3.2 s · warm 1.4 s"`. Each failure → `modelNote = codeName.ifEmpty { message.take(80) }` (never the credential; the runtime never echoes it), `modelReady = false`, no crash.
**When to use:** always for `DetectorConfig.ClikaRt`. For `Fake`/`Http` configs **do not load the runtime at all** (no seat consumed, no 284 MB of `.so` mapped; teammates' builds have an empty `BuildConfig.CLIKA_RT_LICENSE`).
**Threads:** the Helio G85 has 2 big cores → `n = 2` default; the 4/8 experiment needs a process restart because the variable is read once at the first compute: `adb shell am force-stop com.chloeyeo.friendorfoe && adb shell am start -n com.chloeyeo.friendorfoe/.MainActivity --ei clika_threads 4` (intent extra read before `loadRuntime`, the way vision_lens reads its self-test extras).

### Pattern 2: Frame path with a busy gate (D-01, D-02)

**What:** `CameraController.start` binds on the main thread once `PreviewView` is laid out (`previewView.doOnLayout { … }` because `getViewPort()` is null before layout); `analysis.setAnalyzer(cameraExecutor) { proxy -> try { if (!busy.compareAndSet(false, true)) return@setAnalyzer; val frame = RgbaPacker.pack(proxy); RuntimeLane.executor.execute { try { onFrame(frame) } finally { busy.set(false) } } } finally { proxy.close() } }` — the copy (~3.7 MB at 720p) happens on the camera thread, the inference on the lane, nothing queues (at most one frame in flight). First frame: log `width×height`, `cropRect`, `rotationDegrees`, `planes[0].rowStride/pixelStride` once (assert `pixelStride == 4`, `rowStride >= width*4`).
**Why not the skeleton KDoc's "analyzer on the lane":** D-02 forbids inference on the camera executor; sharing the lane would also block the camera callback for the whole inference.

### Pattern 3: Process-singleton detector and coordinator (D-03)

**What:** `FofApplication` owns `val coordinator: IffCoordinator` (created lazily from `Detectors.create(prefs.detectorConfig)` + `FakeSignalSource()`); `MainActivity` only requests CAMERA, starts the runtime load (if not started), binds the camera to its lifecycle, renders `coordinator.state`. `onDestroy` → `cameraController.stop()` only; the model is never closed during the demo. The landscape lock plus `configChanges` already prevents recreation; this pattern makes even a recreation harmless (PITFALLS #7).

### Pattern 4: Class map → de-dup → tracker (D-08, D-09)

**What:** `ClikaRtDetector.detect` = raw rows → `LabelMap.accept(labelId, score)` → `Dedup.apply(list, iou = 0.5f)`: pairs above the IoU threshold keep the DRONE one if classes differ, else the higher score (RT-DETR is NMS-free: at 0.25 the same prop can come back as aeroplane *and* kite). `Tracker.update(detections, nowMillis)`: a track keeps `lastSeenMillis`; a DRONE track coasts for 5000 ms, a PERSON track for ~1500 ms, independent of fps (at 0.5 fps the current `maxMisses = 3` would coast 6 s, at 2 fps 1.5 s — frame counts are the wrong unit). Keep the existing `update(detections)` overload delegating to the clock so the 9 tracker tests still pass.

### Pattern 5: Lock that survives a lost detection (D-06, D-09)

**What:** `challenge(boxId)` (Phase 2 scope = KDoc step 1): refuse while `lock?.isActive(now) == true`; find the track in `tracker.tracks` (frame pixels); `lock = BoxLock(boxId, track.box, now + BoxLock.WINDOW_MS)`; `iffById[boxId] = IffState.Challenged(until = lock.untilMillis)`; `log("challenge · box $boxId")`; publish. In `tick()`: exempt `lock.boxId` from `iffById.keys.retainAll(...)` (`IffCoordinator.kt:72`); after building `trackUis`, if the lock is active and no live track has `boxId`, append `TrackUi(boxId, DRONE, transform.toPreview(lock.rect), Challenged(until))`; when the lock expires with no verdict, drop the lock and the state (Phase 5 replaces this expiry with `Verdict(UNKNOWN, "no response")`). Thread safety: `tick` runs on the lane, `challenge` on the main thread → guard the post-detect state section of `tick` and all of `challenge` with one `synchronized(stateLock)` (the detect call itself stays outside the lock so a tap is never delayed by inference); mark `previewWidth/Height` `@Volatile`.

### Pattern 6: Status fields through the contract (OBS-04, D-07, D-12)

**What:** `tick()` already fills `msPerFrame`, `fps`, `modelReady` (`IffCoordinator.kt:76-84`). Add `fun setStatus(update: (StatusUi) -> StatusUi)` on the coordinator; the loader calls it for `modelReady`/`modelNote`; a main-thread ticker in `MainActivity` (`lifecycleScope.launch { while (!ready) { setStatus { it.copy(modelNote = "model loading… ${elapsed} s") }; delay(1000) } }`) renders D-07's seconds; `tick()` sets `offline = offlineProbe()`. The one contract change is additive: `StatusUi(..., val modelNote: String? = null)` — every existing constructor call and `DemoScenario` keep compiling; the skeleton `StatusStrip` gets one more chip (`modelNote ?: ""`), which the UI stream (issue #14) will restyle. Commit it as `[shared] StatusUi.modelNote` and push at once.

### Pattern 7: Preview under the Canvas without taking over `ui/` (D-10, README ownership)

**What:** `CameraScreen` gains one optional slot `preview: (@Composable BoxScope.() -> Unit)? = null` drawn first inside its `Box` (`CameraScreen.kt:71`), and the overlay size is reported through `Modifier.onSizeChanged { coordinator.setPreviewSize(it.width, it.height) }` on that same `Box`. `MainActivity` passes `preview = { AndroidView(factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { pv -> cameraController.start(lifecycleOwner, pv, coordinator::tick) } }, Modifier.fillMaxSize()) }`. `DemoCameraScreen` and the `@Preview`s are untouched. If the SurfaceView-backed preview ever draws over the Compose canvas on this device, set `implementationMode = PreviewView.ImplementationMode.COMPATIBLE` (TextureView).

### Pattern 8: Parity harness and PNG dump (D-15, PIT-04, PITFALLS #10/#44)

**What:** vision_lens-style intent extras on the debug build: `adb shell am start -n com.chloeyeo.friendorfoe/.MainActivity --es parity parity/scene.png` → on the lane: decode the asset with `BitmapFactory` (ARGB_8888) → `copyPixelsToBuffer` → `Frame` → `detectRaw(frame)` → log every raw row `label_id score xmin ymin xmax ymax` under tag `FoF`; `--ez dump_frame true` → the next live frame is written as PNG (`Bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))`, `compress(PNG)`) to `filesDir/dumps/frame.png`, pulled with `adb exec-out run-as … cat`. Laptop reference, same image:

```bash
export PATH="C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/bin:C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/lib:$PATH"
clikart-cli.exe --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd detect frame.png --device cpu --confidence 0.25 --max-detections 20
# stdout: {"image":{...},"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}],"model":"PekingU/rtdetr_v2_r18vd"}
```
(`detect --help` verified this session: `--confidence F` default 0.5, `--max-detections N` default 100, `--device`; global `--offline --cache-dir` go before the model source.) Identical rows (same `label_id`s, scores within ~0.02, boxes within a few px; use PNG so both sides decode losslessly) = the RGBA path is faithful; a red object that comes out blue in the dump = channel order wrong. Why not an instrumented test: `connectedDebugAndroidTest` reinstalls the ~300 MB APK per run; the intent extra reuses the installed app (seconds).

### Pattern 9: `HttpDetector` for real (the H4 fallback, DET-05 leftover)

**What:** `Frame` → `Bitmap` (`copyPixelsFromBuffer`) → `compress(JPEG, 80)` → OkHttp `MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", "frame.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).addFormDataPart("confidence", "0.25").addFormDataPart("max_detections", "20")` → `POST $baseUrl/v1/detections` (call timeout 2 s) → `DetectionsJson.parse(body)` (pure, `org.json`, unit-tested with the real jar) → `LabelMap.accept` → `Dedup`. Verified contract (STACK §9, live server): field **`image`**, optional `confidence`/`max_detections`; response `{"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}]}` in input pixels, score-descending; wrong field → 400. Laptop: `clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd serve --device cpu --host 0.0.0.0 --port 8000`, Windows firewall rule for 8000, phone and laptop on one LAN (issue #3, still open). `Prefs.detectorConfig = DetectorConfig.Http("http://<laptop>:8000")` is the one-line flip (settable via an `--es detector http:http://192.168.x.y:8000` intent extra until the settings screen exists).

### Anti-Patterns to Avoid
- **Analyzer on the runtime lane / holding the proxy for a second** (the skeleton KDoc's idea): blocks the camera callback and contradicts D-02.
- **Lazy-opening the model inside the first `tick`**: the first frame would block the lane for the whole load with no "model loading… N s" progress; open explicitly in the bring-up sequence.
- **Keying anything on `label` strings** (`"aeroplane"` here, `"airplane"` elsewhere) — `LabelMap` keys on `labelId` already; keep it so.
- **`Backends.isAvailable(ComputeApi.VULKAN)` or `device = "vulkan:0"` in the demo build** — maps 139 MB and is unverified on this driver (PITFALLS #5).
- **Catching only `ModelverseException`** — `ClikaRtException` is a sibling `RuntimeException`; catch both (vision_lens `guarded`).
- **`adb uninstall` / changing the signing config** — deletes `files/models` and changes `ANDROID_ID` for the license (PITFALLS #3, #8).
- **Closing the model on Activity destroy** — it is a process singleton (D-03).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| YUV→RGB conversion | A libyuv call or a Kotlin loop | `OUTPUT_IMAGE_FORMAT_RGBA_8888` | CameraX does it natively (`ImageAnalysisAbstractAnalyzer.java:211-221`) |
| Preprocessing (resize 640×640, rescale) | Manual scaling/normalisation | The pipeline | `preprocessor_config.json` is applied by the binding; hand it raw RGBA |
| Backpressure / frame dropping | A frame queue | `STRATEGY_KEEP_ONLY_LATEST` + one busy flag | A queue grows without bound at 30 fps in / 1 fps out |
| JPEG/PNG encode for HTTP and dumps | An encoder | `Bitmap.compress` | Platform |
| Multipart HTTP | Hand-built boundaries | OkHttp `MultipartBody` | Already a dependency; boundaries/encodings are easy to get subtly wrong |
| JSON | String slicing | `org.json` (platform + real jar in tests) | Already used by `GadgetLineCodec` |
| Object tracking | ByteTrack/DeepSORT | The skeleton `Tracker` + `BoxLock` | 1 fps makes motion models useless (REQUIREMENTS Out of Scope) |
| Memory trimming | Own `onTrimMemory` logic | `ClikaRtAndroid.trimOnMemoryPressure` | Releases the runtime's idle cached memory per device (`ClikaRtAndroid.kt:89-162`) |

**Key insight:** the only custom code with real logic in this phase is pure Kotlin (packer, de-dup, sticky tracking, lock, offline rule, JSON parse) — all JVM-testable before the phone is touched.

## Common Pitfalls

Project-level PITFALLS.md #1–#17 and #44 stay valid; these are the phase-specific ones found by reading the skeleton against the binding and CameraX sources.

### A. ClikaRT bring-up
- **A1 — License failure arrives at the first compute, and the pipeline path loses the code name.** `Modelverse.load` succeeds with a bad credential; the first operator throws (`ClikaRt.kt:56-62`), and through `ObjectDetectionPipeline.invoke` it is `ModelverseException(message)` with `codeName == ""` (`Pipelines.kt:118, 268-272`). *Avoid:* run the hello-sample tensor-op probe right after load and catch `ClikaRtException` (`codeName`) **and** `ModelverseException`; show `codeName.ifEmpty { message.take(80) }`. *Warning sign:* "LICENSE…" only inside a long message, or a load that "worked" followed by a detect that fails.
- **A2 — `Modelverse.load` is idempotent, so an empty `BuildConfig.CLIKA_RT_LICENSE` on the first call sticks** (PITFALLS #2). *Avoid:* `license.ifBlank { null }`, and never load the runtime for the `Fake`/`Http` configs.
- **A3 — `Modelverse.load(context, license)` is an extension; the member `load(license)` also exists.** Import `io.clika.modelverse.load`; the two-argument call resolves to the Android extension (`ModelverseAndroid.kt:17-18`).
- **A4 — The runtime holds `CLIKA_RT_NUM_THREADS` after the first compute**: the 2/4/8 experiment needs `am force-stop` between runs.
- **A5 — `requireLoaded()` throws if `fromPretrained` runs before `Modelverse.load`** (`Modelverse.kt:317-319`): keep the bring-up a single ordered function.

### B. Coordinator and tracking
- **B1 — `IffCoordinatorTest.challenge is the integration lead's TODO` asserts `NotImplementedError`** (`IffCoordinatorTest.kt:45-49`): replace it with the lock test, or the suite goes red the moment `challenge` works.
- **B2 — `Prefs.detectorConfig` returns `Fake` on a fresh install** (`Prefs.kt:15-18`, `DetectorConfig.decode` falls back to `Fake`): with no change the demo build shows scripted boxes forever.
- **B3 — `iffById.keys.retainAll(live ids)`** (`IffCoordinator.kt:72`) erases the `Challenged` state the moment the tracker drops the box — exactly the case DET-06 exists for. Exempt the locked id and append the lock's synthetic `TrackUi`.
- **B4 — Frame-count coasting (`maxMisses = 3`) is fps-dependent**; D-09's 5 s needs wall-clock stickiness.
- **B5 — Two threads touch the coordinator** (`tick` on the lane, `challenge`/`setPreviewSize` on main): synchronise the state section; `@Volatile` the preview size.

### C. Camera and geometry
- **C1 — `PreviewView.getViewPort()` is null before layout** (`PreviewView.java:597-607`): bind inside `doOnLayout`, not in `factory`.
- **C2 — `pixelStride` must be 4 and `rowStride` may exceed `width*4`** (binding has no stride parameter, `VisionModel.kt:235-246`): assert once, repack rows (D-02); a sheared or "green-lined" dump = stride ignored (PITFALLS #10).
- **C3 — `rotationDegrees` may be 180 on a 270°-mounted sensor even in locked landscape**: `FrameTransform` requires 0 (`FrameTransform.kt:23`); rotate in the packer (180° = reverse the pixel sequence) and pass `rotationDegrees = 0` in the `Frame`.
- **C4 — A 4:3 analysis fallback breaks the mapping silently**: request `RATIO_16_9_FALLBACK_AUTO_STRATEGY` on **both** use cases and assert `width*9 == height*16` on the first frame; otherwise use the `cropRect` recipe.
- **C5 — Fast-close costs one RGBA conversion per camera frame** (`ImageAnalysisNonBlockingAnalyzer.java:73-108`): acceptable; hold the proxy ~200 ms when busy only if ms/frame proves CPU contention.
- **C6 — `collectAsStateWithLifecycle` does not compile** (no `lifecycle-runtime-compose` dependency): use `collectAsState()`.

### D. Thresholds, prop, demo
- **D1 — NMS-free duplicates**: one prop → aeroplane + kite boxes → two tracks, two rings. `Dedup` by IoU (D-08).
- **D2 — Black-frame warm-up returns zero rows** — that is fine; only its latency matters.
- **D3 — Thermal throttling after minutes at 100 % on two big cores** (PITFALLS #41): watch ms/frame rise during the rehearsal; keep the screen brightness and the phone cool.
- **D4 — Port drift**: `HttpDetector.kt` KDoc says 8129, the plan says 8000 (`laptop/README.md`, issue #8). Use 8000 everywhere.

## Code Examples

Verified shapes; names follow the skeleton. Each snippet cites the source it mirrors.

### 1. Runtime bring-up with the license probe (`app/FofApplication.kt`)
```kotlin
// Mirrors examples-0.6.4/android/shared/SampleRuntime.kt:30-34, hello/MainActivity.kt:92-99,
// ClikaRtAndroid.kt:41-45 (threads), :100-104 (trim), :171-178 (logs); ModelverseAndroid.kt:17-30
import android.system.Os
import io.clika.modelverse.Modelverse
import io.clika.modelverse.load          // the Android extension Modelverse.load(context, license)
import io.clika.runtime.ClikaRtAndroid
import io.clika.runtime.ClikaRtException
import io.clika.runtime.Ops
import io.clika.runtime.Tensors

/** Blocking; RuntimeLane only. Throws ClikaRtException / ModelverseException with codeName on refusal. */
fun loadRuntime(license: String?, threads: Int = 2) {
    if (Modelverse.isLoaded) return
    Os.setenv("CLIKA_RT_NUM_THREADS", threads.toString(), true)   // read once at the first compute
    Modelverse.load(applicationContext, license?.ifBlank { null })  // idempotent; never log `license`
    ClikaRtAndroid.bridgeLogs()                                     // adb logcat -s ClikaRT
    ClikaRtAndroid.trimOnMemoryPressure(this)
    // License probe: the first operator is where LICENSE_* surfaces, as a clean ClikaRtException.codeName.
    Tensors.of(floatArrayOf(1f, 2f, 3f), longArrayOf(3)).use { a ->
        Tensors.of(floatArrayOf(10f, 20f, 30f), longArrayOf(3)).use { b ->
            Ops.add(a, b).use { sum -> check(sum.summary().isNotEmpty()) }
        }
    }
}
```

### 2. `ClikaRtDetector` open / detect (`detect/ClikaRtDetector.kt`)
```kotlin
// Auto.kt:283-287, Types.kt:831-869, Modelverse.kt:197-202, Pipelines.kt:297-317, vision_lens ModelverseEngine.kt:32-40
import io.clika.modelverse.*

class ClikaRtDetector(private val modelDir: String) : Detector {
    override val name = "clikart:rtdetr_v2_r18vd"
    @Volatile private var pipeline: ObjectDetectionPipeline? = null
    private val options = DetectOptions(maxResults = 20, scoreThreshold = 0.25f)   // labels stay empty (closed-set)

    /** Blocking, seconds; RuntimeLane only. Throws on a missing dir, a wrong label table or a runtime refusal. */
    fun open(): Pipeline {
        pipeline?.let { return it }
        val dir = java.io.File(modelDir)
        for (f in listOf("config.json", "model.safetensors", "preprocessor_config.json")) {
            require(java.io.File(dir, f).isFile) { "model dir missing: $f in $modelDir" }
        }
        val model = AutoModelForObjectDetection.fromPretrained(dir.absolutePath, VisionLoadOptions(offline = true, device = "cpu"))
        val labels = model.capabilities.labels
        check(VisionTask.DETECT in model.capabilities.visionTasks && !model.capabilities.openVocabulary) { "not a closed-set detector" }
        check(labels.size == 80 && labels[4] == "aeroplane" && labels[0] == "person") { "unexpected label table: ${labels.take(5)}" }
        val p = Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline
        p(ImageInput.Rgba8888(ByteArray(640 * 640 * 4), 640, 640), options)        // warm-up
        pipeline = p
        return p
    }

    fun detectRaw(frame: Frame): List<io.clika.modelverse.Detection> =
        (pipeline ?: open() as ObjectDetectionPipeline)(ImageInput.Rgba8888(frame.rgba, frame.width, frame.height), options)

    override fun detect(frame: Frame): List<Detection> = Dedup.apply(
        detectRaw(frame).mapNotNull { d ->
            LabelMap.accept(d.labelId, d.score)?.let { cls -> Detection(cls, d.score, BoxF(d.box.left, d.box.top, d.box.right, d.box.bottom)) }
        },
    )

    override fun close() { pipeline?.close(); pipeline = null }   // closes the model (Pipelines.kt:26); never during the demo
}
```

### 3. `RgbaPacker` (pure Kotlin, `camera/RgbaPacker.kt`) — the D-02 copy
```kotlin
// ImageAnalysis.java:175-184 (single R,G,B,A plane), Types.kt:800-807 (tight rows), PITFALLS #10
object RgbaPacker {
    /** Repacks a padded RGBA plane into tight rows; [rotationDegrees] 0/90/180/270 applied so the result is upright. */
    fun pack(src: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotationDegrees: Int): Frame {
        require(pixelStride == 4) { "RGBA_8888 expects pixelStride 4, got $pixelStride" }
        require(rowStride >= width * 4) { "rowStride $rowStride < width*4" }
        val tight = ByteArray(width * height * 4)
        val buf = src.duplicate()
        if (rowStride == width * 4) { buf.position(0); buf.get(tight) }
        else for (y in 0 until height) { buf.position(y * rowStride); buf.get(tight, y * width * 4, width * 4) }
        return when (rotationDegrees) {
            0 -> Frame(width, height, tight)
            180 -> Frame(width, height, reversePixels(tight))                 // 180° = reverse the pixel sequence
            90, 270 -> rotate90(tight, width, height, clockwise = rotationDegrees == 90)
            else -> error("rotation $rotationDegrees")
        }
    }
    // reversePixels / rotate90: 10 lines each, unit-tested with a 3x2 synthetic frame
}
```
In the analyzer: `val p = proxy.planes[0]; RgbaPacker.pack(p.buffer, proxy.width, proxy.height, p.rowStride, p.pixelStride, proxy.imageInfo.rotationDegrees)`, then `Frame(..., timestampNanos = proxy.imageInfo.timestamp)`.

### 4. `CameraController` bind (`camera/CameraController.kt`)
```kotlin
// CameraFrames.kt:58-86 (official), ImageAnalysis.java:1138/1190/1524, UseCaseGroup.java:109/169, PreviewView.java:600, ProcessCameraProvider.kt:199
fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onFrame: (Frame) -> Unit) {
    previewView.doOnLayout {                                              // getViewPort() is null before layout
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get().also { this.provider = it }
            val aspect16x9 = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect16x9).build())
                .build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect16x9)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->                 // "camera-analysis" single thread
                try {
                    logOnce(proxy)                                          // size, cropRect, rotation, strides
                    if (!busy.compareAndSet(false, true)) return@setAnalyzer   // drop; never queue
                    val plane = proxy.planes[0]
                    val frame = RgbaPacker.pack(plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride, proxy.imageInfo.rotationDegrees)
                    RuntimeLane.executor.execute { try { onFrame(frame) } finally { busy.set(false) } }
                } finally { proxy.close() }
            }
            val group = UseCaseGroup.Builder().setViewPort(requireNotNull(previewView.viewPort)).addUseCase(preview).addUseCase(analysis).build()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        }, ContextCompat.getMainExecutor(context))
    }
}
fun stop() { ContextCompat.getMainExecutor(context).execute { provider?.unbindAll() } }
```

### 5. `OfflineProbe` (`app/OfflineProbe.kt`)
```kotlin
// Constants verified in android.jar 36: AIRPLANE_MODE_ON = "airplane_mode_on"; NET_CAPABILITY_VALIDATED = 16 (API 23)
class OfflineProbe(context: Context) : () -> Boolean {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    override fun invoke(): Boolean {
        val airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val validated = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return OfflineRule.isOffline(airplane, validated)      // pure: airplane && !validated (D-12)
    }
}
```

### 6. `HttpDetector` request (`detect/HttpDetector.kt`) — contract from STACK §9 / `laptop/README.md`
```kotlin
val jpeg = ByteArrayOutputStream().also { out ->
    Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba)) }
        .compress(Bitmap.CompressFormat.JPEG, 80, out)
}.toByteArray()
val body = MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
    .addFormDataPart("confidence", "0.25").addFormDataPart("max_detections", "20").build()
client.newCall(Request.Builder().url("$baseUrl/v1/detections").post(body).build()).execute().use { r ->
    check(r.isSuccessful) { "serve ${r.code}" }
    DetectionsJson.parse(r.body.string())     // pure: detections[].box.{xmin,ymin,xmax,ymax}, score, label_id → LabelMap → Dedup
}
```
`OkHttpClient.Builder().callTimeout(2, SECONDS)`; `name = "http:<host:port>"` already derived (`HttpDetector.kt:20`).

### 7. `MainActivity` wiring (sketch)
```kotlin
// vision_lens MainActivity.kt:54-56 (permission contract), hello MainActivity.kt:64-66 (work on the lane)
private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startCamera() }
override fun onCreate(b: Bundle?) {
    super.onCreate(b); enableEdgeToEdge()
    val app = FofApplication.from(this); val coordinator = app.coordinator        // process singleton
    app.startRuntimeIfNeeded(threads = intent.getIntExtra("clika_threads", 2))    // lane; updates modelReady/modelNote
    setContent { FriendOrFoeTheme {
        val state by coordinator.state.collectAsState()
        CameraScreen(state, onTapTrack = coordinator::challenge, preview = { AndroidView(factory = { ctx -> PreviewView(ctx).also { pv -> previewView = pv; maybeStartCamera() } }, Modifier.fillMaxSize()) })
    } }
    if (ContextCompat.checkSelfPermission(this, CAMERA) == PERMISSION_GRANTED) startCamera() else askCamera.launch(CAMERA)
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `ImageAnalysis.Builder.setTargetResolution` | `ResolutionSelector` + `AspectRatioStrategy`/`ResolutionStrategy` | CameraX 1.3 | Used here; the old API is deprecated |
| YUV_420_888 + manual conversion | `OUTPUT_IMAGE_FORMAT_RGBA_8888` | CameraX 1.1 | Zero conversion code in the app |
| `setOutputImageRotationEnabled(true)` | Lock orientation; rotate in the packer | Bug fixed only in 1.7.0-alpha01 | Avoided by design (D-01) |
| `CameraController` + `MlKitAnalyzer` view coordinates | Manual `UseCaseGroup` + `FrameTransform` | — | One coordinate system (D-01) |
| `ObjectDetectionPipeline(model)` | `Modelverse.pipeline(task, model) as ObjectDetectionPipeline` | ClikaRT 0.6.x (`internal` ctor) | AGENTS.md "What NOT to use" |
| Hub id + `cacheDir` on the phone | Snapshot directory path + `offline = true` | — | Three files, no cache layout (PITFALLS #3) |

**Deprecated/outdated in the skeleton's own notes:** `CameraController.kt:16` suggests `setOutputImageRotationEnabled(true)` — superseded by D-01; `FofApplication.kt:20` names the external files dir first — superseded by D-04; `HttpDetector.kt:5,17` port 8129 — superseded by 8000.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The A06 back sensor is mounted at 90°, so `rotationDegrees == 0` in locked landscape | C (rotation) | 180 (or 90/270) case: the packer's rotation branch handles it; boxes would be upside-down only if the branch is skipped — log at the first frame |
| A2 | The A06 display is 720×1600 (20:9) | C (mapping example) | Only the worked numbers change; the formula does not |
| A3 | `filesDir/models/rtdetr_v2_r18vd` still holds the three files (Phase 1 did the `run-as` copy; phone not attached now) | M | 2 min to re-copy from `/data/local/tmp`; the commands are listed |
| A4 | The Offline bundle is accepted on phone A in airplane mode (seat/`ANDROID_ID`) — never proven on the phone (issue #2 open; STATE.md does not record the airplane-mode harness) | Pattern 1 | **Biggest H4 risk**: `LICENSE_FAILED`/`LICENSE_EXPIRED` → re-issue/seat on platform.clika.io; meanwhile `HttpDetector` |
| A5 | CPU latency 0.5–2 s/frame at 1280×720 with 2 threads | D-14 | Drives the 960×540 / thread experiments at H2; nothing structural |
| A6 | Keeping CameraX 1.4.1 (vs AGENTS.md's 1.6.2) is the right call for this phase | C | 1.6.2 is in the Gradle cache; a bump is a one-line TOML change plus a reinstall |
| A7 | OkHttp 5 Kotlin API names (`toRequestBody`, `toMediaType`, `MultipartBody.FORM`) | Code Example 6 | Compile error, 5 min fix |
| A8 | `run-as … cp` from `/data/local/tmp` is readable by the app uid (worked in Phase 1 per STATE.md) | M | Fallback: `adb push` to `/sdcard/Download` then `run-as cp` |
| A9 | Polling the offline probe at tick rate (not callbacks) satisfies OBS-04 | O | The badge lags ≤ 1 s behind a toggle — acceptable |

## Open Questions (RESOLVED — each is answered by a phone task: Q1 02-05 T1/T3, Q2 02-04 T2 + 02-05 T1, Q3 02-06 T1/T3, Q4 02-06 T2/T3, Q5 02-06 T1)

1. **Does the Offline license bundle run on phone A in airplane mode?**
   - What we know: the bundle is one 3258-byte `CLIKA1…` line, embedded via BuildConfig; the laptop runs `--offline` detections with it; the runtime binds licenses to `ANDROID_ID` and seats are per hardware.
   - What's unclear: whether the bundle's seats include phone A; Phase 1's airplane-mode harness result is not recorded.
   - Recommendation: first phone task of the phase, before any camera work — load + tensor probe + model open in airplane mode; code name on screen.
2. **What are `rotationDegrees`, the delivered analysis size and `cropRect` on the A06?**
   - Recommendation: log all four at the first frame; assert 16:9; branch only if the log says so.
3. **Actual ms/frame → resolution and thread count.** Measure at H2 with 2 threads at 1280×720; then 4 and 8 (force-stop between runs); drop to 960×540 only above 1.5 s (D-14).
4. **Which prop does RT-DETR see (PIT-04)?** Use `detectRaw` logging (scores per label id) during the H3 prop test; record winner, score and distance in README.
5. **Does the busy-gate's per-frame conversion tax matter on two big cores?** Visible as ms/frame variance; mitigation (hold the proxy ~200 ms) is a two-line change.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Gradle wrapper + JDK toolchain | build/tests | ✓ | Gradle 9.4.1, daemon JDK 21 via foojay (`gradle/gradle-daemon-jvm.properties`), host JDK 22.0.2 | — |
| Gradle cache (offline) | tests/build | ✓ | `:app:testDebugUnitTest --offline` green this session | — |
| adb | install, model copy, logs, parity pull | ✓ tool / **✗ phone** | adb 1.0.41 at `C:/Users/C/AppData/Local/Android/Sdk/platform-tools/adb.exe`; `adb devices` empty at research time | Attach phone A (R83YB0LR9GE) before the first phone checkpoint |
| ClikaRT AAR 0.6.4 | app | ✓ | resolved from `C:/Users/C/clika` (local.properties `clikaRtMavenRepo`) | — |
| ClikaRT binding sources + examples | research/reference | ✓ | `C:/Users/C/clika/reference/{clika-runtime-0.6.4-sources,examples-0.6.4}` | — |
| `clikart-cli.exe` | parity reference, `serve` fallback | ✓ | 0.6.4 at `C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/bin` (`detect --help` ran) | — |
| RT-DETR snapshot (laptop cache) | parity, serve | ✓ | 3 files, 80,904,640 B weights | — |
| RT-DETR snapshot (phone `filesDir`) | DET-02 | ? (not verifiable now) | per STATE.md present | re-copy from `/data/local/tmp` (§M) |
| License file | BuildConfig | ✓ | 1 line, 3258 B, `C:/Users/C/clika/clikart-offline.license`, expires 2026-10-17 | — (never copy its text) |
| CameraX 1.4.1 (+1.6.2) | DET-01 | ✓ | both in the Gradle cache | — |
| Python 3.13.3, gh 2.93.0 | laptop side-cars, issues | ✓ | — | — |
| Laptop LAN with phone A (for `HttpDetector`) | H4 fallback | ✗ not set up (issue #3 open) | — | Phone hotspot or travel router; firewall rule for 8000 |

**Missing dependencies with no fallback:** none (the phone must simply be attached).
**Missing dependencies with fallback:** the LAN for the HTTP fallback (set up only if H4 trips).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 (`testImplementation(libs.junit)`), real `org.json` on the test classpath; no Robolectric/mockito |
| Config file | none (AGP defaults; `app/build.gradle.kts`) |
| Quick run command | `./gradlew :app:testDebugUnitTest --offline -q` (~1–2 min cold, seconds warm; 50 tests green now) |
| Full suite command | `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DET-01 | Padded RGBA plane → tight `Frame`; 180° rotation; pixelStride guard | unit | `./gradlew :app:testDebugUnitTest --tests "*RgbaPackerTest" --offline` | ❌ Wave 0 |
| DET-01 | Preview + analysis bound, KEEP_ONLY_LATEST, 1280×720 delivered, `rotationDegrees` logged 0 | manual (phone) | — (logcat `FoF` first-frame line) | human checkpoint |
| DET-02 | Runtime loads in airplane mode, probe passes, model opens, warm-up time logged, boxes at ≈1 fps | manual (phone) | — (status strip + `adb logcat -s ClikaRT FoF`) | human checkpoint |
| DET-02 | Parity: `assets/parity/scene.png` through `detectRaw` equals `clikart-cli detect` rows | manual (phone + laptop) | `am start --es parity …` + CLI command (§Pattern 8) | ❌ asset + harness |
| DET-03 | labelId map + thresholds | unit | `--tests "*LabelMapTest"` | ✅ |
| DET-03 | IoU de-dup keeps the drone-class box / higher score | unit | `--tests "*DedupTest"` | ❌ Wave 0 |
| DET-04 | FILL_CENTER mapping incl. the 1280×720 → 1600×720 case and edge boxes | unit | `--tests "*FrameTransformTest"` | ✅ (add the 1600×720 case) |
| DET-04 | Rings sit on objects at the frame edge | manual (phone) | — | human checkpoint |
| DET-06 | `challenge` locks; lock survives a lost detection; expires at 10 s; refuses a second tap | unit | `--tests "*IffCoordinatorTest"` | ✅ file, ❌ cases (replace the `NotImplementedError` test) |
| DET-06 / D-09 | Drone track sticky 5 s by wall clock, person shorter | unit | `--tests "*TrackerTest"` | ✅ file, ❌ cases |
| DET-05 | `/v1/detections` JSON → `Detection`s (field names, order, unknown fields tolerated) | unit | `--tests "*DetectionsJsonTest"` | ❌ Wave 0 |
| OBS-04 | `offline = airplane && !validated`; `tick` publishes ms/fps/modelReady/offline/modelNote | unit | `--tests "*OfflineRuleTest" --tests "*IffCoordinatorTest"` | ❌ Wave 0 |
| OBS-04 | OFFLINE badge toggles with airplane mode on the phone | manual (phone) | — | human checkpoint |
| PIT-04 | Prop decision recorded with score/distance | manual (phone) | — | README entry |

### Sampling Rate
- **Per task commit:** `./gradlew :app:testDebugUnitTest --offline -q`
- **Per wave merge:** `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline` then `:app:installDebug` only when a phone checkpoint follows (each install is ~300 MB)
- **Phase gate:** suite green + the five phone checkpoints (license, live boxes, parity, edge mapping, prop) logged before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/camera/RgbaPackerTest.kt` — DET-01 (stride, 0/180 rotation, guards)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DedupTest.kt` — DET-03 (D-08)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DetectionsJsonTest.kt` — DET-05 fallback parser (uses the real `org.json` already on the test classpath)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/app/OfflineRuleTest.kt` — OBS-04 rule
- [ ] `TrackerTest.kt` sticky-by-time cases; `IffCoordinatorTest.kt` lock cases (and removal of the `assertThrows(NotImplementedError)` test at :45-49)
- [ ] `app/src/main/assets/parity/scene.png` (copy of `examples-0.6.4/vision_lens/samples/scene.png`) for the parity harness
- Framework install: none needed

## Security Domain

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — (no users) |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes | `Frame.init` size guard (`Frame.kt:20-25`); `RgbaPacker` stride/pixelStride guards; `DetectionsJson` tolerant parse with bounds; intent extras only honoured in debug builds |
| V6 Cryptography | no (Phase 3/4 on the gadget) | — |
| V8 Data Protection | yes | License only in `BuildConfig`; `modelNote`/logs carry `codeName`/message, never the credential (the runtime "never logs" it, `ClikaRtAndroid.kt:38-39`); model files in app-private `filesDir`; PNG dumps in `filesDir/dumps` (app-private) |
| V14 Configuration | yes | `ALLOW_HTTP_DETECTOR` false outside debug (`app/build.gradle.kts:41-50`); no network added; `usesCleartextTraffic` already scoped to the LAN use |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Credential leak via logs/screenshots | Information disclosure | Show `codeName` only; never `Log` the BuildConfig value; SEC-03 |
| Demo drifting to the laptop detector | Repudiation of the SC1 claim | Build flag guard + Phase 6 re-run rule (PITFALLS #44) |
| Malformed `/v1/detections` body (dev LAN) | Tampering | Tolerant parser, bounds-checked boxes, 2 s timeout, debug-only |
| Secrets in the repo | Information disclosure | `.gitignore` covers `*.license`, `local.properties`, `*.safetensors` (verified `.gitignore:17-20`) |

## H. Suggested wave structure to reach H4 (for the planner)

| Wave | Time | Content | Verified by |
|------|------|---------|-------------|
| 0 | H1 (20 min) | Test stubs above; `StatusUi.modelNote` `[shared]`; `Prefs` default | JVM tests red → green |
| 1 | H1–H2 | `loadRuntime` + probe; `ClikaRtDetector.open/detect/detectRaw`; `Dedup`; `HttpDetector` real (+ KDoc port 8000); intent extras (`clika_threads`, `parity`, `dump_frame`, `detector`) | JVM tests; **phone checkpoint 1 (airplane mode):** version + probe + model open + warm-up time, code name on failure |
| 2 | H2–H3 | `RgbaPacker`, `CameraController`, busy gate, `MainActivity`, preview slot, `OfflineProbe`, lock + sticky in coordinator/tracker | **Phone checkpoint 2:** live boxes on a person; first-frame log (size/rotation/stride/cropRect); PNG dump vs CLI; OFFLINE badge; tap lock survives occlusion; edge-of-frame ring |
| 3 | H3–H4 | Prop test (three props, raw-score log), thread-count runs (2/4/8), resolution decision, README numbers (load s, warm-up ms, ms/frame, fps) | **Phone checkpoint 3 (PIT-04)** and the H4 gate: criterion 1 met → continue; not met → `Prefs.detectorConfig = Http(...)`, laptop serve on 8000, continue with criteria 2–5 |

## Sources

### Primary (HIGH confidence)
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/` — `commonMain/io/clika/modelverse/{Auto.kt, VisionModel.kt, Pipelines.kt, Types.kt, Modelverse.kt, Listeners.kt, ModelverseException.kt}`, `commonMain/io/clika/runtime/{ClikaRt.kt, Devices.kt, Tensors.kt, Ops.kt}`, `androidMain/io/clika/{modelverse/ModelverseAndroid.kt, runtime/ClikaRtAndroid.kt}` — every signature and line cited above
- `C:/Users/C/clika/reference/examples-0.6.4/` — `android/shared/{SampleRuntime,RuntimeLane,LicenseStore}.kt`, `android/hello/{build.gradle.kts, MainActivity.kt, HelloApplication.kt}`, `android/README.md`, `vision_lens/src/shared/src/androidMain/.../{CameraFrames.kt, LivePane.android.kt}`, `vision_lens/src/shared/src/commonMain/.../ModelverseEngine.kt`, `vision_lens/src/androidApp/.../MainActivity.kt`, `vision_lens/src/androidApp/src/main/AndroidManifest.xml`, `kotlin/howto/vision_tasks/detect_objects.kt`
- CameraX 1.4.1 sources jars (Gradle cache): `camera-core` `ImageAnalysis.java`, `ImageProxy.java`, `ImageInfo.java`, `UseCaseGroup.java`, `ViewPort.java`, `resolutionselector/*.java`, `ImageAnalysisNonBlockingAnalyzer.java`, `ImageAnalysisAbstractAnalyzer.java`; `camera-view` `PreviewView.java`, `transform/*.java`; `camera-lifecycle` `ProcessCameraProvider.kt`
- Android SDK platform 36: `android.jar` (`javap -constants`) and `data/api-versions.xml` — `AIRPLANE_MODE_ON`, `NET_CAPABILITY_VALIDATED` (API 23), `getActiveNetwork` (23), `registerDefaultNetworkCallback` (24), `ACTION_AIRPLANE_MODE_CHANGED`
- Model snapshot `C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/{config.json, preprocessor_config.json, model.safetensors}` — label table, preprocessing, sizes
- Repo state: all skeleton files under `app/src/main/java/com/chloeyeo/friendorfoe/` and `app/src/test/...`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `.gitignore`, `README.md` §3–§4, `laptop/README.md`, `eval/README.md`; `./gradlew :app:testDebugUnitTest --offline` result; GitHub issues #2–#9 (`gh issue view`)
- `clikart-cli.exe … detect --help` (0.6.4) — flags for the parity command
- `.planning/research/{STACK.md §1, §2, §9, §10; PITFALLS.md #1–#17, #44; ARCHITECTURE.md Pattern 1, coordinate systems, threading, runtime files; FEATURES.md DET rows}` — project-level research reused, not redone

### Secondary (MEDIUM confidence)
- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera) — 1.6.2 stable (Aug 26 2026); 1.7.0-alpha01 `ImageAnalysis` rotation fix text
- [CameraX orientation and rotation](https://developer.android.com/media/camera/camerax/orientation-rotation) — `rotationDegrees` definition, default target rotation, locked-orientation behaviour
- [CameraX transform output](https://developer.android.com/media/camera/camerax/transform-output) — `getCorrectionMatrix` recipe (`cropRect` corners → view corners shifted by `rotationDegrees/90`)
- Memory notes `project-phone-a-device`, `reference-clika-license` (paths and adb gotchas; the phone was not attached to re-verify)

### Tertiary (LOW confidence / needs validation on the device)
- A06 sensor orientation, display size, CPU latency, `cropRect` shape, license seat on the phone — all flagged in the Assumptions Log

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — nothing new; all versions resolved offline and compiled/tested this session
- ClikaRT API: HIGH — read from the 0.6.4 sources, cross-checked against three official samples
- CameraX API/behaviour: HIGH for 1.4.1 API presence and KEEP_ONLY_LATEST semantics (sources); MEDIUM for the device's delivered size/rotation
- Architecture: HIGH — follows the skeleton's contracts and the official runtime-lane pattern; the few additive changes are listed with line numbers
- Pitfalls: HIGH for the code-level ones (B1–B5, C1–C6 found in the skeleton/sources); MEDIUM for the device ones
- Device/latency/prop: LOW until the H2/H3 checkpoints

**Research date:** 2026-10-10
**Valid until:** the hackathon (2026-10-11); the license expires 2026-10-17; CameraX/ClikaRT versions are pinned so nothing else drifts
