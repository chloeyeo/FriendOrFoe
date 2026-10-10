package com.chloeyeo.friendorfoe.cross.fof

import android.content.Context
import android.system.Os
import android.util.Log
import io.clika.modelverse.AutoModelForObjectDetection
import io.clika.modelverse.DetectOptions
import io.clika.modelverse.ImageInput
import io.clika.modelverse.Modelverse
import io.clika.modelverse.ModelverseException
import io.clika.modelverse.ObjectDetectionPipeline
import io.clika.modelverse.Task
import io.clika.modelverse.VisionLoadOptions
import io.clika.modelverse.VisionTask
import io.clika.modelverse.load
import io.clika.runtime.ClikaRtAndroid
import io.clika.runtime.ClikaRtException
import io.clika.runtime.Ops
import io.clika.runtime.Tensors
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RT-DETR v2 (r18vd) in-process through the ClikaRT 0.6.4 Kotlin binding, fully offline — the same call sequence
 * as the native app's plan 02-02 (`ClikaRtDetector` + `FofApplication.loadRuntime`), verified there against
 * `clika-runtime-0.6.4-sources` and `examples-0.6.4/android`:
 *
 * 1. `Modelverse.load(context, license)` once per process, `bridgeLogs()`, `trimOnMemoryPressure`, then the hello
 *    sample's tensor probe — a license problem surfaces there as `ClikaRtException.codeName`
 *    (`LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE`; only an Offline bundle works offline).
 * 2. The native app's dual detector (`DualDetector`, `ClassMap`): a drone specialist (RahulKate RT-DETR v2, one
 *    label `drone`) and a person model (D-FINE S, COCO 80, label 0 `person`), each opened with
 *    `AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions(offline = true, device = "cpu"))`, its label
 *    table checked, `Modelverse.pipeline(Task.OBJECT_DETECTION, model)`, and a 640x640 warm-up. The default dirs are
 *    the `_320` snapshots (input 320x320, about 3-4x faster per frame on phone A).
 * 3. Per frame both pipelines run on `ImageInput.Rgba8888(rgba, w, h)` with `DetectOptions(maxResults = 20,
 *    scoreThreshold = 0.25f)`; rows carry their model tag, and the class map, floors and de-dup run in TypeScript
 *    (`src/detect/labelMap.ts`, `dedup.ts`).
 *
 * Every call blocks; all of them run on [lane], the single runtime thread that is also the camera analyzer.
 * The license text is only ever handed to `Modelverse.load`; it is never logged, stored or sent to JavaScript.
 */
object ClikaEngine {
  private const val TAG = "FoF"
  const val DRONE_DIR = "rtdetr_v2_drone_rahul_320"
  const val PERSON_DIR = "dfine_small_coco_320"
  private val REQUIRED = listOf("config.json", "model.safetensors", "preprocessor_config.json")

  /** One opened model: its tag (the row's `model` field), its pipeline, and its last inference time. */
  private class Opened(val tag: String, val pipeline: ObjectDetectionPipeline, @Volatile var lastMs: Long = 0L)

