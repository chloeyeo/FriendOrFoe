package com.chloeyeo.friendorfoe.detect

/**
 * One detector row before the class map, in frame pixels: the raw COCO [labelId] (RT-DETR v2), its [score] (0..1),
 * its [box] and the model's [label] string (informational only; [LabelMap] keys on the id).
 */
data class RawDetection(val labelId: Long, val score: Float, val box: BoxF, val label: String = "")

/**
 * The ONE mapping from raw rows to [Detection]s that both `ClikaRtDetector` and `HttpDetector` call, so the
 * thresholds live in one place (DET-03: person >= 0.5, drone proxies 4/14/33 >= 0.28 via the [LabelMap] defaults;
 * anything else, e.g. `cell phone` 67, is dropped by [LabelMap.classOf]), then the D-08 [Dedup].
 */
object RawDetections {
    fun toDetections(rows: List<RawDetection>, iouThreshold: Float = 0.5f): List<Detection> =
        Dedup.apply(
            rows.mapNotNull { r -> LabelMap.accept(r.labelId, r.score)?.let { cls -> Detection(cls, r.score, r.box) } },
            iouThreshold,
        )
}
