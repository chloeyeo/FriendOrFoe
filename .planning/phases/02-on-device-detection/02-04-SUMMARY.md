---
phase: 02-on-device-detection
plan: 04
subsystem: camera / ui / app
tags: [camerax, rgba, stride, busy-gate, preview-slot, launch-extras, main-activity]
requires:
  - "02-02: FofApplication.startRuntimeIfNeeded/runTick/runParity/runtimeLoading, DebugHooks, ClikaRtDetector.rawListener/isOpen"
  - "02-03: IffCoordinator.challenge(boxId), setPreviewSize, thread-safe tick"
provides:
  - "RgbaPacker.pack(src, width, height, rowStride, pixelStride, rotationDegrees, timestampNanos): tight, upright Frame (rotation always 0 on output)"
  - "CameraController(context, targetSize = 1280x720): start(owner, previewView, onFrame) / stop(); UseCaseGroup + ViewPort, KEEP_ONLY_LATEST RGBA, busy gate, first-frame log, firstFrameInfo"
  - "CameraScreen slot named like origin/jongho: preview: @Composable (Modifier) -> Unit, onPreviewSize: (Int, Int) -> Unit; modelNote chip"
  - "LaunchOptions.from(app, intent): debug-only extras clika_threads / analysis / parity / dump_frame / log_raw / detector"
  - "MainActivity: CAMERA permission, runtime start, D-07 loading ticker, camera -> lane -> runTick with a readiness gate, tap -> coordinator.challenge"
affects: [02-05, 02-06, Phase 5, UI stream merge]
tech-stack:
  added: []
  patterns:
    - "copy on the camera thread, infer on the lane, AtomicBoolean busy gate: at most one frame in flight, the rest dropped"
    - "frame dispatch gated on runtimeLoading == false and (ClikaRtDetector)?.isOpen != false so a load refusal keeps its code name on the strip"
    - "debug knobs as intent extras, inert unless BuildConfig.DEBUG; canonicalised path containment for clikart:<dir>"
key-files:
  created:
    - app/src/main/java/com/chloeyeo/friendorfoe/camera/RgbaPacker.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/camera/RgbaPackerTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/LaunchOptions.kt
  modified:
    - app/src/test/java/com/chloeyeo/friendorfoe/camera/FrameTransformTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/camera/CameraController.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/ui/CameraScreen.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/MainActivity.kt
decisions:
  - "CameraScreen slot uses origin/jongho's names and types (preview: @Composable (Modifier) -> Unit = {}, onPreviewSize) instead of the plan's BoxScope/onSizeChanged, so his merge needs no call-site rename; the size is reported from the overlay Canvas (same size as the Box)"
  - "MainActivity hands frames to a private onFrame gate, not app::runTick directly: frames are dropped while the runtime loads and after a ClikaRT refusal (detector not open), so 'not loaded' never overwrites the license code name"
  - "clikart:<dir> from the detector extra is accepted only if its canonical path is inside filesDir or the external files dir (prefix + separator), so '..' cannot escape"
metrics:
  duration: "~6 min"
  completed: 2026-10-10
  tasks: 3
  files: 7
  tests: "94 (82 before + 11 RgbaPackerTest + 1 FrameTransformTest)"
---

# Phase 2 Plan 04: Camera to Screen (RgbaPacker, CameraController, Preview Slot, MainActivity) Summary

The loop from camera to screen is closed. `RgbaPacker` turns a padded CameraX RGBA plane into the tight, upright `Frame` the binding needs; it is proven on the JVM, including the 90, 180 and 270 degree cases. `CameraController` binds one Preview and one RGBA ImageAnalysis (KEEP_ONLY_LATEST, 1280x720, both 16:9) in a single `UseCaseGroup` with the PreviewView's ViewPort. It copies frames on its own thread and passes at most one to the runtime lane. It logs the first frame's geometry under `FoF`. `MainActivity` asks for CAMERA, starts the runtime, counts `model loading… N s` on the strip and puts the PreviewView under the overlay through a slot named like Jongho's branch. A tap on a drone box goes to `coordinator.challenge`. The debug APK assembles (296 MB). Nothing was installed on the phone; 02-05 does that.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | a89d76f | camera: RGBA packer with stride and rotation; 1600x720 transform case |
| 2 | 872c1f7 | camera: bind preview + analysis in one group, busy gate, first-frame log |
| 3 | 54d37c2 | ui: preview slot, size report, modelNote chip |
| 3 | c90ab94 | app: LaunchOptions debug extras |
| 3 | 8d73a95 | [shared] MainActivity: camera permission, runtime start, CameraScreen wiring with the preview slot |

