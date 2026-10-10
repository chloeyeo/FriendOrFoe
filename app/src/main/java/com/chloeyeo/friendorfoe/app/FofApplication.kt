package com.chloeyeo.friendorfoe.app

import android.app.Application
import android.content.Context

/**
 * The app's process. `onCreate` stays light (prefs only); the ClikaRT runtime is brought up later, off the main
 * thread, by [loadRuntime].
 *
 * TODO(stream 1 / Chloe): [loadRuntime] — the verified bring-up order, once per process, on [RuntimeLane]
 * (sources: `clika-runtime-0.6.4-sources/androidMain`, `examples-0.6.4/android/shared/SampleRuntime.kt`):
 * 1. Credential: [license] if given, else `BuildConfig.CLIKA_RT_LICENSE` (embedded at build time from the file
 *    `local.properties` names in `clika.runtimeLicenseFile`, "" when none), else the text stored once in private
 *    storage (`LicenseStore` pattern: `filesDir/clika_rt_license`). Never log or display it. Only an **Offline**
 *    license bundle works with the network blocked; an Online key fails with `LICENSE_SERVER_UNREACHABLE`.
 * 2. `Modelverse.load(applicationContext, license)` (extension `io.clika.modelverse.load`): idempotent; pins
 *    `XDG_CACHE_HOME` to the app cache dir, sets `CLIKA_RT_LICENSE`, loads libClikaRT -> modelverse -> JNI bridge.
 *    Set `CLIKA_RT_NUM_THREADS` with `Os.setenv` before this call if the big-core count is to be used.
 * 3. `ClikaRtAndroid.bridgeLogs()` (then `adb logcat -s ClikaRT`), `ClikaRtAndroid.trimOnMemoryPressure(this)`.
 * 4. Model directory: `getExternalFilesDir(null)/models/rtdetr_v2_r18vd` holding the pushed hub snapshot
 *    (`config.json`, `model.safetensors`, `preprocessor_config.json`); the detector opens it with `offline = true`.
 * 5. Refusals are `ModelverseException`/`ClikaRtException` with code names (`LICENSE_FAILED`, `LICENSE_EXPIRED`,
 *    `LICENSE_SERVER_UNREACHABLE`, `BINDING_VERSION_MISMATCH`); branch on the code, surface it in the status strip.
 */
class FofApplication : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TODO(stream 1): ClikaRtAndroid.trimOnMemoryPressure(this) once loadRuntime has run (it registers the
        // runtime's own listener; before the runtime is loaded there is nothing to release).
    }

    /** Brings the ClikaRT runtime up (blocking, seconds); call on [RuntimeLane], never on the main thread. */
    fun loadRuntime(license: String?): Nothing =
        throw NotImplementedError("stream 1: Modelverse.load(context, license) on the runtime lane")

    companion object {
        fun from(context: Context): FofApplication = context.applicationContext as FofApplication
    }
}
