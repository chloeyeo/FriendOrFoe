---
phase: 2
slug: on-device-detection
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-10-10
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `02-RESEARCH.md` § Validation Architecture (verified against the skeleton on 2026-10-10: 50 JVM tests green).

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

## Per-Task Verification Map

The planner assigns the task IDs; the requirement → test rows below come from `02-RESEARCH.md` and are the minimum each plan must cover.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-??-?? | ?? | 0 | DET-01 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*RgbaPackerTest" --offline` | ❌ W0 | ⬜ pending |
| 02-??-?? | ?? | ? | DET-01 | — | N/A | manual (phone) | — (logcat `FoF` first-frame line: delivered size, `rotationDegrees`, `cropRect`) | n/a | ⬜ pending |
| 02-??-?? | ?? | ? | DET-02 | T-02-?? | credential never logged or shown; only `ClikaRtException.codeName` on screen | manual (phone) | — (status strip + `adb logcat -s ClikaRT FoF`) | n/a | ⬜ pending |
| 02-??-?? | ?? | ? | DET-02 | — | N/A | manual (phone + laptop) | parity harness (`am start --es parity …`) vs `clikart-cli … detect` on `assets/parity/scene.png` | ❌ asset + harness | ⬜ pending |
| 02-??-?? | ?? | ? | DET-03 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*LabelMapTest" --offline` | ✅ | ⬜ pending |
| 02-??-?? | ?? | 0 | DET-03 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*DedupTest" --offline` | ❌ W0 | ⬜ pending |
| 02-??-?? | ?? | ? | DET-04 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*FrameTransformTest" --offline` (add the 1280×720 → 1600×720 case) | ✅ | ⬜ pending |
| 02-??-?? | ?? | ? | DET-04 | — | N/A | manual (phone) | — (rings sit on an object at the frame edge) | n/a | ⬜ pending |
| 02-??-?? | ?? | ? | DET-06 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*IffCoordinatorTest" --offline` (lock survives a lost detection, expires at 10 s, refuses a second tap; replaces the `assertThrows(NotImplementedError)` test) | ✅ file, ❌ cases | ⬜ pending |
| 02-??-?? | ?? | ? | DET-06 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*TrackerTest" --offline` (drone track sticky 5 s by wall clock) | ✅ file, ❌ cases | ⬜ pending |
| 02-??-?? | ?? | 0 | DET-05 (fallback) | T-02-?? | cleartext only to the configured LAN host; `HttpDetector` unselectable in the demo build | unit | `./gradlew :app:testDebugUnitTest --tests "*DetectionsJsonTest" --offline` | ❌ W0 | ⬜ pending |
| 02-??-?? | ?? | 0 | OBS-04 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*OfflineRuleTest" --tests "*IffCoordinatorTest" --offline` | ❌ W0 | ⬜ pending |
| 02-??-?? | ?? | ? | OBS-04 | — | N/A | manual (phone) | — (OFFLINE badge toggles with airplane mode) | n/a | ⬜ pending |
| 02-??-?? | ?? | ? | PIT-04 | — | N/A | manual (phone) | — (prop decision with score and distance recorded in README) | n/a | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/camera/RgbaPackerTest.kt` — DET-01 (stride, 0/180° rotation, pixelStride guard)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DedupTest.kt` — DET-03 (D-08 IoU de-dup keeps the drone-class box)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/detect/DetectionsJsonTest.kt` — DET-05 fallback parser (real `org.json` already on the test classpath)
- [ ] `app/src/test/java/com/chloeyeo/friendorfoe/app/OfflineRuleTest.kt` — OBS-04 rule (`offline = airplane && !validated`)
- [ ] `TrackerTest.kt` sticky-by-time cases; `IffCoordinatorTest.kt` lock cases (remove the `assertThrows(NotImplementedError)` test at lines 45–49)
- [ ] `app/src/main/assets/parity/scene.png` (copy of `examples-0.6.4/vision_lens/samples/scene.png`) for the parity harness
- Framework install: none needed — existing JUnit 4 infrastructure covers all automatable phase requirements.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Preview + analysis bound, 1280×720 delivered, `rotationDegrees` 0 | DET-01 | Needs the camera and the device's stream configuration | Launch on phone A in landscape; read the first-frame `FoF` logcat line (size, rotation, cropRect) |
| Runtime loads in airplane mode, probe passes, model opens, boxes at ≈1 fps | DET-02 | Needs the Offline license seat, the model files and the CPU on phone A | Airplane mode on; launch; "model loading… N s" then boxes; `adb logcat -s ClikaRT FoF` for the code name on failure |
| Detector parity vs the CLI | DET-02 | Needs the phone (detector) and the laptop (`clikart-cli`) | `am start --es parity …` on the phone, `clikart-cli … detect` on the same PNG on the laptop; compare rows |
| Rings sit on objects at the frame edge | DET-04 | Visual check of the FILL_CENTER mapping on the real display | Hold an object at the left/right/top edge; the ring must stay on it |
| OFFLINE badge toggles | OBS-04 | Needs airplane mode and the validated-network state of the phone | Toggle airplane mode; badge appears/disappears |
| Prop decision | PIT-04 | Needs the physical props at demo distance | Test display prop / printed photo / toy at 0.5–0.7 m; record which is boxed as aeroplane/bird/kite with score ≥ 0.25, plus score and distance, in README |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