## Supported `am start` extras (debug builds only)

Base: `adb shell am start -S -n com.chloeyeo.friendorfoe/.MainActivity <extras>`

| Extra | Form | Effect |
|-------|------|--------|
| `clika_threads` | `--ei clika_threads 4` | `CLIKA_RT_NUM_THREADS`, clamped 1..8, default 2 (read once per process) |
| `analysis` | `--es analysis 960x540` | ImageAnalysis target size, default 1280x720; unparsable values are ignored |
| `parity` | `--es parity parity/scene.png` | runs the parity harness on that APK asset (queued behind the load job) |
| `dump_frame` | `--ez dump_frame true` | writes the next analysed frame to `filesDir/dumps/frame.png` |
| `log_raw` | `--ez log_raw true` | logs every raw ClikaRT row (`raw ...`) |
| `detector` | `--es detector fake` / `clikart` / `clikart:<dir in app storage>` / `http:http://<ip>:8000` | stores the detector pref (it persists; flip back with `--es detector clikart`); logs `detector pref -> <encoded>` |

Threads, the detector and the runtime are read once per process, so use `-S` (force-stop) to apply a new value. `http:` is accepted only when `BuildConfig.ALLOW_HTTP_DETECTOR` is set and the URL starts with `http://`. A `clikart:` dir outside app storage is logged `detector extra ignored: dir outside app storage` and dropped.

## Logcat lines for checkpoint 1 (`adb logcat -s FoF ClikaRT`)

- `camera bound target=1280x720`
- `first frame WxH rotation=R cropRect=Rect(...) rowStride=S pixelStride=4 format=1`
- `analysis aspect is not 16:9 ...` (warning, only if the delivered size is not 16:9)
- `rotation R != 0 — packer rotates; verify boxes upright` (warning, only if R != 0)
- `frame rejected: ...` (warning, if a plane fails the packer guards)
- from 02-02: `runtime up: ...` / `runtime refused: <CODE>` / `tick N ms F fps tracks=K`

## Tests