  /** The runtime lane: ClikaRT load/detect/close and the camera analyzer share this one thread. */
  val lane: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "fof-runtime-lane") }

  @Volatile private var runtimeUp = false
  @Volatile private var models: List<Opened> = emptyList()
  @Volatile private var openedDirs: List<String> = emptyList()

  val isOpen: Boolean get() = models.isNotEmpty()

  fun <T> onLane(block: () -> T): T = lane.submit(Callable { block() }).get()

  /** The dual detector's snapshot dirs: `<externalFilesDir>/models/<name>` when pushed there, else under `filesDir`. */
  fun defaultModelDirs(context: Context): Map<String, String> = mapOf(
    "drone" to defaultDir(context, DRONE_DIR),
    "person" to defaultDir(context, PERSON_DIR),
  )

  private fun defaultDir(context: Context, name: String): String {
    val external = context.getExternalFilesDir(null)?.let { File(it, "models/$name") }
    if (external != null && external.isDirectory) return external.path
    return File(context.filesDir, "models/$name").path
  }

  /**
   * Brings the runtime up and opens the drone model, then the person model; returns load/warm-up milliseconds.
   * A failure names the model (`drone model: …`) so the status note says which snapshot is wrong. Lane only.
   */
  fun open(context: Context, droneDir: String, personDir: String, threads: Int): Map<String, Any> {
    val dirs = listOf(File(droneDir).absolutePath, File(personDir).absolutePath)
    if (models.isNotEmpty() && openedDirs == dirs) return mapOf("loadMs" to 0L, "warmupMs" to 0L, "threads" to threads)
    close()
    val t0 = System.nanoTime()
    startRuntime(context, threads)
    val drone = openModel("clika-drone", "drone", File(droneDir)) { labels ->
      if (labels.size == 1 && labels[0] == "drone") null else "${labels.size} labels ${labels.take(3)}, expected [drone]"
    }
    val person = try {
      openModel("clika-person", "person", File(personDir)) { labels ->
        if (labels.size == 80 && labels[0] == "person") null else "${labels.size} labels, label 0 ${labels.getOrNull(0)}, expected 80 with 0 person"
      }
    } catch (e: Throwable) {
      drone.pipeline.close()
      throw e
    }
    val t1 = System.nanoTime()
    for (m in listOf(drone, person)) m.pipeline(ImageInput.Rgba8888(ByteArray(640 * 640 * 4), 640, 640), options())
    val t2 = System.nanoTime()
    models = listOf(drone, person)
    openedDirs = dirs
    val loadMs = (t1 - t0) / 1_000_000
    val warmupMs = (t2 - t1) / 1_000_000
    Log.i(TAG, "runtime up: dual ${File(droneDir).name} + ${File(personDir).name}, load $loadMs ms warm $warmupMs ms threads $threads")
    return mapOf("loadMs" to loadMs, "warmupMs" to warmupMs, "threads" to threads)
  }

  private fun openModel(tag: String, name: String, dir: File, labelMismatch: (List<String>) -> String?): Opened {
    try {
      checkModelFiles(dir)
      val model = AutoModelForObjectDetection.fromPretrained(dir.absolutePath, VisionLoadOptions(offline = true, device = "cpu"))
      val caps = model.capabilities
      check(VisionTask.DETECT in caps.visionTasks && !caps.openVocabulary) { "not a closed-set detector" }
      labelMismatch(caps.labels)?.let { mismatch ->
        runCatching { model.close() }
        throw IllegalStateException(mismatch)
      }
      return Opened(tag, Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("$name model: ${e.message}", e)
    } catch (e: IllegalStateException) {
      throw IllegalStateException("$name model: ${e.message}", e)
    }
  }

  /**
   * Raw rows of one tightly packed RGBA frame, in frame pixels, tagged with the model, from the models tagged in
   * [only] (all when null): a class switched off in Settings does not cost its model's seconds. Lane only.
   */
  fun detect(rgba: ByteArray, width: Int, height: Int, only: Set<String>? = null): List<Map<String, Any>> {
    val opened = models
    check(opened.isNotEmpty()) { "model not open" }
    val rows = ArrayList<Map<String, Any>>()
    for (m in opened) {
      if (only != null && m.tag !in only) {
        m.lastMs = 0L
        continue
      }
      val t0 = System.nanoTime()
      val found = m.pipeline(ImageInput.Rgba8888(rgba, width, height), options())
      m.lastMs = (System.nanoTime() - t0) / 1_000_000
      for (d in found) {
        rows += mapOf(
          "model" to m.tag,
          "labelId" to d.labelId.toDouble(),
          "score" to d.score.toDouble(),
          "left" to d.box.left.toDouble(),
          "top" to d.box.top.toDouble(),
          "right" to d.box.right.toDouble(),
          "bottom" to d.box.bottom.toDouble(),
          "label" to d.label,
        )
      }
    }
    return rows
  }

  /** `drone 6.1 s + person 1.4 s` for the last frame, like the native strip note. */
  fun timingNote(): String = models.joinToString(" + ") { "${it.tag.removePrefix("clika-")} ${"%.1f".format(java.util.Locale.US, it.lastMs / 1000.0)} s" }

  fun close() {
    models.forEach { it.pipeline.close() }
    models = emptyList()
    openedDirs = emptyList()
  }

  /** The status-strip note for a failure: a ClikaRT code name when there is one, never the credential. */
  fun noteFor(e: Throwable): String = when (e) {
    is ClikaRtException -> e.codeName
    is ModelverseException -> e.codeName.ifEmpty { e.message.orEmpty().take(80) }
    is UnsatisfiedLinkError -> "LIBRARY_MISSING"
    else -> e.message?.take(80) ?: e.javaClass.simpleName
  }

  private fun options() = DetectOptions(maxResults = 20, scoreThreshold = 0.25f)

  private fun startRuntime(context: Context, threads: Int) {
    if (runtimeUp) return
    val license = license(context) ?: throw IllegalStateException("no license in build")
    Os.setenv("CLIKA_RT_NUM_THREADS", threads.toString(), true)
    Modelverse.load(context.applicationContext, license)
    ClikaRtAndroid.bridgeLogs()
    ClikaRtAndroid.trimOnMemoryPressure(context.applicationContext)
    Tensors.of(floatArrayOf(1f, 2f, 3f), longArrayOf(3)).use { a ->
      Tensors.of(floatArrayOf(10f, 20f, 30f), longArrayOf(3)).use { b ->
        Ops.add(a, b).use { sum -> check(sum.summary().isNotEmpty()) }
      }
    }
    runtimeUp = true
  }

  /** BuildConfig first, then a `clika_rt_license` file in the app's private or external files dir. */
  private fun license(context: Context): String? {
    BuildConfig.CLIKA_RT_LICENSE.trim().takeIf { it.isNotEmpty() }?.let { return it }
    for (dir in listOfNotNull(context.filesDir, context.getExternalFilesDir(null))) {
      val f = File(dir, "clika_rt_license")
      if (f.isFile) f.readLines().firstOrNull { it.isNotBlank() }?.trim()?.let { return it }
    }
    return null
  }

  /** The message carries file names only, never the parent path (it reaches the screen). */
  private fun checkModelFiles(dir: File) {
    require(dir.isDirectory) { "model dir missing: ${dir.name}" }
    for (name in REQUIRED) require(File(dir, name).isFile) { "model dir missing: $name" }
  }
}
