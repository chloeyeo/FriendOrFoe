package com.chloeyeo.friendorfoe.app

import android.content.Intent
import android.util.Log
import android.util.Size
import com.chloeyeo.friendorfoe.BuildConfig
import com.chloeyeo.friendorfoe.detect.DetectorConfig
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
 * | `log_raw` | `--ez log_raw true` | log every raw ClikaRT row |
 * | `detector` | `--es detector fake` / `clikart` / `clikart:<dir>` / `http:http://<ip>:8000` | stored detector pref (persists) |
 * | `demo` | `--ez demo false` | stored demo-mode pref (persists): false shows the live camera, true the scripted scenario |
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
            if (intent.hasExtra("demo")) app.prefs.demoMode = intent.getBooleanExtra("demo", true).also { Log.i(TAG, "launch extra demo=$it") }
            return LaunchOptions(
                threads = intent.getIntExtra("clika_threads", 2).coerceIn(1, 8),
                analysisSize = intent.getStringExtra("analysis")?.let(::parseSize),
                parityAsset = intent.getStringExtra("parity")?.trim()?.takeIf { it.isNotEmpty() },
                dumpFrame = intent.getBooleanExtra("dump_frame", false),
                logRaw = intent.getBooleanExtra("log_raw", false),
            )
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
                else -> when (val d = DetectorConfig.decode(value)) {
                    is DetectorConfig.ClikaRt ->
                        if (inAppStorage(app, d.modelDir)) d else null.also { Log.w(TAG, "detector extra ignored: dir outside app storage") }
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