- RgbaPackerTest, 11 cases: a tight plane is byte-identical; a padded plane (rowStride 20) packs to the same 24 bytes; 180 reverses the pixel order; 180 on a padded plane equals 180 on a tight plane; 90 clockwise gives out(x, y) == src(y, h-1-x) in a 2x3 frame; 270 gives out(x, y) == src(w-1-y, x); pixelStride 1, rowStride 8, rotation 45 and a too-short plane all throw IllegalArgumentException; the source buffer's position is unchanged.
- FrameTransformTest has 7 cases. The new one is `A06 screen - 1280x720 into 1600x720`: scale 1.25, offsetX 0, offsetY -90, and the edge box (0, 0, 100, 720) maps to (0, -90, 125, 810).
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline -q`: 94 tests, 0 failures, 0 errors. `app/build/outputs/apk/debug/app-debug.apk` is 296,607,141 bytes.
- `grep -rn NotImplementedError app/src/main/java/...` finds only `net/EventClient.kt` and `signal/UsbGadgetSignalSource.kt` (streams 2/3). `git grep -n "CLIKA1-[A-Za-z0-9]"` printed nothing before each commit.

## Deviations from Plan

### Execution notes applied (orchestrator)
- **Slot names follow origin/jongho.** The slot is `preview: @Composable (Modifier) -> Unit = {}` and `onPreviewSize: (width: Int, height: Int) -> Unit = { _, _ -> }`, not the plan's `preview: (@Composable BoxScope.() -> Unit)? = null` / `onSizeChanged`. `preview(Modifier.fillMaxSize())` is the Box's first child. Because of this, the plan's literal greps (`preview?.invoke(this)`, `onSizeChanged = coordinator::setPreviewSize`) do not match. The call site is `onPreviewSize = coordinator::setPreviewSize`.
- **Rotation warning.** `logOnce` warns `rotation <deg> != 0 — packer rotates; verify boxes upright`.
- **Commit split.** Task 3 has three commits: `ui:` for CameraScreen, `app:` for LaunchOptions, and `[shared] MainActivity:` for MainActivity.kt on its own.

### Auto-fixed / added
- **[Rule 1 - Bug] Frame gate before runTick (the note from 02-02).** The camera calls `MainActivity.onFrame`, which drops the frame while `app.runtimeLoading` is true or while the ClikaRT detector is not open, and otherwise calls `app.runTick(frame)`. Without the gate, a frame that arrived during the load would call `open()` without `Modelverse.load` and show "not loaded". After a license refusal, every frame would overwrite the code name with that note. Fake and http detectors are never gated. Because of this, the literal `app::runTick` reference from the plan is not present; the key link (`runTick`) still holds. Commit 8d73a95.
- **[Rule 2 - Security] Path canonicalisation in LaunchOptions (T-02-04).** A `clikart:` dir must canonicalise to `filesDir`/`externalFilesDir` itself or to a path under it plus a separator. A plain `startsWith(filesDir.path)` would accept `/data/.../files/../../x`. Commit c90ab94.
- **[Rule 2] The packer checks the plane's length** (`capacity >= rowStride*(h-1) + w*4`), so a short HAL buffer gives a clean `frame rejected` warning instead of a `BufferUnderflowException` on the camera thread. There is an extra test case for it.
- **Analyzer body as a private `analyze(proxy, onFrame)` function** instead of an inline lambda with `return@setAnalyzer`. The behaviour is the same: `finally { proxy.close() }`, and the busy gate is released on a rejected frame and after `onFrame`.
- **CameraController KDoc** describes the output-rotation option in words, so the acceptance grep for `setOutputImageRotationEnabled` stays at 0.
- **MainActivity is 109 lines** (limit 110). The permission branch and the gate are compacted to one line each.
- The DemoCameraScreen KDoc no longer says MainActivity shows it.

## Requirements

- OBS-04 is marked complete under the orchestrator's criterion: the strip renders every field in this plan's done criteria. Those are the detector name, ms/frame, fps, the model note (`model loading… N s`, then the timing note or the code name), OFFLINE/online, the gadget state and RSSI. Caveat: the gadget state is a text chip (`gadget connected` / `no gadget`), not the green/amber/red dot. That styling comes with the UI stream's StatusStrip.
- DET-01 and DET-04 are left for 02-05, which checks them on the phone. DET-02 is also left for 02-05.

## Threat Model Coverage

- T-02-04: Every extra is inert unless `BuildConfig.DEBUG`. `clikart:` dirs must be inside app storage (canonical). `http:` needs `ALLOW_HTTP_DETECTOR` and `http://`. `parity` goes through `assets` only (02-02). `dump_frame` only sets the flag; the fixed path is in 02-02.
- T-02-06: KEEP_ONLY_LATEST, an `AtomicBoolean busy` (one frame in flight), `proxy.close()` in `finally`, one RGBA copy per accepted frame. Frames that arrive while the lane is busy are closed without a copy.
- T-02-09: The packer guards (pixelStride 4, rowStride >= w*4, plane length, rotation set), plus the first-frame log and the 16:9 and rotation warnings.
- T-02-01: MainActivity never references `BuildConfig.CLIKA_RT_LICENSE`. The strip shows only `StatusUi` notes.

## Known Stubs

None added. `CameraScreen`'s `preview` defaults to an empty composable, so the existing `@Preview`s and `DemoCameraScreen` are unchanged. If a phone draws the SurfaceView preview over the rings, the comment in MainActivity names the one-line fix (`PreviewView.ImplementationMode.COMPATIBLE`); it is not applied.

## Self-Check: PASSED

- Files present: RgbaPacker.kt, RgbaPackerTest.kt, LaunchOptions.kt, CameraController.kt, CameraScreen.kt, MainActivity.kt, FrameTransformTest.kt.
- Commits a89d76f, 872c1f7, 54d37c2, c90ab94 and 8d73a95 are in git history.
- app-debug.apk was assembled.
