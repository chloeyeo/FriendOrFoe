package com.chloeyeo.friendorfoe.detect

/** Which [Detector] the app runs; one line in the settings/prefs swaps it and nothing else changes. */
sealed interface DetectorConfig {
    /** Scripted boxes, no camera or model needed. */
    data object Fake : DetectorConfig

    /** On-device RT-DETR v2 through ClikaRT; [modelDir] is the pushed snapshot directory. */
    data class ClikaRt(val modelDir: String) : DetectorConfig

    /** The laptop `clikart-cli serve` at [baseUrl] (debug builds only). */
    data class Http(val baseUrl: String) : DetectorConfig

    /** The one-line form stored in Prefs: `fake`, `clikart:<modelDir>`, `http:<baseUrl>`. */
    fun encode(): String = when (this) {
        Fake -> "fake"
        is ClikaRt -> "clikart:$modelDir"
        is Http -> "http:$baseUrl"
    }

    companion object {
        /** Inverse of [encode]; anything unreadable falls back to [Fake]. */
        fun decode(text: String?): DetectorConfig {
            val s = text?.trim().orEmpty()
            return when {
                s.startsWith("clikart:") && s.length > 8 -> ClikaRt(s.substring(8))
                s.startsWith("http:") && s.length > 5 -> Http(s.substring(5))
                else -> Fake
            }
        }
    }
}
