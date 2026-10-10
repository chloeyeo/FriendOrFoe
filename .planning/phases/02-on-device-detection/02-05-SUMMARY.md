---
phase: 02-on-device-detection
plan: 05
subsystem: device checkpoint / docs
tags: [phone-a, airplane-mode, clikart, license, offline-badge, edge-mapping, parity, checkpoint]
requires:
  - "02-04: CameraController first-frame log, MainActivity wiring, debug launch extras (demo, dump_frame, log_raw, parity)"
  - "02-02: FofApplication runtime bring-up (runtime up / runtime refused lines), parity asset"
provides:
  - "README section 10: checkpoint-1 bring-up table, visual-check table, criterion-1 verdict, license-seat open item"
  - "02-VALIDATION.md statuses for 02-05-01..03 and the observed timings note"
affects: [02-06, Phase 5, Phase 6 runbook]
tech-stack:
  added: []
  patterns:
    - "device checks run over adb (screencap, logcat, airplane/Wi-Fi toggles); captures stay in the session scratchpad, only numbers and code names go into the repo"
key-files:
  created:
    - .planning/phases/02-on-device-detection/02-05-SUMMARY.md
  modified:
    - README.md
    - .planning/phases/02-on-device-detection/02-VALIDATION.md
decisions:
  - "Criterion 1 met on the on-device path (person boxes in airplane mode at 0.22 fps); H4 flip not applied. The missing drone box is the class proxy (PIT-04 / DET-09, plan 02-06), not the runtime"
  - "Phone A must run strictly offline (airplane mode on AND Wi-Fi off; no LAN with upstream internet): online, the ClikaRT credential is LICENSE_STATE_BLOCKED because all hardware seats are held by other units; offline it is accepted again (not cached)"
  - "Edge mapping accepted on person boxes at the left/right edges; no FrameTransform / COMPATIBLE fix, because no screenshot showed an offset or hidden rings"
metrics:
  duration: "~40 min (Task 1 earlier session + ~30 min checkpoint continuation)"
  completed: 2026-10-10
  tasks: 3
  files: 2
---

# Phase 2 Plan 05: Phone Checkpoint 1 (bring-up, visual checks, verdict) Summary

In-process RT-DETR on phone A puts person boxes on people in airplane mode (about 4.5 s/frame, 0.22 fps). No drone box appeared on a real DJI quadcopter or on a laptop-screen photo. The ClikaRT credential gets `LICENSE_STATE_BLOCKED` (all hardware seats held) as soon as phone A is online.

## What was done

- **Task 1** (earlier agent, `809df0f`): install, model-dir check, bring-up in airplane mode (`runtime up: load 844 ms warm 3853 ms threads 2`), first-frame geometry (`1280x720 rotation=0 cropRect=Rect(0, 72 - 1280, 648) rowStride=5120`), median 3497 ms/frame, asset parity identical with the CLI.
- **Task 2** (checkpoint, continued over adb): Chloe reported "so it catches people alright but not the drone". The executor checked the rest using four app screenshots Chloe took on phone A (pulled to the scratchpad, not committed) and its own `screencap` captures:
  - person boxes sit on the people, including static people at the far left and far right edges;
  - rings are drawn over the preview;
  - the OFFLINE badge was seen in all three network states (airplane + Wi-Fi off → `✈ OFFLINE`; online → `ONLINE`; airplane with Wi-Fi left on → `ONLINE`, a Samsung trap);
  - tick timings: 4489 ms median (0.22 fps) at the desk, 7096 ms (0.14 fps) on a crowded view.
- **Task 3** (`50b1eae`): README section 10 visual-check table, the criterion-1 verdict, the license-seat open item and the VALIDATION statuses. Unit suite green (`:app:testDebugUnitTest`). No code fixes were needed.

## Criterion-1 verdict

`Criterion 1 (in-process boxes in airplane mode): met`. H4 flip not applied.

## Observed ms/frame and fps

| Condition | Median ms/frame | fps |
|-----------|-----------------|-----|
| Bring-up capture (Task 1, 18 ticks) | 3497 | 0.28-0.29 |
| Desk, airplane on, 0-1 tracks (12 ticks) | 4489 | 0.22 |
| Crowded hall view, 8-13 tracks, online (9 ticks) | 7096 | 0.14 |

## Fixes applied

None. No screenshot showed a mapping offset, hidden rings or upside-down boxes.

## Deviations from Plan

- **Task 2 visual steps run by the executor, not Chloe** (orchestrator's resume instructions). The evidence is Chloe's own screenshots from her session plus adb captures.
- **Network toggles:** Chloe turned airplane mode off herself during her session (17:44). The executor re-enabled airplane mode and also **disabled Wi-Fi** (`svc wifi disable`), because Samsung keeps Wi-Fi on in airplane mode. That left the phone online and the license blocked. Phone A is now in airplane mode with Wi-Fi off.
- **Edge check done with person boxes**, because no drone box exists. Top edge not checked (nothing there).
- **Not done, phone locked:** phone A locked at about 17:52 with a secure keyguard, which the executor cannot dismiss. So these were not done:
  - the `dump_frame` live-frame parity re-run;
  - the `log_raw` re-run pointed at the drone (raw rows for ids 4/14/33/67).

  Both are listed as open for checkpoint 2 (02-06).
- **Prop identity:** the orchestrator said the prop was a laptop-screen photo. Chloe's 17:43:07 screenshot shows a **real DJI-style folding quadcopter** held in hand at about 0.3-0.5 m. Both are recorded as "no box".
- **Tap lock (step 5):** deferred. No drone box on device; the logic is covered by `IffCoordinatorTest`.

## Open items for the orchestrator / Chloe

1. **License seats (blocker for any online moment):** platform.clika.io → Licenses. Release a hardware seat or raise `max_hardware` so phone A is admitted. Until then the demo must keep phone A strictly offline. Verbatim runtime line (no credential): `clikart license invalid — LICENSE_STATE_BLOCKED: this credential's hardware seats are all held by other units; release a seat or raise its max_hardware to admit this one`.
2. **PIT-04:** RT-DETR's COCO proxies did not fire on the quadcopter. Raw-row evidence still needs a `--ez log_raw true` run aimed at the drone (02-06).
3. **Live-frame parity and colour check** on a scene with people and colour (02-06).
4. **Latency:** 4.5-7 s/frame is well below the ~1 fps target. Thread and resolution runs are in 02-06.
5. **DET-04 detail:** the tag shows the label only, with no score (UI-stream rendering choice). The DET-04 text asks for "label and score".

## Requirements

- DET-01: complete (preview + one analysis stream, 1280x720, KEEP_ONLY_LATEST, first-frame line verified).
- DET-04: complete (boxes on the people at the left/right edges in the screenshots; score-in-tag gap noted above).
- DET-02: left pending. The person half works in-process offline, but the requirement text also needs drone detections and roughly 1 fps; both are open for 02-06.
- DET-06: unchanged (already complete from 02-03 unit coverage; device check deferred).
- OBS-04: unchanged (already complete; badge confirmed on device).

## Self-Check: PASSED

- FOUND: README.md section 10 rows + `Criterion 1 (in-process boxes in airplane mode): met`
- FOUND: 02-VALIDATION.md rows 02-05-01..03 set (no `⬜ pending` left in them)
- FOUND: commits 809df0f, 50b1eae
- `git grep -n "CLIKA1-[A-Za-z0-9]"`: empty
