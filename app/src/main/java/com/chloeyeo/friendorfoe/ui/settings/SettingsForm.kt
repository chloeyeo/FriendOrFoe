package com.chloeyeo.friendorfoe.ui.settings

import com.chloeyeo.friendorfoe.app.Prefs
import com.chloeyeo.friendorfoe.detect.DetectorConfig
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.signal.FakeMode
import com.chloeyeo.friendorfoe.signal.SignalChoice

/** Everything the settings screen edits, as plain values (read from and written to [Prefs]). */
data class SettingsValues(
    val detector: DetectorConfig,
    val challengerId: Int,
    val acceptedDroneIds: List<String>,
    val demoLatitude: Double,
    val demoLongitude: Double,
    val demoAltitudeM: Double,
    val demoHeadingDeg: Float,
    val personThreshold: Float,
    val droneThreshold: Float,
    val rttBudgetMs: Long,
    val rssiFloorDbm: Int,
    val strictPhysical: Boolean,
    val demoMode: Boolean,
    val dashboardBaseUrl: String,
    val signalChoice: SignalChoice = SignalChoice.AUTO,
    val fakeMode: FakeMode = FakeMode.HONEST,
) {
    fun writeTo(p: Prefs) {
        p.detectorConfig = detector
        p.challengerId = challengerId
        p.acceptedDroneIds = acceptedDroneIds
        p.demoLatitude = demoLatitude
        p.demoLongitude = demoLongitude
        p.demoAltitudeM = demoAltitudeM
        p.demoHeadingDeg = demoHeadingDeg
        p.personThreshold = personThreshold
        p.droneThreshold = droneThreshold
        p.rttBudgetMs = rttBudgetMs
        p.rssiFloorDbm = rssiFloorDbm
        p.strictPhysical = strictPhysical
        p.demoMode = demoMode
        p.dashboardBaseUrl = dashboardBaseUrl
        p.signalChoice = signalChoice
        p.fakeMode = fakeMode
    }

    companion object {
        fun readFrom(p: Prefs) = SettingsValues(
            detector = p.detectorConfig,
            challengerId = p.challengerId,
            acceptedDroneIds = p.acceptedDroneIds,
            demoLatitude = p.demoLatitude,
            demoLongitude = p.demoLongitude,
            demoAltitudeM = p.demoAltitudeM,
            demoHeadingDeg = p.demoHeadingDeg,
            personThreshold = p.personThreshold,
            droneThreshold = p.droneThreshold,
            rttBudgetMs = p.rttBudgetMs,
            rssiFloorDbm = p.rssiFloorDbm,
            strictPhysical = p.strictPhysical,
            demoMode = p.demoMode,
            dashboardBaseUrl = p.dashboardBaseUrl,
            signalChoice = p.signalChoice,
            fakeMode = p.fakeMode,
        )
    }
}

enum class DetectorKind(val label: String) { DUAL("Dual"), CLIKART("ClikaRT"), HTTP("Http"), FAKE("Fake") }

enum class Field {
    MODEL_DIR, HTTP_URL, DASHBOARD_URL, CHALLENGER_ID, DRONE_IDS, LAT, LON, ALT, HEADING,
    PERSON_THRESHOLD, DRONE_THRESHOLD, RTT_BUDGET, RSSI_FLOOR,
}

/**
 * The settings screen's draft: text exactly as typed, so a half-typed number is never lost or silently
 * replaced. [parse] either yields valid values or says what is wrong with which field. The model directory and
 * the serve URL are both kept, so switching the detector back and forth loses neither.
 */
