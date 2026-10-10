---
phase: 02-on-device-detection
plan: 02
subsystem: detect / app
tags: [clikart, rtdetr, license-probe, http-detector, debug-hooks, runtime-lane]
requires:
  - "02-01: RawDetections.toDetections, DetectionsJson.parse, IffCoordinator.setStatus/offlineProbe, StatusUi.modelNote, OfflineProbe, Prefs ClikaRt default"
provides:
  - "ClikaRtDetector: open(): OpenTiming, detectRaw(frame), detect(frame), close(), isOpen, rawListener"
  - "ModelFiles.check(dir) with 'model dir missing: <name>' messages (file names only)"
  - "HttpDetector: real OkHttp multipart POST <baseUrl>/v1/detections (image, confidence 0.25, max_detections 20), 1 s connect / 2 s call timeout"
  - "FofApplication: coordinator singleton, loadRuntime(license, threads) with the tensor-op probe, startRuntimeIfNeeded(threads), runTick(frame), runParity(asset), noteFor(e), runtimeLoading"
  - "DebugHooks: dumpNextFrame, dumpPng, assetFrame, logRows, runParity (debug only, fixed filesDir/dumps paths, APK assets only)"
  - "assets/parity/scene.png (2851 bytes, copy of examples-0.6.4/vision_lens/samples/scene.png)"
affects: [02-04, 02-05, 02-06]
tech-stack:
  added: []
  patterns:
    - "binding types by FQN / explicit imports only; no io.clika.modelverse.* wildcard (Detection/Box clash)"
    - "one load job on RuntimeLane; runtimeLoading cleared before the final setStatus"
    - "runTick as the single error sink: failure -> modelNote, next frame retries"
key-files:
  created:
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/ModelFiles.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/detect/ModelFilesTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/DebugHooks.kt
    - app/src/main/assets/parity/scene.png
  modified:
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/ClikaRtDetector.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/detect/HttpDetector.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/FofApplication.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/app/RuntimeLane.kt
decisions:
  - "No getExternalFilesDir fallback for the model dir: filesDir/models/rtdetr_v2_r18vd only (D-04, a second path doubles the failure modes)"
  - "noteFor: ClikaRtException -> codeName; ModelverseException -> codeName or message.take(80); UnsatisfiedLinkError -> LIBRARY_MISSING; else message.take(80) or class simple name"
  - "startRuntimeIfNeeded never loads the runtime for fake/http detectors (note 'detector <name>') and refuses a blank BuildConfig license with 'no license in build' (no seat consumed)"
metrics:
  duration: "~10 min"
  completed: 2026-10-10
  tasks: 3
  files: 8
  tests: "73 (68 before + 5 ModelFilesTest)"
---

# Phase 2 Plan 02: ClikaRT Detector, License-Probe Bring-up and the Real H4 Fallback Summary

`ClikaRtDetector` now opens the RT-DETR v2 r18vd snapshot from `filesDir` with `offline = true, device = "cpu"`. It fails loudly unless the label table is the 80-entry COCO table with `labels[4] == "aeroplane"`, warms up on a black 640x640 frame, and sends every row through `RawDetections.toDetections`. `FofApplication` brings the runtime up once on the lane: setenv, then load, bridgeLogs, trim, and the hello-sample `Ops.add` probe, so a license refusal comes back as a clean code name on the strip. `HttpDetector` is a real multipart POST to `clikart-cli serve` on port 8000, so the H4 flip is one stored config value. Everything compiles against the 0.6.4 AAR and the unit suite is green. Nothing was installed on the phone; 02-05 does that.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | f6b1fb6 | detect: ClikaRtDetector open/detect/detectRaw with the label-table assert |
| 2 | 63029bd | detect: HttpDetector multipart POST to serve on port 8000 |
| 3 | cc03a99 | app: runtime bring-up with the license probe, singleton coordinator, debug hooks |

## noteFor mapping (exact)

```kotlin
fun noteFor(e: Throwable): String = when (e) {
    is ClikaRtException -> e.codeName
    is ModelverseException -> e.codeName.ifEmpty { e.message.orEmpty().take(80) }
    is UnsatisfiedLinkError -> "LIBRARY_MISSING"
    else -> e.message?.take(80) ?: e.javaClass.simpleName
}
```

Strip notes this plan produces: `detector <name>` (fake/http), `no license in build`, `model loading…`, `load X.X s · warm Y ms · N thr`, or the `noteFor` text (`LICENSE_*`, `model dir missing: <file>`, `unexpected label table: [...]`, `not a closed-set detector`, `serve NNN`, IOException messages). The `modelNote` is cleared to null on the first good tick after a failed one.

