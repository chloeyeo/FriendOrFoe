package com.chloeyeo.friendorfoe.detect

/**
 * Maps the RT-DETR v2 (COCO) `labelId` to our two classes and holds the per-class score thresholds.
 * Keyed on the numeric id, never on the label string: the cached `PekingU/rtdetr_v2_r18vd` config spells the
 * labels VOC-style (`aeroplane`, `motorbike`), other checkpoints spell them COCO-style (`airplane`).
 *
 * - 0 `person` -> [ObjClass.PERSON]
 * - 4 `aeroplane`, 14 `bird`, 33 `kite` -> [ObjClass.DRONE] (the COCO proxies for a small flying object)
 * - everything else -> null (dropped)
 *
 * Drones are small and under-represented in COCO, so the drone threshold is low (0.28) while the person
 * threshold stays at the usual 0.5. Stream 1 tunes both from the eval table.
 */
object LabelMap {
    const val DEFAULT_PERSON_THRESHOLD = 0.5f
    const val DEFAULT_DRONE_THRESHOLD = 0.28f

    private const val PERSON_ID = 0L
    private val DRONE_IDS: Set<Long> = setOf(4L, 14L, 33L)

    /** The class of a raw RT-DETR/COCO [labelId], or null when it is none of ours. */
    fun classOf(labelId: Long): ObjClass? = when {
        labelId == PERSON_ID -> ObjClass.PERSON
        labelId in DRONE_IDS -> ObjClass.DRONE
        else -> null
    }

    /** The default score floor of [cls]. */
    fun threshold(cls: ObjClass): Float = when (cls) {
        ObjClass.PERSON -> DEFAULT_PERSON_THRESHOLD
        ObjClass.DRONE -> DEFAULT_DRONE_THRESHOLD
    }

    /**
     * The class to keep for one raw (labelId, score) row, or null when the id is not ours or the score is below
     * the class threshold ([personThreshold] / [droneThreshold] default to the constants above).
     */
    fun accept(
        labelId: Long,
        score: Float,
        personThreshold: Float = DEFAULT_PERSON_THRESHOLD,
        droneThreshold: Float = DEFAULT_DRONE_THRESHOLD,
    ): ObjClass? {
        val cls = classOf(labelId) ?: return null
        val floor = when (cls) {
            ObjClass.PERSON -> personThreshold
            ObjClass.DRONE -> droneThreshold
        }
        return if (score >= floor) cls else null
    }
}