data class SettingsForm(
    val detectorKind: DetectorKind,
    val modelDir: String,
    val httpBaseUrl: String,
    val challengerId: String,
    val acceptedDroneIds: String,
    val demoLatitude: String,
    val demoLongitude: String,
    val demoAltitudeM: String,
    val demoHeadingDeg: String,
    val personThreshold: String,
    val droneThreshold: String,
    val rttBudgetMs: String,
    val rssiFloorDbm: String,
    val strictPhysical: Boolean,
    val demoMode: Boolean,
    val dashboardBaseUrl: String,
    val droneModelDir: String = DEFAULT_DUAL_DRONE_DIR,
    val personModelDir: String = DEFAULT_DUAL_PERSON_DIR,
    val signalChoice: SignalChoice = SignalChoice.AUTO,
    val fakeMode: FakeMode = FakeMode.HONEST,
) {
    sealed interface Result {
        data class Valid(val values: SettingsValues) : Result
        data class Invalid(val errors: Map<Field, String>) : Result
    }

    fun parse(): Result {
        val errors = mutableMapOf<Field, String>()

        fun <T : Comparable<T>> num(field: Field, text: String, parse: (String) -> T?, min: T, max: T, what: String): T? {
            val v = parse(text.trim())
            if (v == null || v < min || v > max) errors[field] = what
            return v
        }

        fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

        val dir = modelDir.trim()
        val serve = httpBaseUrl.trim()
        val detector = when (detectorKind) {
            DetectorKind.FAKE -> DetectorConfig.Fake
            DetectorKind.CLIKART -> {
                if (dir.isEmpty()) errors[Field.MODEL_DIR] = "path of the pushed model snapshot"
                DetectorConfig.ClikaRt(dir)
            }
            DetectorKind.DUAL -> {
                val droneDir = droneModelDir.trim()
                val personDir = personModelDir.trim()
                if (droneDir.isEmpty() || personDir.isEmpty() || '|' in droneDir || '|' in personDir) {
                    errors[Field.MODEL_DIR] = "paths of the two pushed snapshots"
                }
                DetectorConfig.Dual(droneDir, personDir)
            }
            DetectorKind.HTTP -> {
                if (!isHttp(serve)) errors[Field.HTTP_URL] = "http://<laptop-ip>:8000"
                DetectorConfig.Http(serve)
            }
        }
        val dashboard = dashboardBaseUrl.trim()
        if (dashboard.isNotEmpty() && !isHttp(dashboard)) errors[Field.DASHBOARD_URL] = "blank, or http://<laptop-ip>:8081"

        val challenger = num(Field.CHALLENGER_ID, challengerId, String::toIntOrNull, 0, 65_535, "0 – 65535")
        val ids = acceptedDroneIds.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) errors[Field.DRONE_IDS] = "at least one droneId, comma-separated"
        val lat = num(Field.LAT, demoLatitude, String::toDoubleOrNull, -90.0, 90.0, "−90 – 90")
        val lon = num(Field.LON, demoLongitude, String::toDoubleOrNull, -180.0, 180.0, "−180 – 180")
        val alt = num(Field.ALT, demoAltitudeM, String::toDoubleOrNull, -500.0, 10_000.0, "−500 – 10000 m")
        val heading = num(Field.HEADING, demoHeadingDeg, String::toFloatOrNull, 0f, 360f, "0 – 360°")
        val person = num(Field.PERSON_THRESHOLD, personThreshold, String::toFloatOrNull, 0f, 1f, "0 – 1")
        val drone = num(Field.DRONE_THRESHOLD, droneThreshold, String::toFloatOrNull, 0f, 1f, "0 – 1")
        val rtt = num(Field.RTT_BUDGET, rttBudgetMs, String::toLongOrNull, 1L, IffState.DEFAULT_WINDOW_MS, "1 – 10000 ms")
        val rssi = num(Field.RSSI_FLOOR, rssiFloorDbm, String::toIntOrNull, -120, 0, "−120 – 0 dBm")

        if (errors.isNotEmpty()) return Result.Invalid(errors)
        return Result.Valid(
            SettingsValues(
                detector = detector,
                challengerId = challenger!!,
                acceptedDroneIds = ids,
                demoLatitude = lat!!,
                demoLongitude = lon!!,
                demoAltitudeM = alt!!,
                demoHeadingDeg = heading!! % 360f,
                personThreshold = person!!,
                droneThreshold = drone!!,
                rttBudgetMs = rtt!!,
                rssiFloorDbm = rssi!!,
                strictPhysical = strictPhysical,
                demoMode = demoMode,
                dashboardBaseUrl = dashboard,
                signalChoice = signalChoice,
                fakeMode = fakeMode,
            ),
        )
    }

    companion object {
        /** Where README §6 pushes the RT-DETR snapshot (`adb push <snapshot>/. <this>`). */
        const val DEFAULT_MODEL_DIR = "/sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd"
        const val DEFAULT_SERVE_URL = "http://192.168.43.100:8000"

        /** Fallbacks for the dual detector's two snapshot dirs (Prefs' defaults on phone A, `filesDir/models/...`). */
        const val DEFAULT_DUAL_DRONE_DIR = "/data/user/0/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_drone_rahul_320"
        const val DEFAULT_DUAL_PERSON_DIR = "/data/user/0/com.chloeyeo.friendorfoe/files/models/dfine_small_coco_320"

        fun from(v: SettingsValues) = SettingsForm(
            detectorKind = when (v.detector) {
                DetectorConfig.Fake -> DetectorKind.FAKE
                is DetectorConfig.ClikaRt -> DetectorKind.CLIKART
                is DetectorConfig.Dual -> DetectorKind.DUAL
                is DetectorConfig.Http -> DetectorKind.HTTP
            },
            modelDir = (v.detector as? DetectorConfig.ClikaRt)?.modelDir ?: DEFAULT_MODEL_DIR,
            httpBaseUrl = (v.detector as? DetectorConfig.Http)?.baseUrl ?: DEFAULT_SERVE_URL,
            challengerId = v.challengerId.toString(),
            acceptedDroneIds = v.acceptedDroneIds.joinToString(", "),
            demoLatitude = v.demoLatitude.toString(),
            demoLongitude = v.demoLongitude.toString(),
            demoAltitudeM = v.demoAltitudeM.toString(),
            demoHeadingDeg = v.demoHeadingDeg.toString(),
            personThreshold = v.personThreshold.toString(),
            droneThreshold = v.droneThreshold.toString(),
            rttBudgetMs = v.rttBudgetMs.toString(),
            rssiFloorDbm = v.rssiFloorDbm.toString(),
            strictPhysical = v.strictPhysical,
            demoMode = v.demoMode,
            dashboardBaseUrl = v.dashboardBaseUrl,
            droneModelDir = (v.detector as? DetectorConfig.Dual)?.droneDir ?: DEFAULT_DUAL_DRONE_DIR,
            personModelDir = (v.detector as? DetectorConfig.Dual)?.personDir ?: DEFAULT_DUAL_PERSON_DIR,
            signalChoice = v.signalChoice,
            fakeMode = v.fakeMode,
        )
    }
}
