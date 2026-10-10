package com.chloeyeo.friendorfoe.app

import android.content.Intent
import android.util.Log
import android.util.Size
import com.chloeyeo.friendorfoe.BuildConfig
import com.chloeyeo.friendorfoe.detect.DetectorConfig
import com.chloeyeo.friendorfoe.detect.DualDetector
import com.chloeyeo.friendorfoe.detect.LoadKnobs
import com.chloeyeo.friendorfoe.detect.ModelDirs
import com.chloeyeo.friendorfoe.detect.RoundScheduler
import java.io.File

/**
 * Debug-only launch knobs for the phone checkpoints, read from the activity's intent extras (`adb shell am start
 * -n com.chloeyeo.friendorfoe/.MainActivity ...`). Outside debug builds every extra is ignored (T-02-04).
 *
 * | extra | adb form | effect |
 * |---|---|---|
 * | `clika_threads` | `--ei clika_threads 4` | `CLIKA_RT_NUM_THREADS` (1..8, default 2) |
 * | `analysis` | `--es analysis 960x540` | ImageAnalysis target size (default 1280x720) |
 * | `parity` | `--es parity parity/scene.png` | run the parity harness on that APK asset |
 * | `dump_frame` | `--ez dump_frame true` | write the next analysed frame to `filesDir/dumps/frame.png` |
 * | `log_raw` | `--ez log_raw true` | log every raw ClikaRT row (`raw` for r18, `raw drone` / `raw person` for dual) |
 * | `detector` | `--es detector dual` (320 input) / `dual640` / `clikart` (r18 alone) / `fake` / `clikart:<dir>` / `dual:<droneDir>\|<personDir>` / `http:http://<ip>:8000` | stored detector pref (persists) |
 * | `motion_bench` | `--ez motion_bench true` | log the motion tracker's cost per step and per fast-forward on this CPU |
 * | `demo` | `--ez demo false` | stored demo-mode pref (persists): false shows the live camera, true the scripted scenario |
 * | `clika_device` | `--es clika_device vulkan:0` | ClikaRT device for both models, this process only (`cpu` default; a refused `vulkan:0` falls back to `cpu`) |
 * | `clika_dtype` | `--es clika_dtype float16` | weight dtype for both models, this process only (`float16` / `bfloat16`; `float32` = the checkpoint's own) |
 * | `drone_input` | `--ei drone_input 256` | dual detector, drone snapshot `files/models/rtdetr_v2_drone_rahul_<N>` (320/256/224/160), this process only |
 * | `person_input` | `--ei person_input 224` | dual detector, person snapshot `files/models/dfine_small_coco_<N>` (320/256/224), this process only |
 * | `person_model` | `--es person_model dfine_nano_coco_320` | dual detector, person snapshot `files/models/<name>` (a plain dir name), this process only |
 * | `crop_model` | `--es crop_model rtdetr_v2_drone_rahul_crop160` / `none` | crop-round drone snapshot `files/models/<name>`; `none` runs crops on the full-frame drone model; this process only |
 * | `still_frame` | `--ez still_frame true` | analyse `files/dumps/still.png` (push with `run-as`) instead of every camera frame, e.g. a drone photo for timing crop rounds |
 * | `full_every` / `person_every` / `max_crops` | `--ei full_every 4 --ei person_every 3 --ei max_crops 3` | round scheduler: a full scan every Nth round (and at least every 3 s), the person model every Nth round, crop rounds for at most N tracked drones; this process only |
 *
 * [from] must run BEFORE `app.coordinator` is first touched: the lazy singleton reads the detector pref once.
 */
