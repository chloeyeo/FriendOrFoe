package com.chloeyeo.friendorfoe.detect

/** How long [DualDetector.open] took per model; [crop] is null when no crop model is configured or it did not open. */
data class DualTiming(val drone: OpenTiming, val person: OpenTiming?, val crop: OpenTiming? = null)

/**
 * Two single-purpose models on one frame: a drone specialist ([drone], e.g. the RahulKate RT-DETR v2, one class)
 * and a person detector ([person], e.g. D-FINE S on COCO), both in-process on the same runtime lane. Each frame
 * runs both sequentially, maps each model's raw rows through ITS OWN [ClassMap], concatenates, sorts by score and
 * runs the D-08 [Dedup] once (so a drone box over a person box still wins). Blocking; runtime lane only.
 *
 * Crop rounds ([detectRound] with a crop [RoundPlan]): the drone model runs only on a square crop around each tracked
 * drone ([Crops]), on [cropDrone] (the same weights at a small input, e.g. 160) when it opened, else on [drone]; the
 * best accepted row per crop is mapped back to frame pixels. The person model runs only when the plan says so.
 */
class DualDetector(
    val drone: ModelDetector,
    val person: ModelDetector?,
    override val name: String = NAME,
    private val clockNanos: () -> Long = System::nanoTime,
    val cropDrone: ModelDetector? = null,
) : LoadableDetector, RoundDetector {

    /** The drone model (and the person model, when there is one) opened. */
    override val isOpen: Boolean get() = drone.isOpen && (person?.isOpen ?: true)

    /** Inference time of the last frame per model, in milliseconds (0 before the first frame). */
    @Volatile
    var lastDroneMs: Long = 0L
        private set

    @Volatile
    var lastPersonMs: Long = 0L
        private set

    /** The last round's plan (null before the first round). */
    @Volatile
    var lastPlan: RoundPlan? = null
        private set

    /** True once [cropDrone] opened; crop rounds then run on it instead of [drone]. */
    @Volatile
    var cropModelOpen: Boolean = false
        private set

    /**
     * Opens the drone model, then the person model. A failure names the model (`drone model: …`) so the strip note
     * says which snapshot is wrong; the binding's own exceptions pass through unchanged (their code names matter).
     */
    fun open(): DualTiming {
        val d = openTagged(drone)
        val p = person?.let { openTagged(it) }
        val c = cropDrone?.let { crop ->
            // A missing or broken crop snapshot only costs speed: crop rounds then run on the full-frame drone model.
            try {
                openTagged(crop).also { cropModelOpen = true }
            } catch (e: IllegalArgumentException) {
                cropOpenFailure = e.message
                null
            } catch (e: IllegalStateException) {
                cropOpenFailure = e.message
                null
            }
        }
        return DualTiming(d, p, c)
    }

    /** Why [cropDrone] did not open (null when it opened or none is configured), for the `runtime up` log line. */
    @Volatile
    var cropOpenFailure: String? = null
        private set

    override fun detect(frame: Frame): List<Detection> = detectRound(frame, RoundPlan.FULL)

    override fun detectRound(frame: Frame, plan: RoundPlan): List<Detection> {
        val t0 = clockNanos()
        val drones = if (plan.full) drone.classMap.map(drone.detectRaw(frame)) else plan.crops.mapNotNull { cropDetect(frame, it) }
        val t1 = clockNanos()
        val p = person
        val people = if (plan.person && p != null) p.classMap.map(p.detectRaw(frame)) else emptyList()
        val t2 = clockNanos()
        lastDroneMs = (t1 - t0) / 1_000_000
        lastPersonMs = (t2 - t1) / 1_000_000
        lastPlan = plan
        return merge(drones, people)
    }

    /** The best accepted drone row in the square crop around [box], in frame pixels, or null when the crop has none. */
    private fun cropDetect(frame: Frame, box: BoxF): Detection? {
        val rect = Crops.square(box, frame.width, frame.height) ?: return null
        val model = if (cropModelOpen) cropDrone!! else drone
        val best = model.classMap.map(model.detectRaw(Crops.cut(frame, rect))).maxByOrNull { it.score } ?: return null
        return best.copy(box = Crops.toFrame(best.box, rect))
    }

    /** Strip/log text for the last frame, e.g. `drone 6.1 s + person 1.4 s`. */
    fun timingNote(): String {
        val p = person ?: return "${drone.classMap.tag} ${secs(lastDroneMs)}"
        return "${drone.classMap.tag} ${secs(lastDroneMs)} + ${p.classMap.tag} ${secs(lastPersonMs)}"
    }

    override fun close() {
        drone.close()
        person?.close()
        cropDrone?.close()
        cropModelOpen = false
    }

    private fun openTagged(model: ModelDetector): OpenTiming = try {
        model.open()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(tagged(model, e.message), e)
    } catch (e: IllegalStateException) {
        throw IllegalStateException(tagged(model, e.message), e)
    }

    private fun tagged(model: ModelDetector, msg: String?): String {
        val m = msg.orEmpty()
        val tag = model.classMap.tag
        return if (m.startsWith("$tag model")) m else "$tag model: $m"
    }

    companion object {
        const val NAME = "clikart:drone-rahul+dfine-s"

        /** The strip name when there is no person model. */
        const val DRONE_ONLY_NAME = "clikart:drone-rahul"

        /** The concatenation of both mapped lists, score-descending, deduplicated once. */
        fun merge(droneDetections: List<Detection>, personDetections: List<Detection>, iouThreshold: Float = 0.5f): List<Detection> =
            Dedup.apply((droneDetections + personDetections).sortedByDescending { it.score }, iouThreshold)

        /**
         * The production pair: the RahulKate drone RT-DETR v2 in [droneDir], D-FINE S (COCO) in [personDir]. The
         * inner names are the directory names (`clikart:rtdetr_v2_drone_rahul_320`), so the `runtime up` line says
         * which input size runs; a `_<N>` drone dir (`_320`, `_256`, …) adds ` @<N>` to the strip name. [cropDir] (optional)
         * is the crop-round drone model, the same weights at a small input ([ClassMap.DRONE_RAHUL_CROP]).
         */
        fun clikaRt(droneDir: String, personDir: String?, cropDir: String? = null): DualDetector {
            val base = if (personDir == null) DRONE_ONLY_NAME else NAME
            return DualDetector(
            drone = ClikaRtDetector(droneDir, ClassMap.DRONE_RAHUL, "clikart:" + java.io.File(droneDir).name),
            person = personDir?.let { ClikaRtDetector(it, ClassMap.DFINE_PERSON, "clikart:" + java.io.File(it).name) },
            name = INPUT_SUFFIX.find(java.io.File(droneDir).name)?.let { "$base @${it.groupValues[1]}" } ?: base,
            cropDrone = cropDir?.let { ClikaRtDetector(it, ClassMap.DRONE_RAHUL_CROP, "clikart:" + java.io.File(it).name) },
            )
        }

        private val INPUT_SUFFIX = Regex("_([0-9]{3})$")

        private fun secs(ms: Long): String = "%.1f s".format(java.util.Locale.US, ms / 1000.0)
    }
}
