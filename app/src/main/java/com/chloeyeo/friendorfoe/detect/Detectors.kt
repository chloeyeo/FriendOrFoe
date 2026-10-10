package com.chloeyeo.friendorfoe.detect

import com.chloeyeo.friendorfoe.BuildConfig

/** The one place a [DetectorConfig] becomes a [Detector]. */
object Detectors {
    /**
     * Builds the detector for [config]. [DetectorConfig.Http] is a development/contingency path and is refused
     * (with [IllegalStateException]) unless the build sets `BuildConfig.ALLOW_HTTP_DETECTOR` (debug builds do).
     */
    fun create(config: DetectorConfig): Detector = when (config) {
        DetectorConfig.Fake -> FakeDetector()
        is DetectorConfig.ClikaRt -> ClikaRtDetector(config.modelDir)
        is DetectorConfig.Dual -> DualDetector.clikaRt(config.droneDir, config.personDir)
        is DetectorConfig.Http -> {
            check(BuildConfig.ALLOW_HTTP_DETECTOR) {
                "HttpDetector is only allowed in builds with ALLOW_HTTP_DETECTOR (debug); the demo runs offline"
            }
            HttpDetector(config.baseUrl)
        }
    }
}
