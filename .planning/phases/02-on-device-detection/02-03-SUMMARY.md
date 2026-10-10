---
phase: 02-on-device-detection
plan: 03
subsystem: track / session
tags: [tracker, sticky-tracks, box-lock, thread-safety, tdd]
requires:
  - "02-01: TrackUi.score, IffCoordinator.setStatus/offlineProbe"
provides:
  - "Tracker.update(detections, nowMillis): wall-clock sticky windows per class (DRONE 5 s, PERSON 1.5 s); Track.lastSeenMillis"
  - "IffCoordinator.challenge(boxId): Unit. Locks a live DRONE track (BoxLock, 10 s), sets IffState.Challenged, logs `challenge · box <id>`, publishes immediately"
  - "Lock-aware tick: synthetic TrackUi at transform.toPreview(lock.rect) once the object is lost; expiry clears the lock and logs `challenge window closed · box <id>`"
  - "Thread model: inference outside synchronized(stateLock); tracker/lock/iffById inside; @Volatile preview size and lock"
  - "TrackUi.score = track.score for live tracks; the synthetic lock TrackUi carries the tapped track's last score"
affects: [02-04, 02-05, Phase 5]
tech-stack:
  added: []
  patterns:
    - "one private match(detections, nowMillis?, survives) behind two public update overloads"
    - "detect outside the lock, mutate + build UI inside synchronized(stateLock), publish via _state.update"
    - "inline ScriptedDetector(ArrayDeque<List<Detection>>) in tests"
key-files:
  created: []
  modified:
    - app/src/main/java/com/chloeyeo/friendorfoe/track/Tracker.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/track/TrackerTest.kt
    - app/src/main/java/com/chloeyeo/friendorfoe/session/IffCoordinator.kt
    - app/src/test/java/com/chloeyeo/friendorfoe/session/IffCoordinatorTest.kt
decisions:
  - "The lock rect is frozen at tap time (frame pixels); while the tracker still sees the locked id the live box is drawn with the Challenged state, and the frozen rect is used only after the object is lost"
  - "challenge() publishes tracks as `iff = iffById[id] ?: Unchallenged` rather than patching only the tapped id, so an expired-but-not-yet-ticked previous lock is cleared in the same publish"
  - "The KDoc TODO for steps 2-3 is reworded ('the signal source's challenge(req)') so `grep signalSource.challenge` stays 0 until Phase 5 actually adds the call"
metrics:
  duration: "~6 min"
  completed: 2026-10-10
  tasks: 2
  files: 4
  tests: "82 (73 before + 5 Tracker + 5 lock cases - 1 deleted NotImplementedError test)"
---

# Phase 2 Plan 03: Wall-Clock Sticky Tracks and the 10 s Box Lock Summary

Two changes keep a box attributed. Tracks are now sticky by wall clock: a drone survives 5 s without a detection and a person about 1.5 s, at any frame rate (D-09). A tap now creates a real 10 s box lock: `IffCoordinator.challenge(boxId)` locks a live drone track, turns its ring to Challenged at once, and the locked ring stays on screen at its frozen rect after both the detector and the tracker have lost the object. The lock expires on time and a second tap during the window is refused. Everything is pure Kotlin with JVM tests; the coordinator is now safe to call from both the runtime lane and the main thread.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | 816c5b5 | track: sticky tracks by wall clock (drone 5 s, person 1.5 s) |
| 2 | 6a133f9 | session: box lock on tap, lock-aware tick, thread-safe state section |

## Tests

- TrackerTest: 14. The 9 existing tests are unchanged and green. 5 are new: drone kept at 5 900 ms and dropped at 6 100 ms; person kept at 2 400 and dropped at 2 600; re-detection at 5 500 keeps the id with `misses == 0` and `lastSeenMillis == 5_500`; the wall-clock overload returns only the tracks seen in that call; and the same 4 s gap survives at 0.25 fps and at 4 fps.
- IffCoordinatorTest: 9. The 4 earlier tests stay; the `NotImplementedError` test is deleted. The 5 lock cases: Challenged is published without a tick and the event line is written; the lock survives 6 s after the tracker drops the box, mapped to `BoxF(20, 20, 100, 80)` at scale 2 with score 0.6 kept; the lock expires at 10 001 ms (lock null, no TrackUi, `challenge window closed` logged); a second tap is refused; a person id or unknown id is ignored.
- RED first for both tasks: Task 1 failed to compile (no 2-argument `update`), and Task 2 had 5 of 9 tests failing against the stub. Then GREEN.
- `:app:compileDebugKotlin :app:testDebugUnitTest --offline -q`: 82 tests, 0 failures, 0 errors. DemoScenarioTest has 4 tests, all green.
- Acceptance greps: `NotImplementedError` occurs 0 times in both coordinator files; `signalSource.challenge`, `ReChecks.check` and `EventClient(` occur 0 times in IffCoordinator; `import android` occurs 0 times in Tracker.kt. `git grep -n "CLIKA1-[A-Za-z0-9]"` printed nothing before each commit.

## Deviations from Plan

### Execution note applied
- `TrackUi.score = track.score` for every live track. The coordinator keeps the tapped track's last score in a private `lockScore`: it is set on the tap and refreshed while the locked track is still seen. The synthetic lock TrackUi uses that value, which is 0f only if the score was never known.

### Small additions (within scope)
- **[Rule 1 - Bug] Stale Challenged state after an expired lock.** If a lock expires and a tap arrives before the next tick clears it, `challenge` now removes the old box's `iffById` entry before creating the new lock. Without this, the old box would keep a stale Challenged ring. Found and fixed in Task 2 (6a133f9).
- `lock` is `@Volatile` as well as `previewWidth`/`previewHeight`, so a main-thread read of `coordinator.lock` sees the lane's write.
- One extra Tracker test: fps independence (0.25 fps vs 4 fps). It directly proves the plan's done line.
- The lock-expiry test also asserts the `challenge window closed · box <id>` event line.

Otherwise none. The plan ran as written. Phase 5 scope was not started: there is no signal call, no re-checks, no verdict, no dashboard post and no `reset()`.

## Requirements

- DET-06 is marked complete on the JVM evidence that the plan's done criteria name. The tap gesture in the UI is wired in 02-04, and 02-05 checks the lock on the phone.
- DET-04 is left open for 02-05 because it needs the phone. The lock rect does go through `FrameTransform.toPreview` like every other TrackUi, and the scale-2 test covers this.

## Threat Model Coverage

- T-02-06: `synchronized(stateLock)` covers the whole post-detect section of `tick` and all of `challenge`. The inference runs outside the lock. Preview size and `lock` are `@Volatile`, and `EventLog` is self-synchronized.
- T-02-08: Only a live DRONE track id can be locked. A second tap during the window is refused, and expiry clears both the lock and its IFF state.

## Known Stubs

None added. The Phase 5 TODO for `challenge` steps 2-3 stays in the class KDoc. The expiry event line is the placeholder that Phase 5 replaces with `Verdict(UNKNOWN, "no response")`.

## Self-Check: PASSED

- All 4 modified files are committed.
- Commits 816c5b5 and 6a133f9 are in git history.
