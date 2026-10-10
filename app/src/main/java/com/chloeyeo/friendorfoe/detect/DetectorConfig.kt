package com.chloeyeo.friendorfoe.detect

/** Which [Detector] the app runs; one line in the settings/prefs swaps it and nothing else changes. */
sealed interface DetectorConfig {
    /** Scripted boxes, no camera or model needed. */
    data object Fake : DetectorConfig

    /** On-device RT-DETR v2 through ClikaRT; [modelDir] is the pushed snapshot directory. */
    data class ClikaRt(val modelDir: String) : DetectorConfig

    /**
     * On-device models through ClikaRT ([DualDetector]): a drone specialist in [droneDir] and, optionally, a person
     * detector in [personDir] (pushed snapshot directories). A null [personDir] is drone-only: the person model is
     * never loaded or run (the fresh-install default).
     */
    data class Dual(val droneDir: String, val personDir: String? = null) : DetectorConfig

    /** The laptop `clikart-cli serve` at [baseUrl] (debug builds only). */
    data class Http(val baseUrl: String) : DetectorConfig

    /** The one-line form stored in Prefs: `fake`, `clikart:<modelDir>`, `dual:<droneDir>|<personDir>` (empty person dir = drone only), `http:<baseUrl>`. */
    fun encode(): String = when (this) {
        Fake -> "fake"
        is ClikaRt -> "clikart:$modelDir"
        is Dual -> "dual:$droneDir|${personDir.orEmpty()}"
        is Http -> "http:$baseUrl"
    }

    companion object {
        /** Inverse of [encode]; anything unreadable falls back to [Fake]. */
        fun decode(text: String?): DetectorConfig {
            val s = text?.trim().orEmpty()
            return when {
                s.startsWith("clikart:") && s.length > 8 -> ClikaRt(s.substring(8))
                s.startsWith("dual:") -> decodeDual(s.substring(5))
                s.startsWith("http:") && s.length > 5 -> Http(s.substring(5))
                else -> Fake
            }
        }

        private fun decodeDual(rest: String): DetectorConfig {
            val parts = rest.split('|')
            return if (parts.size == 2 && parts[0].isNotBlank()) Dual(parts[0], parts[1].ifBlank { null }) else Fake
        }
    }
}
