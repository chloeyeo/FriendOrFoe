---
phase: 2
slug: on-device-detection
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-10-10
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `02-RESEARCH.md` § Validation Architecture (verified against the skeleton on 2026-10-10: 50 JVM tests green).
> Task IDs assigned by the planner on 2026-10-10 (plans `02-01` … `02-06`); IDs are `02-<plan>-<task>`.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 (`testImplementation(libs.junit)`), real `org.json` on the test classpath; no Robolectric, no mockito |
| **Config file** | none — AGP defaults in `app/build.gradle.kts` |
| **Quick run command** | `./gradlew :app:testDebugUnitTest --offline -q` |
| **Full suite command** | `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline` |
| **Estimated runtime** | ~60–120 s cold, seconds warm (quick command); the full suite adds the ~300 MB APK assembly |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :app:testDebugUnitTest --offline -q`
- **After every plan wave:** Run `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`, then `./gradlew :app:installDebug` only when a phone checkpoint follows (each install pushes ~300 MB over USB)
- **Before `/gsd:verify-work`:** Full suite must be green and the five phone checkpoints (license in airplane mode, live boxes at ≈1 fps, parity vs `clikart-cli`, edge-of-frame mapping, prop decision) logged
- **Max feedback latency:** 120 seconds

---

## Wave 0 note (Kotlin)

A Kotlin test file that references a class which does not exist yet fails compilation for the whole `testDebugUnitTest` task and would break `main` for the two teammates pulling it. The Wave 0 test files are therefore created **inside the task that creates the class they test** (RED = compile error or failing assertion → implement → GREEN → one commit), never as a separate red commit. Plan waves start at 1; "Wave 0" below names the scaffold, not a plan wave.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | OBS-04 | T-02-01 | `modelNote` carries code names only, never the credential | unit | `./gradlew :app:testDebugUnitTest --tests "*IffCoordinatorTest" --offline` (setStatus publishes modelNote; offlineProbe lands in status.offline) | ✅ file, ❌ cases (W0) | ⬜ pending |
| 02-01-02 | 01 | 1 | OBS-04 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*OfflineRuleTest" --offline` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 1 | DET-02 (D-11 default) | T-02-02 | fresh install selects ClikaRT; `http:` only when stored and allowed | compile | `./gradlew :app:compileDebugKotlin --offline -q` | n/a | ⬜ pending |
| 02-01-03 | 01 | 1 | DET-03 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*DedupTest" --offline` | ❌ W0 | ⬜ pending |
| 02-01-03 | 01 | 1 | DET-05 (fallback parser) | T-02-03 | malformed/out-of-range rows dropped, body never logged | unit | `./gradlew :app:testDebugUnitTest --tests "*DetectionsJsonTest" --offline` | ❌ W0 | ⬜ pending |
| 02-02-01 | 02 | 2 | DET-02 | T-02-05 | `model dir missing` messages carry file names only | unit + compile | `./gradlew :app:compileDebugKotlin --offline -q && ./gradlew :app:testDebugUnitTest --tests "*ModelFilesTest" --offline` | ❌ W0 | ⬜ pending |
| 02-02-01 | 02 | 2 | DET-03 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*LabelMapTest" --offline` (thresholds applied through `RawDetections.toDetections`) | ✅ | ⬜ pending |
| 02-02-02 | 02 | 2 | DET-05 (H4 fallback) | T-02-02, T-02-03 | cleartext only to the configured LAN host; 2 s timeout; unselectable outside debug | compile + unit | `./gradlew :app:compileDebugKotlin --offline -q && ./gradlew :app:testDebugUnitTest --tests "*DetectionsJsonTest" --offline` | n/a | ⬜ pending |
| 02-02-03 | 02 | 2 | DET-02, OBS-04 | T-02-01, T-02-04, T-02-07 | credential only passed to `Modelverse.load`; every failure → `modelNote`; parity reads assets only; dump path fixed | compile + unit | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --offline -q` | n/a | ⬜ pending |
| 02-03-01 | 03 | 2 | DET-06 (D-09) | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*TrackerTest" --offline` (drone sticky 5 s by wall clock, person 1.5 s) | ✅ file, ❌ cases (W0) | ⬜ pending |
| 02-03-02 | 03 | 2 | DET-06, DET-04 | T-02-06, T-02-08 | tick/challenge synchronised; only a live DRONE id can be locked | unit | `./gradlew :app:testDebugUnitTest --tests "*IffCoordinatorTest" --offline` (lock survives a lost detection, expires at 10 s, refuses a second tap; replaces the `assertThrows(NotImplementedError)` test) | ✅ file, ❌ cases (W0) | ⬜ pending |
| 02-04-01 | 04 | 3 | DET-01 | T-02-09 | stride/pixelStride/rotation guards | unit | `./gradlew :app:testDebugUnitTest --tests "*RgbaPackerTest" --offline` | ❌ W0 | ⬜ pending |
| 02-04-01 | 04 | 3 | DET-04 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*FrameTransformTest" --offline` (adds the 1280×720 → 1600×720 case with an edge box) | ✅ | ⬜ pending |
| 02-04-02 | 04 | 3 | DET-01 | T-02-06 | KEEP_ONLY_LATEST + busy gate; proxy closed in `finally` | compile | `./gradlew :app:compileDebugKotlin --offline -q` | n/a | ⬜ pending |
| 02-04-03 | 04 | 3 | DET-02, DET-04, OBS-04 | T-02-04, T-02-01 | extras inert outside debug; `clikart:` dir inside app storage; activity never reads the credential | full suite | `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline -q` | n/a | ⬜ pending |
| 02-05-01 | 05 | 4 | DET-01 | — | N/A | manual (phone, executor-run adb) | — (logcat `FoF` first-frame line: delivered size, `rotationDegrees`, `cropRect`, strides) | n/a | ✅ green |
| 02-05-01 | 05 | 4 | DET-02 | T-02-01 | credential never logged or shown; only the code name on screen | manual (phone) | — (status strip + `adb logcat -s ClikaRT FoF`; capture grepped for `CLIKA1`) | n/a | ✅ green (offline; online = `LICENSE_STATE_BLOCKED`, seats full) |
| 02-05-01 | 05 | 4 | DET-02 | — | N/A | manual (phone + laptop) | parity: live-frame dump `files/dumps/frame.png` vs `clikart-cli … detect` on the same PNG; `am start --es parity parity/scene.png` vs the CLI on the asset | ✅ asset (02-02-03) | ✅ green (asset); live frame open → 02-06 |
| 02-05-02 | 05 | 4 | DET-04 | — | N/A | manual (phone) | — (rings sit on an object at the left/right/top frame edge) | n/a | ✅ green (person boxes, left/right edge; top not checked) |
| 02-05-02 | 05 | 4 | OBS-04 | T-02-10 | airplane state asserted via `settings get global airplane_mode_on` | manual (phone) | — (OFFLINE badge toggles with `cmd connectivity airplane-mode enable/disable`) | n/a | ✅ green |
| 02-05-02 | 05 | 4 | DET-06 | — | N/A | manual (phone) | — (tap → Challenged ring survives a 3 s occlusion, expires at 10 s) | n/a | ⬜ deferred (no drone box on device yet; unit-covered) |
| 02-05-03 | 05 | 4 | DET-02 (H4 rule) | T-02-02 | HTTP branch recorded in README with reason and the Phase 6 re-run rule | docs + unit | `grep -q "Criterion 1 (in-process boxes in airplane mode): " README.md && ./gradlew :app:testDebugUnitTest --offline -q` | n/a | ✅ green (met; no H4 flip) |
| 02-06-01 | 06 | 5 | DET-02 (D-06/D-14) | T-02-12 | thread runs restart the process; ties go to fewer threads | manual (phone, executor-run adb) | — (`tick … ms` medians at 2/4/8 threads; 960×540 run if > 1.5 s) | n/a | ⬜ pending |
| 02-06-02 | 06 | 5 | PIT-04 | — | N/A | manual (phone) | — (three props at 0.5–0.7 m with `--ez log_raw true`; raw scores per label id; occlusion) | n/a | ⬜ pending |
| 02-06-03 | 06 | 5 | PIT-04, DET-03 | T-02-11 | drone threshold changed only within 0.25–0.30 on a measured median | docs + unit | `grep -q "### PIT-04 — demo target decision (H3)" README.md && ./gradlew :app:testDebugUnitTest --offline -q` | n/a | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

