package com.chloeyeo.friendorfoe.detect

/**
 * TODO(stream 1 / Chloe): the production detector — RT-DETR v2 (r18vd) in-process through the ClikaRT 0.6.4
 * Kotlin binding, fully offline. Every call below blocks; run all of them on the runtime lane
 * (`app/RuntimeLane`), never on the main thread. The call sequence was verified against
 * `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources` and the official `examples-0.6.4/android` samples:
 *
 * 1. Bring-up, once per process (done by `FofApplication.loadRuntime`, not here):
 *    `Modelverse.load(context, license)` — the Android extension `io.clika.modelverse.load`, idempotent;
 *    then `ClikaRtAndroid.bridgeLogs()` (logcat tag `ClikaRT`) and `ClikaRtAndroid.trimOnMemoryPressure(context)`.
 * 2. `val model = AutoModelForObjectDetection.fromPretrained(modelDir, VisionLoadOptions(offline = true, device = "cpu"))`
 *    — [modelDir] is the pushed hub snapshot directory (`config.json`, `model.safetensors`, `preprocessor_config.json`),
 *    e.g. `<externalFilesDir>/models/rtdetr_v2_r18vd`; a directory source ignores the hub knobs, `offline = true` is
 *    belt and braces. Loading takes seconds. Try `device = "vulkan:0"` later with a `ModelverseException` fallback.
 * 3. `val pipeline = Modelverse.pipeline("object-detection", model) as ObjectDetectionPipeline`
 *    (the pipeline constructor is `internal`; `Modelverse.pipeline(task, model)` is the door).
 * 4. Per frame: `pipeline(ImageInput.Rgba8888(frame.rgba, frame.width, frame.height), DetectOptions(scoreThreshold = 0.25f))`
 *    — the pixels must be tightly packed RGBA rows ([Frame] guarantees it). Result: `List<io.clika.modelverse.Detection>`
 *    with `label`, `labelId: Long`, `score`, `box: Box(left, top, right, bottom)` in input pixels, score-descending.
 *    `labels` must stay empty on this closed-set model (it is refused otherwise).
 * 5. Map every row through [LabelMap.accept] (0 -> PERSON, 4/14/33 -> DRONE, per-class thresholds) into our [Detection]
 *    ([BoxF] from the `Box`), keeping the order.
 * 6. [close]: `model.close()` on the lane; the application keeps the runtime loaded.
 *
 * Refusal code names to branch on (never on message text): `LICENSE_FAILED`, `LICENSE_EXPIRED`,
 * `LICENSE_SERVER_UNREACHABLE` — only an **Offline** license bundle works with the network blocked.
 */
class ClikaRtDetector(private val modelDir: String) : Detector {
    override val name: String = "clikart:rtdetr_v2_r18vd"

    override fun detect(frame: Frame): List<Detection> = throw NotImplementedError("stream 1")

    override fun close() {
        // TODO(stream 1): model.close() on the runtime lane once the model is opened in detect()/open().
    }
}
