package com.chloeyeo.friendorfoe.detect

import java.io.File

/**
 * The process-wide ClikaRT load choices every [ClikaRtDetector] opens with: the runtime [device] spelling (`cpu`,
 * `vulkan:0`) and the weight [dtype] (`float16`, `bfloat16`, or null for the checkpoint's own), both handed to
 * `VisionLoadOptions`. The debug launch extras `clika_device` / `clika_dtype` set [current] once per process, before
 * the detector opens; release builds keep the defaults.
 */
data class LoadKnobs(val device: String = DEFAULT_DEVICE, val dtype: String? = DEFAULT_DTYPE) {
    companion object {
        const val DEFAULT_DEVICE = "cpu"
        val DEFAULT_DTYPE: String? = null

        val DEVICES = listOf("cpu", "vulkan:0")
        val DTYPES = listOf("float16", "bfloat16")

        /** Read by [ClikaRtDetector.open]; written by `LaunchOptions` before the coordinator is first touched. */
        @Volatile
        var current: LoadKnobs = LoadKnobs()

        /** `cpu` / `vulkan:0` (case and blanks ignored); anything else, or no extra, keeps the default. */
        fun parseDevice(text: String?): String =
            text?.trim()?.lowercase()?.takeIf { it in DEVICES } ?: DEFAULT_DEVICE

        /** `float16` / `bfloat16`; `float32` or `none` means the checkpoint's own (null); anything else keeps the default. */
        fun parseDtype(text: String?): String? = when (val t = text?.trim()?.lowercase()) {
            null, "" -> DEFAULT_DTYPE
            "float32", "none" -> null
            in DTYPES -> t
            else -> DEFAULT_DTYPE
        }
    }
}

/**
 * The dual detector's snapshot directories per model input size: `<filesDir>/models/rtdetr_v2_drone_rahul_<N>`
 * (drones) and `<filesDir>/models/dfine_small_coco_<N>` (people). Each holds the same weights; only
 * `preprocessor_config.json` `size` differs. The `drone_input` / `person_input` launch extras pick one of [DRONE_INPUTS]
 * / [PERSON_INPUTS]; an unknown size is ignored.
 */
object ModelDirs {
    const val DEFAULT_DRONE_INPUT = 320
    const val DEFAULT_PERSON_INPUT = 320

    val DRONE_INPUTS = listOf(320, 256, 224, 160)
    val PERSON_INPUTS = listOf(320, 256, 224)

    fun drone(filesDir: File, input: Int): String = File(filesDir, "models/rtdetr_v2_drone_rahul_$input").path
    fun person(filesDir: File, input: Int): String = File(filesDir, "models/dfine_small_coco_$input").path

    /** [input] when it is a size we ship a drone snapshot for, else null. */
    fun droneInput(input: Int?): Int? = input?.takeIf { it in DRONE_INPUTS }
    fun personInput(input: Int?): Int? = input?.takeIf { it in PERSON_INPUTS }

    /**
     * The dual config for the launch extras, or null when neither names a known size (then the stored pref stands).
     * A missing or unknown half falls back to its default size.
     */
    fun dualFor(filesDir: File, droneInput: Int?, personInput: Int?): DetectorConfig.Dual? {
        val d = droneInput(droneInput)
        val p = personInput(personInput)
        if (d == null && p == null) return null
        return DetectorConfig.Dual(drone(filesDir, d ?: DEFAULT_DRONE_INPUT), person(filesDir, p ?: DEFAULT_PERSON_INPUT))
    }
}
