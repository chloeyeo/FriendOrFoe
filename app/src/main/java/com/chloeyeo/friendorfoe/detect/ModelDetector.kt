package com.chloeyeo.friendorfoe.detect

/** A [Detector] that must be opened (loaded and warmed up) before its first frame; the camera gate checks [isOpen]. */
interface LoadableDetector : Detector {
    /** True once the model(s) opened and until [close]. */
    val isOpen: Boolean
}

/**
 * One in-process model with its own [ClassMap]: [ClikaRtDetector] in the app, a fake in the JVM tests of
 * [DualDetector]. [open] and [detectRaw] block; run them on the runtime lane.
 */
interface ModelDetector : LoadableDetector {
    val classMap: ClassMap

    /** Debug hook: receives every raw row list from [detectRaw] (before the class map); null by default. */
    var rawListener: ((List<RawDetection>) -> Unit)?

    /** Loads, checks the label table against [classMap] and warms up; idempotent. */
    fun open(): OpenTiming

    /** Raw rows of [frame] in frame pixels, score-descending, before the class map; feeds [rawListener]. */
    fun detectRaw(frame: Frame): List<RawDetection>

    /** [detectRaw] through [classMap], then the D-08 [Dedup]. */
    override fun detect(frame: Frame): List<Detection> = Dedup.apply(classMap.map(detectRaw(frame)))
}
