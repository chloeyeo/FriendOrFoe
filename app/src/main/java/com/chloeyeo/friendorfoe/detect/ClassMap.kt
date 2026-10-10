package com.chloeyeo.friendorfoe.detect

/**
 * One model's label table, as the app reads it: which raw `labelId` means which [ObjClass], the score floor per
 * class, and the label strings the loaded checkpoint must carry (the fail-loudly check in [ClikaRtDetector.open]).
 * Ids not in [classes] are dropped. Pure Kotlin, no Android.
 *
 * @property tag short model name for logs and the status strip (`r18`, `drone`, `person`)
 * @property expectedLabelCount the label-table size the checkpoint must have, or null to skip the size check
 * @property expectedLabels label strings the checkpoint must carry at those indices
 */
data class ClassMap(
    val tag: String,
    val classes: Map<Long, ObjClass>,
    val thresholds: Map<ObjClass, Float>,
    val expectedLabelCount: Int?,
    val expectedLabels: Map<Int, String>,
) {
    /** The class to keep for one raw row, or null when the id is not mapped or the score is below its floor. */
    fun accept(labelId: Long, score: Float): ObjClass? {
        val cls = classes[labelId] ?: return null
        return if (score >= (thresholds[cls] ?: 1f)) cls else null
    }

    /** Maps [rows] in order (no dedup; the caller runs [Dedup] once over everything it merges). */
    fun map(rows: List<RawDetection>): List<Detection> =
        rows.mapNotNull { r -> accept(r.labelId, r.score)?.let { Detection(it, r.score, r.box) } }

    /** Null when [labels] (the checkpoint's `capabilities.labels`) matches, else the mismatch for the strip note. */
    fun labelMismatch(labels: List<String>): String? {
        if (expectedLabelCount != null && labels.size != expectedLabelCount) {
            return "$tag model: ${labels.size} labels, expected $expectedLabelCount"
        }
        for ((i, want) in expectedLabels) {
            val got = labels.getOrNull(i)
            if (got != want) return "$tag model: label $i is ${got ?: "missing"}, expected $want"
        }
        return null
    }

    companion object {
        /** `PekingU/rtdetr_v2_r18vd` (COCO 80): the [LabelMap] proxy set, 0 person, 4/14/33 drone. */
        val COCO_R18 = ClassMap(
            tag = "r18",
            classes = mapOf(0L to ObjClass.PERSON, 4L to ObjClass.DRONE, 14L to ObjClass.DRONE, 33L to ObjClass.DRONE),
            thresholds = mapOf(
                ObjClass.PERSON to LabelMap.DEFAULT_PERSON_THRESHOLD,
                ObjClass.DRONE to LabelMap.DEFAULT_DRONE_THRESHOLD,
            ),
            expectedLabelCount = 80,
            expectedLabels = mapOf(0 to "person", 4 to "aeroplane"),
        )

        /** `RahulKate-173/rt-detrv2-drone-object-detection` (RT-DETR v2 R50, one class): 0 drone >= 0.30. */
        val DRONE_RAHUL = ClassMap(
            tag = "drone",
            classes = mapOf(0L to ObjClass.DRONE),
            thresholds = mapOf(ObjClass.DRONE to 0.30f),
            expectedLabelCount = 1,
            expectedLabels = mapOf(0 to "drone"),
        )

        /**
         * The same drone checkpoint on a crop round: the crop is cut around a drone the tracker already follows, so
         * the floor drops to the detector's own 0.25 (the small crop input scores a confirmed drone lower, 0.3-0.4).
         */
        val DRONE_RAHUL_CROP = DRONE_RAHUL.copy(tag = "crop", thresholds = mapOf(ObjClass.DRONE to 0.25f))

        /**
         * A drone-trained YOLO ONNX ([OnnxYoloDetector] folds every class into 0): drone >= 0.40 (marie YOLO11n).
         * The model carries no label table, so nothing is checked at open. [yolo] picks the floor per file.
         */
        val DRONE_YOLO = ClassMap(
            tag = "yolo",
            classes = mapOf(0L to ObjClass.DRONE),
            thresholds = mapOf(ObjClass.DRONE to 0.40f),
            expectedLabelCount = null,
            expectedLabels = emptyMap(),
        )

        /** TomSmail YOLOv8n (quadcopter + fixed-wing): the benchmark's floor is 0.20 with the big-box drop. */
        val DRONE_YOLO_TOM = DRONE_YOLO.copy(thresholds = mapOf(ObjClass.DRONE to 0.20f))

        /** The class map for YOLO file [fileName]: TomSmail files (`tom*`) at 0.20, the rest at 0.40; [floor] overrides. */
        fun yolo(fileName: String, floor: Float? = null): ClassMap {
            val base = if (fileName.lowercase().startsWith("tom")) DRONE_YOLO_TOM else DRONE_YOLO
            return if (floor == null) base else base.copy(thresholds = mapOf(ObjClass.DRONE to floor))
        }

        /** `ustc-community/dfine-small-coco` (D-FINE S, COCO 80): only 0 person >= 0.50, everything else dropped. */
        val DFINE_PERSON = ClassMap(
            tag = "person",
            classes = mapOf(0L to ObjClass.PERSON),
            thresholds = mapOf(ObjClass.PERSON to 0.50f),
            expectedLabelCount = 80,
            expectedLabels = mapOf(0 to "person"),
        )
    }
}