data class LaunchOptions(
    val threads: Int = 2,
    val analysisSize: Size? = null,
    val parityAsset: String? = null,
    val dumpFrame: Boolean = false,
    val logRaw: Boolean = false,
) {
    companion object {
        private const val TAG = "FoF"

        fun from(app: FofApplication, intent: Intent?): LaunchOptions {
            if (!BuildConfig.DEBUG || intent == null) return LaunchOptions()
            intent.getStringExtra("detector")?.let { applyDetector(app, it) }
            DebugHooks.logRawRows = intent.getBooleanExtra("log_raw", false)
            DebugHooks.stillFrame = if (intent.getBooleanExtra("still_frame", false)) {
                DebugHooks.loadStill(app).also { Log.i(TAG, "launch extra still_frame -> ${it ?: "dumps/still.png missing or unreadable"}") }
            } else {
                null
            }
            if (intent.getBooleanExtra("motion_bench", false)) {
                // After the model load (15 s), warm (5 rounds), beside the running detector, as the motion executor runs.
                Thread({
                    Thread.sleep(15_000)
                    com.chloeyeo.friendorfoe.track.MotionBench.run(rounds = 5).forEach { Log.i(TAG, it) }
                }, "motion-bench").start()
            }
            applyLoadKnobs(app, intent)
            if (intent.hasExtra("demo")) app.prefs.demoMode = intent.getBooleanExtra("demo", true).also { Log.i(TAG, "launch extra demo=$it") }
            SignalWiring.applyLaunchExtras(app.prefs, intent) // `signal`, `fake_mode`: see SignalWiring
            return LaunchOptions(
                threads = intent.getIntExtra("clika_threads", 2).coerceIn(1, 8),
                analysisSize = intent.getStringExtra("analysis")?.let(::parseSize),
                parityAsset = intent.getStringExtra("parity")?.trim()?.takeIf { it.isNotEmpty() },
                dumpFrame = intent.getBooleanExtra("dump_frame", false),
                logRaw = intent.getBooleanExtra("log_raw", false),
            )
        }

        /**
         * `clika_device`, `clika_dtype`, `drone_input`, `person_input`: process-only (never stored), so a launch
         * without them goes back to the defaults. The input sizes override the detector pref with the dual pair.
         */
        private fun applyLoadKnobs(app: FofApplication, intent: Intent) {
            if (intent.hasExtra("clika_device") || intent.hasExtra("clika_dtype")) {
                LoadKnobs.current = LoadKnobs(
                    device = LoadKnobs.parseDevice(intent.getStringExtra("clika_device")),
                    dtype = if (intent.hasExtra("clika_dtype")) LoadKnobs.parseDtype(intent.getStringExtra("clika_dtype")) else LoadKnobs.DEFAULT_DTYPE,
                )
                Log.i(TAG, "launch extra load knobs ${LoadKnobs.current}")
            }
            val droneInput = if (intent.hasExtra("drone_input")) intent.getIntExtra("drone_input", 0) else null
            val personInput = if (intent.hasExtra("person_input")) intent.getIntExtra("person_input", 0) else null
            ModelDirs.dualFor(app.filesDir, droneInput, personInput)?.let {
                app.detectorOverride = it
                Log.i(TAG, "launch extra detector override -> ${it.encode()}")
            }
            intent.getStringExtra("person_model")?.trim()?.let { name ->
                val base = (app.detectorOverride ?: app.prefs.detectorConfig) as? DetectorConfig.Dual
                if (base != null && ModelDirs.isPlainName(name)) {
                    val parent = File(base.personDir ?: base.droneDir).parentFile
                    app.detectorOverride = base.copy(personDir = File(parent, name).path)
                    Log.i(TAG, "launch extra person_model -> ${app.detectorOverride?.encode()}")
                } else {
                    Log.w(TAG, "launch extra person_model ignored (needs the dual detector and a plain dir name)")
                }
            }
            if (intent.hasExtra("yolo_thr")) {
                DualDetector.yoloFloor = intent.getFloatExtra("yolo_thr", 0.4f).coerceIn(0.05f, 0.95f)
                Log.i(TAG, "launch extra yolo_thr -> ${DualDetector.yoloFloor}")
            }
            intent.getStringExtra("crop_model")?.trim()?.let { name ->
                when {
                    name == "none" -> ModelDirs.cropName = null
                    ModelDirs.isPlainName(name) -> ModelDirs.cropName = name
                    else -> Log.w(TAG, "launch extra crop_model ignored (not a plain dir name)")
                }
                Log.i(TAG, "launch extra crop_model -> ${ModelDirs.cropName}")
            }
            if (intent.hasExtra("full_every") || intent.hasExtra("person_every") || intent.hasExtra("max_crops")) {
                val d = RoundScheduler.Config()
                RoundScheduler.Config.current = d.copy(
                    fullEvery = intent.getIntExtra("full_every", d.fullEvery).coerceIn(1, 20),
                    personEvery = intent.getIntExtra("person_every", d.personEvery).coerceIn(1, 20),
                    maxCrops = intent.getIntExtra("max_crops", d.maxCrops).coerceIn(0, 8),
                )
                Log.i(TAG, "launch extra rounds ${RoundScheduler.Config.current}")
            }
        }

        /** `960x540` -> Size(960, 540); anything else -> null (the default size is used). */
        private fun parseSize(text: String): Size? {
            val parts = text.trim().lowercase().split('x')
            val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (parts.size == 2 && w in 1..4096 && h in 1..4096) Size(w, h) else null
        }

        private fun applyDetector(app: FofApplication, value: String) {
            val config: DetectorConfig? = when (value.trim()) {
                "fake" -> DetectorConfig.Fake
                "clikart" -> app.prefs.defaultClikaRtConfig
                "dual" -> app.prefs.defaultDualConfig
                "drone" -> app.prefs.defaultDroneOnlyConfig
                "dual640" -> app.prefs.dual640Config
                "yolo" -> app.prefs.yoloConfig()
                "yolo320" -> app.prefs.yoloConfig(320)
                "yolo416" -> app.prefs.yoloConfig(416)
                "yolo480" -> app.prefs.yoloConfig(480)
                "yolo640" -> app.prefs.yoloConfig(640)
                else -> if (value.trim().startsWith("yolo:")) {
                    val name = value.trim().removePrefix("yolo:")
                    if (ModelDirs.isPlainName(name)) app.prefs.yoloFile(name) else null.also { Log.w(TAG, "detector extra ignored: bad yolo file name") }
                } else when (val d = DetectorConfig.decode(value)) {
                    is DetectorConfig.ClikaRt ->
                        if (inAppStorage(app, d.modelDir)) d else null.also { Log.w(TAG, "detector extra ignored: dir outside app storage") }
                    is DetectorConfig.Dual ->
                        if (inAppStorage(app, d.droneDir) && (d.personDir == null || inAppStorage(app, d.personDir))) d
                        else null.also { Log.w(TAG, "detector extra ignored: dir outside app storage") }
                    is DetectorConfig.Http ->
                        if (BuildConfig.ALLOW_HTTP_DETECTOR && d.baseUrl.startsWith("http://")) d
                        else null.also { Log.w(TAG, "detector extra ignored: http not allowed or not http://") }
                    DetectorConfig.Fake -> null.also { Log.w(TAG, "detector extra ignored: unreadable") }
                }
            }
            config ?: return
            app.prefs.detectorConfig = config
            Log.i(TAG, "detector pref -> ${config.encode()}")
        }

        /** True when [dir] (canonicalised, so `..` cannot escape) lies inside `filesDir` or the external files dir. */
        private fun inAppStorage(app: FofApplication, dir: String): Boolean {
            val target = runCatching { File(dir).canonicalPath }.getOrNull() ?: return false
            val roots = listOfNotNull(app.filesDir.path, app.getExternalFilesDir(null)?.path)
            return roots.any { root ->
                val canonicalRoot = runCatching { File(root).canonicalPath }.getOrNull() ?: return@any false
                target == canonicalRoot || target.startsWith(canonicalRoot + File.separator)
            }
        }
    }
}
