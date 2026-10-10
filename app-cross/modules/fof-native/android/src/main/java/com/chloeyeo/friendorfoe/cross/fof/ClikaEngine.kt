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
 * 2. `AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions(offline = true, device = "cpu"))`, the
 *    closed-set + label-table assert, `Modelverse.pipeline(Task.OBJECT_DETECTION, model)`, a 640x640 warm-up.
 * 3. Per frame `pipeline(ImageInput.Rgba8888(rgba, w, h), DetectOptions(maxResults = 20, scoreThreshold = 0.25f))`;
 *    the class map, thresholds and de-dup run in TypeScript (`src/detect/dedup.ts`), like `RawDetections` does.
 *
 * Every call blocks; all of them run on [lane], the single runtime thread that is also the camera analyzer.
 * The license text is only ever handed to `Modelverse.load`; it is never logged, stored or sent to JavaScript.
 */
object ClikaEngine {
  private const val TAG = "FoF"
  const val MODEL_NAME = "rtdetr_v2_r18vd"
  private val REQUIRED = listOf("config.json", "model.safetensors", "preprocessor_config.json")

  /** The runtime lane: ClikaRT load/detect/close and the camera analyzer share this one thread. */
  val lane: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "fof-runtime-lane") }

  @Volatile private var runtimeUp = false
  @Volatile private var pipeline: ObjectDetectionPipeline? = null
  @Volatile private var openedDir: String? = null

  val isOpen: Boolean get() = pipeline != null

  fun <T> onLane(block: () -> T): T = lane.submit(Callable { block() }).get()

  /** `<externalFilesDir>/models/rtdetr_v2_r18vd` when the snapshot was pushed there, else under `filesDir`. */
  fun defaultModelDir(context: Context): String {
    val external = context.getExternalFilesDir(null)?.let { File(it, "models/$MODEL_NAME") }
    if (external != null && external.isDirectory) return external.path
    return File(context.filesDir, "models/$MODEL_NAME").path
  }

  /** Brings the runtime up and opens the model; returns load/warm-up milliseconds. Lane only. */
  fun open(context: Context, modelDir: String, threads: Int): Map<String, Any> {
    val dir = File(modelDir)
    if (pipeline != null && openedDir == dir.absolutePath) return mapOf("loadMs" to 0L, "warmupMs" to 0L, "threads" to threads)
    close()
    checkModelFiles(dir)
    val t0 = System.nanoTime()
    startRuntime(context, threads)
    val model = AutoModelForObjectDetection.fromPretrained(dir.absolutePath, VisionLoadOptions(offline = true, device = "cpu"))
    val caps = model.capabilities
    check(VisionTask.DETECT in caps.visionTasks && !caps.openVocabulary) { "not a closed-set detector" }
    check(caps.labels.size == 80 && caps.labels[0] == "person" && caps.labels[4] == "aeroplane") {
      "unexpected label table: ${caps.labels.take(5)}"
    }
    val p = Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline
    val t1 = System.nanoTime()
    p(ImageInput.Rgba8888(ByteArray(640 * 640 * 4), 640, 640), options())
    val t2 = System.nanoTime()
    pipeline = p
    openedDir = dir.absolutePath
    val loadMs = (t1 - t0) / 1_000_000
    val warmupMs = (t2 - t1) / 1_000_000
    Log.i(TAG, "runtime up: load $loadMs ms warm $warmupMs ms threads $threads")
    return mapOf("loadMs" to loadMs, "warmupMs" to warmupMs, "threads" to threads)
  }

  /** Raw rows of one tightly packed RGBA frame, score-descending, in frame pixels. Lane only. */
  fun detect(rgba: ByteArray, width: Int, height: Int): List<Map<String, Any>> {
    val p = pipeline ?: throw IllegalStateException("model not open")
    return p(ImageInput.Rgba8888(rgba, width, height), options()).map { d ->
      mapOf(
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

  fun close() {
    pipeline?.close()
    pipeline = null
    openedDir = null
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
