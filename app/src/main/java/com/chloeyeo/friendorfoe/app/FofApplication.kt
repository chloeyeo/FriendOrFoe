package com.chloeyeo.friendorfoe.app

import android.app.Application
import android.content.Context
import android.system.Os
import android.util.Log
import com.chloeyeo.friendorfoe.BuildConfig
import com.chloeyeo.friendorfoe.detect.ClikaRtDetector
import com.chloeyeo.friendorfoe.detect.Detectors
import com.chloeyeo.friendorfoe.detect.DualDetector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.session.IffCoordinator
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import io.clika.modelverse.Modelverse
import io.clika.modelverse.ModelverseException
import io.clika.modelverse.load
import io.clika.runtime.ClikaRtAndroid
import io.clika.runtime.ClikaRtException
import io.clika.runtime.Ops
import io.clika.runtime.Tensors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's process. `onCreate` stays light (prefs only); the ClikaRT runtime is brought up later, off the main
 * thread, by [startRuntimeIfNeeded] -> [loadRuntime] on [RuntimeLane]. The verified bring-up order, once per process
 * (sources: `clika-runtime-0.6.4-sources/androidMain`, `examples-0.6.4/android/shared/SampleRuntime.kt`,
 * `examples-0.6.4/android/hello/MainActivity.kt`):
 *
 * 1. Credential: `BuildConfig.CLIKA_RT_LICENSE`, embedded at build time from the file `local.properties` names in
 *    `clika.runtimeLicenseFile` ("" when none -> the strip says `no license in build` and nothing loads). It is
 *    only ever passed to `Modelverse.load`: never logged, displayed, stored or compared. Only an **Offline** license
 *    bundle works with the network blocked; an Online key fails with `LICENSE_SERVER_UNREACHABLE`.
 * 2. `Os.setenv("CLIKA_RT_NUM_THREADS", ...)` (read once at the first compute), then
 *    `Modelverse.load(applicationContext, license)` (extension `io.clika.modelverse.load`): idempotent, so an empty
 *    first credential sticks until process death; pins `XDG_CACHE_HOME`, loads libClikaRT -> modelverse -> JNI bridge.
 * 3. `ClikaRtAndroid.bridgeLogs()` (then `adb logcat -s ClikaRT`), `ClikaRtAndroid.trimOnMemoryPressure(this)`.
 * 4. The hello-sample tensor probe (`Ops.add` on two CPU tensors): a license refusal surfaces at the first compute,
 *    not at `load`, and through a pipeline it would be a `ModelverseException` with an EMPTY code name; the probe
 *    turns it into a clean `ClikaRtException.codeName` (`LICENSE_FAILED`, `LICENSE_EXPIRED`,
 *    `LICENSE_SERVER_UNREACHABLE`).
 * 5. Model directory: `filesDir/models/rtdetr_v2_r18vd` (D-04) holding the pushed hub snapshot (`config.json`,
 *    `model.safetensors`, `preprocessor_config.json`); [ClikaRtDetector.open] opens it with `offline = true`. The
 *    default [DualDetector] opens two such snapshots in turn, `filesDir/models/rtdetr_v2_drone_rahul` (drones) and
 *    `filesDir/models/dfine_small_coco` (people). There is
 *    deliberately no `getExternalFilesDir` fallback: the snapshot already sits in `filesDir` on phone A and a second
 *    path doubles the failure modes.
 * 6. Every refusal becomes a `StatusUi.modelNote` through [noteFor]; branch on code names, never on message text.
 */
class FofApplication : Application() {
    lateinit var prefs: Prefs
        private set

    /** The process-singleton coordinator (D-03); the activity observes it and never closes it. */
    val coordinator: IffCoordinator by lazy {
        IffCoordinator(Detectors.create(prefs.detectorConfig), FakeSignalSource(), offlineProbe = OfflineProbe(this))
    }

    /** True while the load job runs; the activity's `model loading… N s` ticker only runs while this is set. */
    @Volatile
    var runtimeLoading: Boolean = false
        private set

    private val runtimeStarted = AtomicBoolean(false)

    @Volatile
    private var tickFailed = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    // trimOnMemoryPressure is registered inside loadRuntime; nothing else to release here.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    /**
     * Brings the ClikaRT runtime up and runs the tensor-op probe (blocking, seconds); call on [RuntimeLane], never
     * on the main thread. Throws `ClikaRtException` (with a code name), `ModelverseException` or
     * `UnsatisfiedLinkError`; [startRuntimeIfNeeded] maps them through [noteFor].
     */
    fun loadRuntime(license: String?, threads: Int = 2) {
        if (!Modelverse.isLoaded) {
            Os.setenv("CLIKA_RT_NUM_THREADS", threads.toString(), true)
            Modelverse.load(applicationContext, license?.ifBlank { null })
            ClikaRtAndroid.bridgeLogs()
            ClikaRtAndroid.trimOnMemoryPressure(this)
        }
        Tensors.of(floatArrayOf(1f, 2f, 3f), longArrayOf(3)).use { a ->
            Tensors.of(floatArrayOf(10f, 20f, 30f), longArrayOf(3)).use { b ->
                Ops.add(a, b).use { sum -> check(sum.summary().isNotEmpty()) { "tensor probe returned nothing" } }
            }
        }
    }

