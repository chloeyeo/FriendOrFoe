package com.chloeyeo.friendorfoe.detect

/**
 * Contract 1 of 3 (README section 3): stream 1 provides, everyone consumes. Implementations: [ClikaRtDetector]
 * (production, RT-DETR v2 in-process), [HttpDetector] (laptop `clikart-cli serve`, contingency) and
 * [FakeDetector] (UI development, scripted boxes). Swapping is one [DetectorConfig] line in [Detectors.create].
 */
interface Detector {
    /**
     * Boxes in frame pixels of [frame], score-descending, already filtered through [LabelMap] (only PERSON and
     * DRONE, each above its class threshold). Blocks for the inference; call it on the runtime lane, never on
     * the main thread.
     */
    fun detect(frame: Frame): List<Detection>

    /** Short name for the status strip, e.g. `clikart:rtdetr_v2_r18vd`, `http:192.168.1.2:8129`, `fake`. */
    val name: String

    /** Releases the model; a second call is a no-op. */
    fun close() {}
}
