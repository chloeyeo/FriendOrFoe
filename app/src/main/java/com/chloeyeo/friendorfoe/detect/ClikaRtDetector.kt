package com.chloeyeo.friendorfoe.detect

import io.clika.modelverse.AutoModelForObjectDetection
import io.clika.modelverse.DetectOptions
import io.clika.modelverse.ImageInput
import io.clika.modelverse.Modelverse
import io.clika.modelverse.ObjectDetectionPipeline
import io.clika.modelverse.Task
import io.clika.modelverse.VisionLoadOptions
import io.clika.modelverse.VisionTask
import java.io.File

/** How long [ClikaRtDetector.open] took: the snapshot load and the black-frame warm-up, in milliseconds. */
data class OpenTiming(val loadMs: Long, val warmupMs: Long)

/**
 * The production detector — RT-DETR v2 (r18vd) in-process through the ClikaRT 0.6.4 Kotlin binding, fully offline.
 * Every call below blocks; run all of them on the runtime lane (`app/RuntimeLane`), never on the main thread. The
 * call sequence was verified against `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources` and the official
 * `examples-0.6.4/android` samples:
 *
 * 1. Bring-up, once per process (done by `FofApplication.loadRuntime`, not here):
 *    `Modelverse.load(context, license)` — the Android extension `io.clika.modelverse.load`, idempotent;
 *    then `ClikaRtAndroid.bridgeLogs()` (logcat tag `ClikaRT`) and `ClikaRtAndroid.trimOnMemoryPressure(context)`.
 * 2. `val model = AutoModelForObjectDetection.fromPretrained(modelDir, VisionLoadOptions(offline = true, device = "cpu"))`
 *    — [modelDir] is the pushed hub snapshot directory (`config.json`, `model.safetensors`, `preprocessor_config.json`),
 *    `<filesDir>/models/rtdetr_v2_r18vd` (D-04); a directory source ignores the hub knobs, `offline = true` is
 *    belt and braces. Loading takes seconds. CPU only (D-06).
 * 3. `val pipeline = Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline`
 *    (the pipeline constructor is `internal`; `Modelverse.pipeline(task, model)` is the door).
 * 4. Per frame: `pipeline(ImageInput.Rgba8888(frame.rgba, frame.width, frame.height), DetectOptions(scoreThreshold = 0.25f))`
 *    — the pixels must be tightly packed RGBA rows ([Frame] guarantees it). Result: `List<io.clika.modelverse.Detection>`
 *    with `label`, `labelId: Long`, `score`, `box: Box(left, top, right, bottom)` in input pixels, score-descending.
 *    `labels` must stay empty on this closed-set model (it is refused otherwise).
 * 5. Map every row through [RawDetections.toDetections] ([LabelMap.accept]: 0 -> PERSON, 4/14/33 -> DRONE, per-class
 *    thresholds, then the D-08 [Dedup]) into our [Detection], keeping the order.
 * 6. [close]: `pipeline.close()` (closes the model) on the lane; the application keeps the runtime loaded.
 *
 * Refusal code names to branch on (never on message text): `LICENSE_FAILED`, `LICENSE_EXPIRED`,
 * `LICENSE_SERVER_UNREACHABLE` — only an **Offline** license bundle works with the network blocked.
 *
 * The binding's `io.clika.modelverse.Detection`/`Box` clash with the app's [Detection]/[BoxF]: they are referred to
 * by their fully qualified name here and `io.clika.modelverse.*` is never wildcard-imported. Constructing this class
 * touches no native code; every binding object is created inside [open].
 */
class ClikaRtDetector(private val modelDir: String) : Detector {
    override val name: String = "clikart:rtdetr_v2_r18vd"

    @Volatile
    private var pipeline: ObjectDetectionPipeline? = null

    @Volatile
    private var timing: OpenTiming? = null

    /** True once [open] succeeded and until [close]. */
    val isOpen: Boolean get() = pipeline != null

    /** Debug hook: receives every raw row list from [detectRaw] (before the class map); null by default. */
    @Volatile
    var rawListener: ((List<RawDetection>) -> Unit)? = null

    /**
     * Opens the snapshot, asserts the label table (D-05, fail loudly) and warms up on a black 640x640 frame.
     * Blocking, runtime lane only, idempotent (returns the stored timing when already open). Throws
     * `IllegalArgumentException` (`model dir missing: …`), `IllegalStateException` (wrong model) or the binding's
     * `ClikaRtException` / `ModelverseException`; `FofApplication` maps them to a note.
     */
    fun open(): OpenTiming {
        timing?.let { if (pipeline != null) return it }
        val dir = File(modelDir)
        ModelFiles.check(dir)
        val t0 = System.nanoTime()
        val model = AutoModelForObjectDetection.fromPretrained(
            dir.absolutePath,
            VisionLoadOptions(offline = true, device = "cpu"),
        )
        val caps = model.capabilities
        check(VisionTask.DETECT in caps.visionTasks && !caps.openVocabulary) { "not a closed-set detector" }
        check(caps.labels.size == 80 && caps.labels[0] == "person" && caps.labels[4] == "aeroplane") {
            "unexpected label table: ${caps.labels.take(5)}"
        }
        val p = Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline
        val t1 = System.nanoTime()
        p(ImageInput.Rgba8888(ByteArray(640 * 640 * 4), 640, 640), options()) // zero rows expected (Pitfall D2)
        val t2 = System.nanoTime()
        val t = OpenTiming((t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
        pipeline = p
        timing = t
        return t
    }

    /** Raw rows of [frame] in frame pixels, score-descending, before the class map; feeds [rawListener]. */
    fun detectRaw(frame: Frame): List<RawDetection> {
        val p = pipeline ?: run { open(); pipeline!! }
        val rows = p(ImageInput.Rgba8888(frame.rgba, frame.width, frame.height), options()).map { d ->
            RawDetection(d.labelId, d.score, BoxF(d.box.left, d.box.top, d.box.right, d.box.bottom), d.label)
        }
        rawListener?.invoke(rows)
        return rows
    }

    override fun detect(frame: Frame): List<Detection> = RawDetections.toDetections(detectRaw(frame))

    /** Closes the model (never during the demo, D-03); a second call is a no-op. */
    override fun close() {
        pipeline?.close()
        pipeline = null
        timing = null
    }

    /** RT-DETR is closed-set: `labels` stays empty (refused otherwise, D-05); the class map applies DET-03 after. */
    private fun options() = DetectOptions(maxResults = 20, scoreThreshold = 0.25f)
}