Checkpoint 1 observed (phone A, 2026-10-10): median 4489 ms/frame, 0.22 fps at the desk (0-1 tracks), 7096 ms / 0.14 fps on a crowded view (8-13 tracks); bring-up capture 3497 ms / 0.28 fps. No drone box on the DJI quadcopter or the laptop-screen photo (PIT-04 → 02-06). Online, the license is `LICENSE_STATE_BLOCKED` (all hardware seats held by other units).

Sampling continuity: the only non-automated tasks are the two `checkpoint:human-verify` tasks (02-05-02, 02-06-02); each is preceded and followed by a task with an automated command, so no three consecutive tasks lack one.

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/camera/RgbaPackerTest.kt` — DET-01 (stride, 0/90/180 rotation, pixelStride guard) → created in 02-04-01 with `RgbaPacker`
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DedupTest.kt` — DET-03 (D-08 IoU de-dup keeps the drone-class box) → 02-01-03
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DetectionsJsonTest.kt` — DET-05 fallback parser (real `org.json` already on the test classpath) → 02-01-03
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/app/OfflineRuleTest.kt` — OBS-04 rule (`offline = airplane && !validated`) → 02-01-02
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/ModelFilesTest.kt` — DET-02 snapshot check (`model dir missing`) → 02-02-01
- [ ] `TrackerTest.kt` sticky-by-time cases → 02-03-01; `IffCoordinatorTest.kt` setStatus/offline cases → 02-01-01, lock cases (and removal of the `assertThrows(NotImplementedError)` test at lines 45–49) → 02-03-02
- [ ] `app/src/main/assets/parity/scene.png` (copy of `examples-0.6.4/vision_lens/samples/scene.png`, 2851 B) for the parity harness → 02-02-03
- Framework install: none needed — existing JUnit 4 infrastructure covers all automatable phase requirements.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Preview + analysis bound, 1280×720 delivered, `rotationDegrees` 0 | DET-01 | Needs the camera and the device's stream configuration | 02-05-01: launch on phone A in landscape; read the first-frame `FoF` logcat line (size, rotation, cropRect, strides) |
| Runtime loads in airplane mode, probe passes, model opens, boxes at ≈1 fps | DET-02 | Needs the Offline license seat, the model files and the CPU on phone A | 02-05-01/02: airplane mode on; launch; "model loading… N s" then boxes; `adb logcat -s ClikaRT FoF` for the code name on failure |
| Detector parity vs the CLI | DET-02 | Needs the phone (detector) and the laptop (`clikart-cli`) | 02-05-01: dump a live frame (`--ez dump_frame true --ez log_raw true`), run the CLI on the pulled PNG, compare rows; `am start --es parity parity/scene.png` as the deterministic second check |
| Rings sit on objects at the frame edge | DET-04 | Visual check of the FILL_CENTER mapping on the real display | 02-05-02: hold an object at the left/right/top edge; the ring must stay on it |
| OFFLINE badge toggles | OBS-04 | Needs airplane mode and the validated-network state of the phone | 02-05-02: `adb shell cmd connectivity airplane-mode disable/enable`; badge changes within ~2 s when internet is available |
| Tap lock survives occlusion | DET-06 | Needs a real occlusion of a tracked prop | 02-05-02: tap, cover the prop 3 s, uncover; ring stays Challenged; expires at 10 s |
| Thread count / resolution | DET-02 | Needs the phone's CPU | 02-06-01: 2/4/8 threads with `am force-stop` between runs; 960×540 only above 1.5 s/frame |
| Prop decision | PIT-04 | Needs the physical props at demo distance | 02-06-02/03: test display prop / printed photo / toy at 0.5–0.7 m; record which is boxed as aeroplane/bird/kite with score ≥ 0.25, plus score and distance, in README section 10 |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies (the two human checkpoints are the documented exceptions)
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (each scaffold is bound to the task that creates its class)
- [x] No watch-mode flags
- [x] Feedback latency < 120s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending execution (statuses are set by plans 02-05 and 02-06)