    /**
     * Starts the one load job on [RuntimeLane] (once per process): runtime bring-up, then [ClikaRtDetector.open] (or
     * [DualDetector.open], both models in turn). Publishes `model loading…`, then `load X s · warm Y ms · N thr`
     * (dual: `drone 9.1+0.8 s · person 2.0+0.3 s · N thr`) or the refusal's note. A non-ClikaRT detector
     * (`fake`, `http:`) never loads the runtime.
     */
    fun startRuntimeIfNeeded(threads: Int = 2) {
        if (!runtimeStarted.compareAndSet(false, true)) return
        val detector = coordinator.detector
        val open: () -> String = when (detector) {
            is ClikaRtDetector -> {
                {
                    val t = detector.open()
                    Log.i(TAG, "runtime up: load ${t.loadMs} ms warm ${t.warmupMs} ms threads $threads")
                    "load ${"%.1f".format(t.loadMs / 1000.0)} s · warm ${t.warmupMs} ms · $threads thr"
                }
            }
            is DualDetector -> {
                {
                    val t = detector.open()
                    if (BuildConfig.DEBUG && DebugHooks.logRawRows) {
                        detector.drone.rawListener = { DebugHooks.logRows("raw drone", it) }
                        detector.person.rawListener = { DebugHooks.logRows("raw person", it) }
                    }
                    Log.i(
                        TAG,
                        "runtime up: ${detector.drone.name} load ${t.drone.loadMs} ms warm ${t.drone.warmupMs} ms + " +
                            "${detector.person.name} load ${t.person.loadMs} ms warm ${t.person.warmupMs} ms threads $threads",
                    )
                    "${detector.drone.classMap.tag} ${secs(t.drone.loadMs)}+${secs(t.drone.warmupMs)} · " +
                        "${detector.person.classMap.tag} ${secs(t.person.loadMs)}+${secs(t.person.warmupMs)} · $threads thr"
                }
            }
            else -> {
                coordinator.setStatus { it.copy(modelNote = "detector ${detector.name}") }
                return
            }
        }
        if (BuildConfig.CLIKA_RT_LICENSE.isBlank()) {
            coordinator.setStatus { it.copy(modelReady = false, modelNote = "no license in build") }
            return
        }
        runtimeLoading = true
        coordinator.setStatus { it.copy(modelReady = false, modelNote = "model loading…") }
        RuntimeLane.executor.execute {
            var ready = false
            val note: String = try {
                loadRuntime(BuildConfig.CLIKA_RT_LICENSE, threads)
                open().also { ready = true }
            } catch (e: Throwable) {
                Log.w(TAG, "runtime refused: ${noteFor(e)}")
                noteFor(e)
            }
            // Cleared BEFORE the final note so the activity's loading ticker cannot overwrite it.
            runtimeLoading = false
            coordinator.setStatus { it.copy(modelReady = ready, modelNote = note) }
        }
    }

    /**
     * One analysis tick on [RuntimeLane] (the camera controller's `onFrame` target in 02-04). The single error sink
     * for `ModelverseException`, `IOException` and `serve NNN`: the failure becomes a note, the lane survives and
     * the next frame retries.
     */
    fun runTick(frame: Frame) {
        if (BuildConfig.DEBUG && DebugHooks.dumpNextFrame) {
            DebugHooks.dumpNextFrame = false
            try {
                DebugHooks.dumpPng(this, frame)
            } catch (e: Exception) {
                Log.w(TAG, "dump failed: ${noteFor(e)}")
            }
        }
        try {
            coordinator.tick(frame)
            if (tickFailed) {
                tickFailed = false
                coordinator.setStatus { it.copy(modelNote = null) }
            }
            val st = coordinator.state.value
            val dual = coordinator.detector as? DualDetector
            if (dual != null) {
                // The per-model split on the strip, e.g. `drone 6.1 s + person 1.4 s`.
                val split = dual.timingNote()
                coordinator.setStatus { it.copy(modelNote = split) }
                Log.i(TAG, "tick ${st.status.msPerFrame} ms (${dual.lastDroneMs} drone + ${dual.lastPersonMs} person) " +
                    "${"%.2f".format(st.status.fps)} fps tracks=${st.tracks.size}")
            } else {
                Log.i(TAG, "tick ${st.status.msPerFrame} ms ${"%.2f".format(st.status.fps)} fps tracks=${st.tracks.size}")
            }
        } catch (e: Exception) {
            tickFailed = true
            Log.w(TAG, "tick failed: ${noteFor(e)}")
            coordinator.setStatus { it.copy(modelReady = false, modelNote = noteFor(e)) }
        }
    }

    /** Debug: the parity harness on the lane, queued behind the load job; [assetPath] names an APK asset. */
    fun runParity(assetPath: String) {
        RuntimeLane.executor.execute {
            val d = coordinator.detector
            ((d as? ClikaRtDetector) ?: ((d as? DualDetector)?.drone as? ClikaRtDetector))?.let { DebugHooks.runParity(this, assetPath, it) }
                ?: Log.w(TAG, "parity needs the clikart detector")
        }
    }

    companion object {
        private const val TAG = "FoF"

        private fun secs(ms: Long): String = "%.1f s".format(ms / 1000.0)

        fun from(context: Context): FofApplication = context.applicationContext as FofApplication

        /**
         * The strip/log text for a failure: the ClikaRT code name when there is one, else at most 80 characters of
         * the message. The credential cannot appear: the runtime never echoes it and nothing here formats it.
         */
        fun noteFor(e: Throwable): String = when (e) {
            is ClikaRtException -> e.codeName
            is ModelverseException -> e.codeName.ifEmpty { e.message.orEmpty().take(80) }
            is UnsatisfiedLinkError -> "LIBRARY_MISSING"
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }
    }
}
