---
phase: 02-on-device-detection
plan: 01
subsystem: detect / session / app
tags: [contract, offline-badge, dedup, json-parser, prefs, tdd]
requires: []
provides:
  - "StatusUi.modelNote: String? = null (shared contract)"
  - "TrackUi.score: Float = 0f (shared contract, execution note)"
  - "IffCoordinator.setStatus(update) and offlineProbe constructor parameter"
  - "OfflineRule.isOffline(airplaneOn, validated) + OfflineProbe(context)"
  - "Prefs.detectorConfig defaults to DetectorConfig.ClikaRt(<filesDir>/models/rtdetr_v2_r18vd); Prefs.defaultClikaRtConfig"
  - "RawDetection + RawDetections.toDetections (LabelMap.accept -> Dedup.apply)"
  - "Dedup.apply (IoU, drone box wins, D-08)"
  - "DetectionsJson.parse (/v1/detections body)"
affects: [02-02, 02-03, 02-04]
tech-stack:
  added: []
  patterns: ["pure object + JVM truth-table test (OfflineRule)", "injectable () -> Boolean probe", "org.json parse with JSONException -> IllegalArgumentException, bad rows skipped"]
key-files:
  created:
    - app/src/main/java/com/chloeyeo/friendorfoe/app/OfflineRule.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/OfflineProbe.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/app/OfflineRuleTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/RawDetection.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/Dedup.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/DetectionsJson.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/detect/DedupTest.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/detect/DetectionsJsonTest.kt
  modified:
    - app/src/main/java/com/chloeyeo/friendorfoe/session/ObserverUiState.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/session/IffCoordinator.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/session/IffCoordinatorTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/Prefs.kt
decisions:
  - "Dedup: a new DRONE replaces kept boxes only when every overlapping kept box is a PERSON; any other overlap drops the new box"
  - "DetectionsJson: a row that throws JSONException (missing box/score/label_id) is skipped like an out-of-range row, not thrown"
  - "TrackUi.score added in the [shared] commit per the plan's execution note"
metrics:
  duration: "~15 min"
  completed: 2026-10-10
  tasks: 3
  files: 12
  tests: "68 (50 before + 18 new)"
---

# Phase 2 Plan 01: Pure Ground for On-Device Detection Summary

Pure-Kotlin base for the H4 detection path, all of it covered by JVM tests. It adds `StatusUi.modelNote` and `TrackUi.score` to the shared contract, a coordinator `setStatus` and an injected `offlineProbe` for the D-12 OFFLINE badge, and makes ClikaRT the default detector on a fresh install (D-11). It also adds the IoU de-dup where the drone box wins (D-08), and one raw-row mapping plus a `/v1/detections` parser that both detectors will share.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1A | 67d1c18 | [shared] StatusUi.modelNote: optional loader note for the strip (also TrackUi.score) |
| 1B | c6e2c95 | session: setStatus and offlineProbe on IffCoordinator |
| 2 | d0c2c52 | app: OfflineRule + OfflineProbe and the ClikaRT default in Prefs |
| 3 | 5edb7d2 | detect: raw rows, IoU de-dup and the /v1/detections parser |

The `[shared]` commit touches only `ObserverUiState.kt` and comes before the `session:` commit.

## Tests

- IffCoordinatorTest: 5 (3 existing + 2 new: setStatus without a tick; offlineProbe in status.offline, with `{ true }` as the default)
- OfflineRuleTest: 4 (the full truth table)
- DedupTest: 7 (the 5 planned cases + person-after-drone dropped + a `RawDetections.toDetections` end-to-end case)
- DetectionsJsonTest: 5
- LabelMapTest: 6 (unchanged, still green)
- Full `:app:testDebugUnitTest --offline`: 68 tests, 0 failures; `:app:compileDebugKotlin --offline` green (OfflineProbe compiles against API 36)

## Deviations from Plan

### Execution note applied
- `TrackUi.score: Float = 0f` went in as the last parameter, with a default, in the same `[shared]` commit as `StatusUi.modelNote`. Every existing `TrackUi(...)` call (DemoScenario, IffCoordinator) still compiles because they pass arguments by position and the new parameter has a default.

### Small additions (within scope)
- DedupTest has two extra cases beyond `<behavior>`: a person after an overlapping drone, and `RawDetections.toDetections` mapping, thresholds and de-dup together. There is no separate RawDetections test file.
- DetectionsJsonTest case (d) also includes a row with no `box`. It is skipped, which follows the plan's "skipped, never thrown" row rule.

Otherwise none. The plan ran as written.

## Threat Model Coverage

- T-02-03: `DetectionsJson` converts `JSONException` to `IllegalArgumentException`. It skips rows with non-finite numbers, inverted boxes, out-of-range scores or a bad shape. It never logs the body; at most 120 characters go into the exception message.
- T-02-02: `Prefs` now defaults to `ClikaRt`. `http:` is used only when explicitly stored.
- T-02-01: the `modelNote` KDoc states the rule that the note never carries the credential. The loader in 02-02 enforces it.

## Known Stubs

None added. `IffCoordinator.challenge` is still the integration TODO. Plan 02-03 replaces it, as planned.

## Self-Check: PASSED

- All 8 created files are present; the 4 modified files are committed.
- Commits 67d1c18, c6e2c95, d0c2c52 and 5edb7d2 are all in git history.
- `git grep -n "CLIKA1-[A-Za-z0-9]"` printed nothing before each commit.
