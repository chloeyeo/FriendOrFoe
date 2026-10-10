# Phase 2: On-device detection - Context

**Gathered:** 2026-10-10
**Status:** Ready for planning
**Source:** Express path — decisions consolidated from PROJECT.md (Key Decisions, ClikaRT facts, device facts), README.md §3–§4, the Phase 2 roadmap section and GitHub issues #2–#9 (owner: Chloe, stream 1). No discuss-phase session was held: the user asked to be asked only when a choice changes the hour plan, and every choice below was already made in those documents.

<domain>
## Phase Boundary

Phone A (Samsung Galaxy A06) shows live boxes on a person and on the demo drone target at roughly 1 fps, fully on-device and offline, with the challenge-window box lock and the status strip, and the demo target is decided. HARD CHECKPOINT at H4: if the in-process ClikaRT detector does not run, flip one config value to `HttpDetector` and keep going. Requirements: DET-01, DET-02, DET-03, DET-04, DET-06, OBS-04, PIT-04. Everything about the gadget, the verdict path and UI polish belongs to Phases 3–5.

</domain>

<decisions>
## Implementation Decisions

### Camera pipeline (DET-01)
- **D-01:** One CameraX `Preview` + one `ImageAnalysis` bound in a single `UseCaseGroup` with the `PreviewView`'s `ViewPort`; `STRATEGY_KEEP_ONLY_LATEST`; `OUTPUT_IMAGE_FORMAT_RGBA_8888`; target 1280×720 (1920×1080 only if ms/frame stays under 1.5 s); back camera only; the observer Activity is locked to landscape so `rotationDegrees` is 0 (assert and log it). No `CameraController`, no `MlKitAnalyzer`.
- **D-02:** The analyzer copies `planes[0]` row by row into a tightly packed `Frame` (`rowStride` may exceed `width·4`), closes the `ImageProxy` in `finally`, and hands the frame to the runtime lane only when it is idle (busy flag; frames are dropped, never queued). Inference never runs on the camera executor or the main thread.

### ClikaRT detector (DET-02)
- **D-03:** `ClikaRtDetector` loads once per process on `RuntimeLane`: resolve the credential first (`BuildConfig.CLIKA_RT_LICENSE`, filled from `local.properties` → `clika.runtimeLicenseFile`, an Offline bundle), then `Modelverse.load(context, license)`, `ClikaRtAndroid.bridgeLogs()`, `ClikaRtAndroid.trimOnMemoryPressure(context)`, then `AutoModelForObjectDetection.fromPretrained(modelDir, VisionLoadOptions(offline = true, device = "cpu"))` and `Modelverse.pipeline("object-detection", model)` (the `ObjectDetectionPipeline` constructor is internal). The model is a process singleton, never an Activity-scoped object.
- **D-04:** The model directory is `context.filesDir/models/rtdetr_v2_r18vd` (the three snapshot files are already there on phone A: config.json, preprocessor_config.json, model.safetensors, md5 7037c890…); fall back to `getExternalFilesDir(null)/models/rtdetr_v2_r18vd`; a missing directory is shown on screen as "model dir missing", never crashes.
- **D-05:** `DetectOptions(scoreThreshold = 0.25f)` with `labels` left empty (RT-DETR refuses labels); per-class filtering happens in `LabelMap` (person ≥ 0.5, drone proxies ≥ 0.28). At load, assert `capabilities.labels[4] == "aeroplane"` (VOC spelling in the cached config) and fail loudly otherwise.
- **D-06:** Threads: set `CLIKA_RT_NUM_THREADS` via `Os.setenv` before `Modelverse.load` to the big-core count of the Helio G85 (2× Cortex-A75), measure ms/frame, and try 4 and 8 once; keep the best. Warm up on one black 640×640 frame behind the "model loading… N s" state. `device = "cpu"` only; never probe Vulkan in the demo build (139 MB library).
- **D-07:** A "model loading…" state with elapsed seconds is visible from app start until the first detection; the ClikaRT exception code name (`LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE`, …) is shown on screen on failure, never the credential text.

### Class map and thresholds (DET-03)
- **D-08:** `LabelMap` keys on `labelId`: 0 → PERSON; 4 (aeroplane), 14 (bird), 33 (kite) → DRONE; everything else dropped; `cell phone` (67) is never mapped. Overlapping boxes are de-duplicated by IoU, keeping the drone-class one.

