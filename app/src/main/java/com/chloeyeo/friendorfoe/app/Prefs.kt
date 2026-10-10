package com.chloeyeo.friendorfoe.app

import android.content.Context
import android.content.SharedPreferences
import com.chloeyeo.friendorfoe.detect.DetectorConfig
import com.chloeyeo.friendorfoe.detect.LabelMap
import com.chloeyeo.friendorfoe.detect.ModelDirs
import com.chloeyeo.friendorfoe.signal.ReChecks
import java.io.File

/**
 * The app's settings (SharedPreferences). Defaults make a fresh install demo-able with no gadget:
 * the dual ClikaRT detector (drone specialist + D-FINE person model) on the pushed snapshots, demo mode on, the README's physical-layer budgets.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("fof_prefs", Context.MODE_PRIVATE)

    /** The D-04 model directory: `<filesDir>/models/rtdetr_v2_r18vd` (the snapshot is copied there on the phone). */
    private val defaultModelDir: String = File(context.filesDir, "models/rtdetr_v2_r18vd").path

    /**
     * The dual detector's default snapshots: the drone model at 224x224 input (`<filesDir>/models/rtdetr_v2_drone_rahul_224`)
     * and the person model at 320x320 (`<filesDir>/models/dfine_small_coco_320`), the same weights as the 640 dirs with
     * `preprocessor_config.json` `size` changed ([ModelDirs]). Phone A sweep (README "Speed sweep"): drone 2.3-2.7 s at
     * 320 -> 1.4-1.6 s at 224; the person model stays at 320 because 224 dropped most people in the room shots.
     */
    private val defaultDroneModelDir: String = ModelDirs.drone(context.filesDir, ModelDirs.DEFAULT_DRONE_INPUT)
    private val defaultPersonModelDir: String = ModelDirs.person(context.filesDir, ModelDirs.DEFAULT_PERSON_INPUT)

    /** The same two models at their published 640x640 input, for A/B (`--es detector dual640`). */
    private val dual640DroneModelDir: String = File(context.filesDir, "models/rtdetr_v2_drone_rahul").path
    private val dual640PersonModelDir: String = File(context.filesDir, "models/dfine_small_coco").path

    /**
     * Which detector runs; the dual ClikaRT detector by default; `clikart:` (r18 alone), `fake` or `http:` only when
     * stored. An unreadable stored
     * value still falls back to `fake` through [DetectorConfig.decode].
     */
    var detectorConfig: DetectorConfig
        get() = sp.getString(KEY_DETECTOR, null)?.let(DetectorConfig::decode) ?: defaultDualConfig
        set(value) = sp.edit().putString(KEY_DETECTOR, value.encode()).apply()

    /** This observer's id (2 bytes on the wire). */
    var challengerId: Int
        get() = sp.getInt(KEY_CHALLENGER_ID, 7)
        set(value) = sp.edit().putInt(KEY_CHALLENGER_ID, value).apply()

    /** Drone ids this observer accepts as FRIEND (comma-separated in storage). */
    var acceptedDroneIds: List<String>
        get() = (sp.getString(KEY_ACCEPTED_DRONE_IDS, null) ?: "RQ-07").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_ACCEPTED_DRONE_IDS, value.joinToString(",")).apply()

    /** Per-class score floors handed to [LabelMap.accept]. */
    var personThreshold: Float
        get() = sp.getFloat(KEY_PERSON_THRESHOLD, LabelMap.DEFAULT_PERSON_THRESHOLD)
        set(value) = sp.edit().putFloat(KEY_PERSON_THRESHOLD, value).apply()

    var droneThreshold: Float
        get() = sp.getFloat(KEY_DRONE_THRESHOLD, LabelMap.DEFAULT_DRONE_THRESHOLD)
        set(value) = sp.edit().putFloat(KEY_DRONE_THRESHOLD, value).apply()

    /** Demo mode: FakeSignalSource instead of the USB gadget, DemoScenario available on screen. */
    var demoMode: Boolean
        get() = sp.getBoolean(KEY_DEMO_MODE, true)
        set(value) = sp.edit().putBoolean(KEY_DEMO_MODE, value).apply()

    /** Round-trip budget for the `late response` re-check. */
    var rttBudgetMs: Long
        get() = sp.getLong(KEY_RTT_BUDGET_MS, 2500L)
        set(value) = sp.edit().putLong(KEY_RTT_BUDGET_MS, value).apply()

    /** RSSI floor for the `signal mismatch` re-check. */
    var rssiFloorDbm: Int
        get() = sp.getInt(KEY_RSSI_FLOOR_DBM, -70)
        set(value) = sp.edit().putInt(KEY_RSSI_FLOOR_DBM, value).apply()

    /** Strict physical checks: an answer without RSSI fails instead of being skipped. */
    var strictPhysical: Boolean
        get() = sp.getBoolean(KEY_STRICT_PHYSICAL, false)
        set(value) = sp.edit().putBoolean(KEY_STRICT_PHYSICAL, value).apply()

    /** Configured observer position for the indoor demo (no GPS fix in the venue). */
    var demoLatitude: Double
        get() = sp.getString(KEY_DEMO_LAT, null)?.toDoubleOrNull() ?: 37.5665
        set(value) = sp.edit().putString(KEY_DEMO_LAT, value.toString()).apply()

    var demoLongitude: Double
        get() = sp.getString(KEY_DEMO_LON, null)?.toDoubleOrNull() ?: 126.9780
        set(value) = sp.edit().putString(KEY_DEMO_LON, value.toString()).apply()

    var demoAltitudeM: Double
        get() = sp.getString(KEY_DEMO_ALT, null)?.toDoubleOrNull() ?: 30.0
        set(value) = sp.edit().putString(KEY_DEMO_ALT, value.toString()).apply()

    /** Configured heading of the camera in degrees (0 = north) for the position cone. */
    var demoHeadingDeg: Float
        get() = sp.getFloat(KEY_DEMO_HEADING, 0f)
        set(value) = sp.edit().putFloat(KEY_DEMO_HEADING, value).apply()

    /** The laptop dashboard, e.g. `http://192.168.1.2:8081`; empty disables event posting. */
    var dashboardBaseUrl: String
        get() = sp.getString(KEY_DASHBOARD_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_DASHBOARD_URL, value.trim()).apply()

    /** The single r18 detector (the Phase 2 baseline), for the `clikart` launch extra and the flip back from `http:`. */
    val defaultClikaRtConfig: DetectorConfig
        get() = DetectorConfig.ClikaRt(defaultModelDir)

    /** The fresh-install detector: drone specialist at 224x224 + D-FINE person model at 320x320, for the `dual` launch extra. */
    val defaultDualConfig: DetectorConfig
        get() = DetectorConfig.Dual(defaultDroneModelDir, defaultPersonModelDir)

    /** The same pair at 640x640, for the `dual640` launch extra (A/B against the 320 default). */
    val dual640Config: DetectorConfig
        get() = DetectorConfig.Dual(dual640DroneModelDir, dual640PersonModelDir)

    fun reChecksConfig(): ReChecks.Config = ReChecks.Config(rttBudgetMs, rssiFloorDbm, strictPhysical)

    private companion object {
        const val KEY_DETECTOR = "detectorConfig"
        const val KEY_CHALLENGER_ID = "challengerId"
        const val KEY_ACCEPTED_DRONE_IDS = "acceptedDroneIds"
        const val KEY_PERSON_THRESHOLD = "personThreshold"
        const val KEY_DRONE_THRESHOLD = "droneThreshold"
        const val KEY_DEMO_MODE = "demoMode"
        const val KEY_RTT_BUDGET_MS = "rttBudgetMs"
        const val KEY_RSSI_FLOOR_DBM = "rssiFloorDbm"
        const val KEY_STRICT_PHYSICAL = "strictPhysical"
        const val KEY_DEMO_LAT = "demoLatitude"
        const val KEY_DEMO_LON = "demoLongitude"
        const val KEY_DEMO_ALT = "demoAltitudeM"
        const val KEY_DEMO_HEADING = "demoHeadingDeg"
        const val KEY_DASHBOARD_URL = "dashboardBaseUrl"
    }
}
