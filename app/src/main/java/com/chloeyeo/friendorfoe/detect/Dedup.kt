package com.chloeyeo.friendorfoe.detect

/**
 * IoU de-duplication of one frame's detections (D-08). RT-DETR is NMS-free, but the drone proxies and `person`
 * can land on the same object (a hand-held prop, a person-shaped blob): when two boxes overlap at IoU >= the
 * threshold, the same-class duplicate with the lower score goes, and across classes the DRONE box wins — a
 * drone that is also called a person must still be challengeable.
 *
 * Walks the input in order (score-descending, as the [Detector] contract guarantees); pure Kotlin, no Android.
 */
object Dedup {
    fun apply(detections: List<Detection>, iouThreshold: Float = 0.5f): List<Detection> {
        val kept = ArrayList<Detection>(detections.size)
        for (det in detections) {
            val overlapping = kept.filter { it.box.iou(det.box) >= iouThreshold }
            when {
                overlapping.isEmpty() -> kept += det
                // A drone over boxes that are all persons: the drone replaces them (D-08).
                det.cls == ObjClass.DRONE && overlapping.all { it.cls == ObjClass.PERSON } -> {
                    kept.removeAll { k -> overlapping.any { it === k } }
                    kept += det
                }
                // Same class (the kept one scored higher) or a person under a kept drone: drop the new one.
                else -> Unit
            }
        }
        return kept.sortedByDescending { it.score }
    }
}