### Tracking, box lock, overlay geometry (DET-04, DET-06)
- **D-09:** `Tracker` (IoU matching, stable ids, already in the skeleton) assigns ids per frame; a drone box stays sticky for 5 s after the detector loses it; `BoxLock` freezes the tapped box (id + rect) for the 10 s challenge window regardless of what the detector does.
- **D-10:** `FrameTransform` maps analysis-frame pixels to `PreviewView` pixels for `FILL_CENTER` (same 16:9 aspect on both use cases); it is checked on an object at the frame edge, not only the centre. `TrackUi.box` is in preview pixels (`BoxF`), as the contract says; the overlay drawing itself stays with the UI stream (the skeleton's `OverlayPainter` is enough for this phase).

### Detector selection and the H4 checkpoint (DET-05 interplay)
- **D-11:** `Detectors.create(config)` reads `Prefs.detectorConfig` (ClikaRt by default); `HttpDetector` is selectable only when `BuildConfig.ALLOW_HTTP_DETECTOR` is true (debug builds). If criterion 1 is not met on the phone by H4, the config value flips to `HttpDetector` against the laptop `serve --offline` and the phase continues; the five-step on-device checklist (license code name → model files/path → RGBA stride/channel dump vs the CLI → label ids → threshold) is re-run in the Phase 6 buffer.

### Status strip (OBS-04)
- **D-12:** The strip shows detector name, ms/frame, fps, model-ready state and an OFFLINE badge when airplane mode is on (`Settings.Global.AIRPLANE_MODE_ON`) and no network is validated (`NET_CAPABILITY_VALIDATED` false); the gadget dot stays grey until Phase 5.

### Demo target decision (PIT-04)
- **D-13:** At H3 test three props at the demo distance (0.5–0.7 m for the prop, 2–3 m for the person): a second phone or tablet with a full-screen quadcopter image at 100 % brightness, a printed A3/A4 quadcopter photo, a toy quadcopter. Record which one RT-DETR boxes as aeroplane/bird/kite at score ≥ 0.25 with the box surviving occlusion, plus the score and distance, in README. If none works: change the prop first; only then promote DET-09 (OWLv2, benched first) and cut EVL-01/02 first.

### Device facts that constrain this phase
- **D-14:** Phone A is a Galaxy A06 (SM-A065F, Android 16, Helio G85: 2× A75 @2.0 GHz + 6× A55, Mali-G52, 3.7 GB RAM with ~800 MB free at rest, single USB-C port, USB host supported). Expect CPU inference closer to 0.5 fps than 1 fps before tuning; if ms/frame exceeds 1.5 s, drop the analysis resolution to 960×540 before touching anything else. Keep memory low: one RGBA copy per frame, no Vulkan library load.

### Verification approach
- **D-15:** Pure logic (LabelMap, Tracker, FrameTransform, frame copy) is covered by `:app:testDebugUnitTest`. Detector parity is checked with a static-image harness: a bundled test JPEG through `ClikaRtDetector` must give the same boxes as `clikart-cli … detect` on the laptop. Everything that needs the phone (license on device, live boxes, airplane mode, edge-of-frame mapping, prop test) is a human checkpoint with the phone unlocked and in airplane mode.

### Claude's Discretion
- Exact thread count, analysis resolution and warm-up details within the bounds above; how the "model loading… N s" state is rendered; where the parity harness lives (a debug-only screen or an instrumented test); the sticky-box timing implementation; whether the external files dir fallback is kept.

### Informational (not tracked as plan items)
- **UI contract:** no UI-SPEC is produced for this phase by decision: the visual design of rings, tags and the strip belongs to the design track (issues #37–#39) and the UI stream (#13, #14); Phase 2 only has to deliver correct geometry and the state fields through the skeleton's `CameraScreen`.
- **Security:** the credential never appears in logs, screenshots or the repo (read from `BuildConfig`, masked everywhere); model files stay in app-private storage; no network access is added in this phase.

</decisions>

<specifics>
## Specific Ideas

- The first thing a judge sees is `person` and `drone · ?` boxes sitting on the real objects; the box must survive the prop being briefly occluded (that is what the lock and the sticky box are for in Phase 5).
- Two-minute H2 sanity check: dump one analysis frame of a red object to PNG, inspect the channel order, run the same PNG through `clikart-cli … detect`; identical boxes mean the RGBA path is faithful.
- Status strip numbers are the Technical Execution evidence for the README later (ms/frame, fps, load time).

</specifics>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Contracts and skeleton
- `README.md` §3 — package map, the three contracts (`Detector`, `Frame`, `Detection`, `BoxF`; `ObserverUiState`, `TrackUi`, `StatusUi`), what exists in the skeleton
- `app/src/main/java/com/chloeyeo/friendorfoe/detect/` — `Detector.kt`, `Frame.kt`, `BoxF.kt`, `LabelMap.kt`, `DetectorConfig.kt`, `Detectors.kt`, `ClikaRtDetector.kt` (stub with the verified call sequence), `HttpDetector.kt` (stub), `FakeDetector.kt`
- `app/src/main/java/com/chloeyeo/friendorfoe/camera/` — `CameraController.kt` (stub), `FrameTransform.kt`
- `app/src/main/java/com/chloeyeo/friendorfoe/track/` — `Tracker.kt`, `BoxLock.kt`
- `app/src/main/java/com/chloeyeo/friendorfoe/session/IffCoordinator.kt` — `tick(frame)` already forwards detections into tracks
- `app/src/main/java/com/chloeyeo/friendorfoe/app/` — `FofApplication.kt` (`loadRuntime` stub), `RuntimeLane.kt`, `Prefs.kt`
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` — BuildConfig fields, permissions, landscape

### ClikaRT facts (verified locally)
- `.planning/PROJECT.md` — "ClikaRT facts verified locally on 2026-10-10" and "Demo topology" (device facts)
- `.planning/research/STACK.md` — ClikaRT load/detect snippets, CameraX RGBA handling, versions
- `.planning/research/PITFALLS.md` — #1–#17 (license, model push, RGBA stride, rotation, mapping, resolution, prop test), #44 (HttpDetector guard)
- `.planning/research/ARCHITECTURE.md` — runtime lane pattern, coordinate systems, request flow
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/{Auto.kt,VisionModel.kt,Pipelines.kt,Types.kt,Modelverse.kt}` and `androidMain/io/clika/runtime/ClikaRtAndroid.kt` — the binding's real API
- `C:/Users/C/clika/reference/examples-0.6.4/android/shared/src/main/kotlin/io/clika/examples/android/shared/{SampleRuntime.kt,RuntimeLane.kt,LicenseStore.kt}` — the official bring-up pattern

### Requirements and tracking
- `.planning/REQUIREMENTS.md` — DET-01, DET-02, DET-03, DET-04, DET-06, OBS-04, PIT-04
- GitHub issues #2 (license on the phone), #3 (model on the phone — done), #4, #5, #6, #7, #8, #9 — acceptance criteria per deliverable

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `FakeDetector` + `DemoScenario` keep the UI working while the real detector is wired; `Detectors.create` is the single swap point.
- `Tracker` (IoU, stable ids) and `FrameTransform` (FILL_CENTER) exist with unit tests; `BoxLock` is a pure data class.
- `RuntimeLane` (single-thread executor) and `FofApplication.loadRuntime(license)` stub mark where ClikaRT bring-up goes.
- `LabelMap` already implements the labelId map and thresholds with tests.

### Established Patterns
- Pure Kotlin types (`BoxF`, `Frame`) instead of Android graphics classes so `:app:testDebugUnitTest` runs without stubs.
- Every stub throws `NotImplementedError("stream 1")` with the intended call sequence in KDoc; replace the body, keep the signature.
- Secrets only via `BuildConfig` from `local.properties`; `*.license` and `local.properties` are gitignored.

### Integration Points
- `IffCoordinator.tick(frame)` consumes `Detector.detect(frame)` output; `CameraController` must feed it frames and the status numbers (`StatusUi`).
- `MainActivity` → `CameraScreen` renders `ObserverUiState`; the preview surface (`AndroidView { PreviewView }`) is added under the overlay `Canvas` in this phase.

</code_context>

<deferred>
## Deferred Ideas

- Audio alert and bearing banner (DET-08) — Phase 5.
- OWLv2 zero-shot option (DET-09) — Phase 6, bench first; promoted only if PIT-04 fails.
- Eval set and `run_eval.py` (EVL-01/02) — Phase 6; the static-image parity harness from this phase is its seed.
- Gadget dot in the status strip and the whole signal path — Phases 4–5.

</deferred>

---

*Phase: 02-on-device-detection*
*Context gathered: 2026-10-10 via express path (PROJECT.md, README.md, roadmap, issues)*
