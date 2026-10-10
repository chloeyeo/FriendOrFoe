# Quick task: dual detector (drone model + person model) — Summary

**Date:** 2026-10-10 · **Phase:** 2 follow-up (ad-hoc, user-approved)

Phone A now runs two ClikaRT models in-process on every frame: `RahulKate-173/rt-detrv2-drone-object-detection` (RT-DETR v2 R50, one class, `drone`) for drones and `ustc-community/dfine-small-coco` (D-FINE S, COCO 80) for people. Each model maps its rows through its own class map, the two lists are merged and deduplicated once, and this pair is now the default detector.

## What was built

- `detect/ClassMap.kt`: a class map per model, giving labelId -> class, the score threshold for each class, and the label table the checkpoint must have. `COCO_R18` (0 person, 4/14/33 drone, 0.5/0.28, the same as `LabelMap`), `DRONE_RAHUL` (0 -> DRONE >= 0.30, 1 label `drone`), `DFINE_PERSON` (0 -> PERSON >= 0.50, 80 labels, `person` at 0; everything else is dropped).
- `detect/ModelDetector.kt`: `LoadableDetector` (`isOpen`) and `ModelDetector` (`classMap`, `open`, `detectRaw`, `rawListener`). `ClikaRtDetector` now implements it and takes `(modelDir, classMap = COCO_R18, name)`. The hard-coded `labels[4] == "aeroplane"` assert is replaced by `classMap.labelMismatch` (it still fails loudly, and the message names the model).
- `detect/DualDetector.kt`: two `ModelDetector`s (injectable). It runs drone then person on the same frame, maps each, concatenates, sorts and runs `Dedup` once (the drone wins an overlap). It records `lastDroneMs`/`lastPersonMs` and builds `timingNote()` (`drone 7.6 s + person 1.9 s`). Name: `clikart:drone-rahul+dfine-s`.
- `DetectorConfig.Dual(droneDir, personDir)` (`dual:<drone>|<personDir>`, a malformed value falls back to `fake`), `Detectors.create`, the `Prefs` default (`filesDir/models/rtdetr_v2_drone_rahul`, `filesDir/models/dfine_small_coco`), and the launch extras `--es detector dual` / `clikart` (r18 alone) / `dual:<a>|<b>` (both dirs must be in app storage).
- `FofApplication`: brings up both models behind the existing loading note. The `runtime up` line names both models with their load and warm-up times. Each tick puts the per-model split on the strip and in the `tick` log line. With `log_raw` it logs `raw drone` / `raw person` rows (through `DebugHooks.logRawRows`, so MainActivity's own log_raw line stays untouched).
- `MainActivity` (shared, one line): the frame gate casts to `LoadableDetector`.
- Settings form/screen: a `Dual` detector kind with two directory fields, so saving settings no longer overwrites the dual config.
- Tests: `ClassMapTest` (6), `DualDetectorTest` (5, fake inner models), `DetectorConfigTest` (3), and `SettingsFormTest` +1. Full suite: 137 tests, 0 failures. `:app:assembleDebug` is green.

## Phone A (Galaxy A06, airplane mode ON, 2 runtime threads)

- Pushed to `/data/local/tmp/models/{rtdetr_v2_drone_rahul,dfine_small_coco}`, then `run-as` copied into `files/models/`. Sizes check out: 171,527,460 + 2,563 + 444 (the r18 preprocessor config) and 41,497,620 + 6,865 + 470 bytes.
- **Both models load offline**: drone load 0.8–1.2 s, warm 7.8–9.6 s; D-FINE load 0.4 s, warm 2.0–2.6 s.
- **Per frame**: drone 7.5–8.5 s, person 1.9–2.2 s, total 9.5–10.7 s (0.1 fps). It gets slower over the minutes, which is probably heat. The r18 alone was 3.5 s.
- `--ei clika_threads 4` gains nothing: the runtime clamps to the 2 performance cores (`CLIKA_RT_NUM_THREADS=4 clamped to the 2 performance cores`).
- Scene (the phone is lying facing a plain grey surface): 0 raw rows from both models, which is the expected result. The strip shows `drone 7.6 s + person 1.9 s · 9492 ms/frame · 0.1 fps`.

## Commits

- `f4cf4ca` detect: run a drone model and a person model per frame
- `79c6153` app: make the dual ClikaRT detector the default
- `9042581` [shared] MainActivity: gate frames on any loadable detector being open

## Notes / follow-ups

- At about 10 s per frame, tracking will feel sluggish. The R50 drone model takes 80% of that. Options to measure: run the drone model only every Nth frame, use a smaller analysis size (the preprocessor resizes to 640 anyway, so the main gain is the copy), or try `vulkan:0`.
- The `load X s · warm Y ms` note is replaced by the per-model split from the first tick on. The load times stay in the `runtime up` log line.