## Binding signatures vs the plan

None differed. Every signature in the plan's `<interfaces>` block resolved in `:app:compileDebugKotlin --offline` against `io.clika:clika-runtime:0.6.4`. I checked these in the sources: `VisionModel.capabilities`, `Modelverse.isLoaded`, `Tensor.summary()`, `Ops.add(Tensor, Tensor)`, the `ObjectDetectionPipeline.invoke` operator, and `Pipeline.close()` closing the model.

## Tests

- ModelFilesTest: 5 cases. A complete snapshot passes. Missing `model.safetensors` names the file. A non-existent dir gives `model dir missing`. With each required file left out in turn, the message is exactly `model dir missing: <name>` and never contains the parent path. `ClikaRtDetector(...)` constructs without native code (`name`, `isOpen == false`).
- I ran it RED first (unresolved `ModelFiles`), then GREEN.
- Full `:app:compileDebugKotlin :app:testDebugUnitTest --offline -q`: 73 tests, 0 failures, 0 errors.
- `grep -rn NotImplementedError app/.../detect app/.../app` prints nothing. `git grep -n "CLIKA1-[A-Za-z0-9]"` printed nothing before each commit.

## Deviations from Plan

### Small adjustments (within scope)
- `startRuntimeIfNeeded` computes `ready`/`note` inside try/catch, then sets `runtimeLoading = false`, then calls a single final `setStatus`. The plan used a `finally`. This meets the plan's own requirement that `runtimeLoading` is cleared before the final note, and the ordering is explicit.
- `runTick` wraps `DebugHooks.dumpPng` in its own try/catch, so a failed dump never skips the tick.
- `DebugHooks.logRows` returns the lines it logged, so `runParity` writes the same lines to `dumps/parity.out` without formatting them twice.
- Task 1 has one commit, following the plan's commit convention, instead of separate RED and GREEN commits. The plan is `type: execute`, so no plan-level TDD gate applies.

Otherwise none. The plan ran as written.

## Notes for later plans

- `IffCoordinator.tick` sets `modelReady = true` on every successful tick. If a frame reaches `runTick` before the load job has finished, `ClikaRtDetector.detectRaw` falls back to `open()`. Without `Modelverse.load` that throws a "not loaded" `ModelverseException`, which becomes a note. 02-04 should feed frames only once `runtimeLoading == false` and `modelReady`, or accept that note.
- The `Detector.kt` KDoc still gives `http:192.168.1.2:8129` as its name example. It is a stale port in a contract file and out of this plan's file list. It can be fixed with any `[shared]` touch.
- REQUIREMENTS.md: DET-03 was already checked. I left DET-02 for 02-05 (live boxes on the phone) and OBS-04 for 02-04 (the strip renders there). Its traceability row already says "Complete" although the checkbox is open; I did not change it.

## Threat Model Coverage

- T-02-01: `BuildConfig.CLIKA_RT_LICENSE` is used only in `isBlank()` and as the `loadRuntime` argument. `loadRuntime` passes it to `Modelverse.load` only. No log, template or `setStatus` call references it.
- T-02-02: The `Detectors.create` `ALLOW_HTTP_DETECTOR` guard is unchanged, and the strip shows `detector http:<host:port>`.
- T-02-03: 1 s connect / 2 s call timeout. A non-2xx response gives `IllegalStateException("serve NNN")`. `DetectionsJson` enforces bounds, and `runTick` catches and adds a note. The body is never logged.
- T-02-04: Parity reads APK assets only. The dump path is fixed to `filesDir/dumps/frame.png` and `runTick` gates it on `BuildConfig.DEBUG`.
- T-02-05: `ModelFiles` messages carry file names only. Dumps live in app-private `filesDir`.
- T-02-07: Every failure in the load job and in `runTick` becomes a `modelNote`. Nothing is rethrown on the lane.

## Known Stubs

None added. `IffCoordinator.challenge` is still the integration TODO (02-03 / Phase 5). The camera feed that calls `runTick` and the activity's `startRuntimeIfNeeded` call are wired in 02-04, as planned.

## Self-Check: PASSED

- All 8 files are present (4 created, 4 modified).
- Commits f6b1fb6, 63029bd and cc03a99 are in git history.
- `app/src/main/assets/parity/scene.png` is 2851 bytes.
