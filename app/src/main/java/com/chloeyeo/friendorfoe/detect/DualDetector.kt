package com.chloeyeo.friendorfoe.detect

/** How long [DualDetector.open] took per model. */
data class DualTiming(val drone: OpenTiming, val person: OpenTiming)

/**
 * Two single-purpose models on one frame: a drone specialist ([drone], e.g. the RahulKate RT-DETR v2, one class)
 * and a person detector ([person], e.g. D-FINE S on COCO), both in-process on the same runtime lane. Each frame
 * runs both sequentially, maps each model's raw rows through ITS OWN [ClassMap], concatenates, sorts by score and
 * runs the D-08 [Dedup] once (so a drone box over a person box still wins). Blocking; runtime lane only.
 */
class DualDetector(
    val drone: ModelDetector,
    val person: ModelDetector,
    override val name: String = NAME,
    private val clockNanos: () -> Long = System::nanoTime,
) : LoadableDetector {

    /** Both models opened. */
    override val isOpen: Boolean get() = drone.isOpen && person.isOpen

    /** Inference time of the last frame per model, in milliseconds (0 before the first frame). */
    @Volatile
    var lastDroneMs: Long = 0L
        private set

    @Volatile
    var lastPersonMs: Long = 0L
        private set

    /**
     * Opens the drone model, then the person model. A failure names the model (`drone model: …`) so the strip note
     * says which snapshot is wrong; the binding's own exceptions pass through unchanged (their code names matter).
     */
    fun open(): DualTiming {
        val d = openTagged(drone)
        val p = openTagged(person)
        return DualTiming(d, p)
    }

    override fun detect(frame: Frame): List<Detection> {
        val t0 = clockNanos()
        val droneRows = drone.detectRaw(frame)
        val t1 = clockNanos()
        val personRows = person.detectRaw(frame)
        val t2 = clockNanos()
        lastDroneMs = (t1 - t0) / 1_000_000
        lastPersonMs = (t2 - t1) / 1_000_000
        return merge(drone.classMap.map(droneRows), person.classMap.map(personRows))
    }

    /** Strip/log text for the last frame, e.g. `drone 6.1 s + person 1.4 s`. */
    fun timingNote(): String = "${drone.classMap.tag} ${secs(lastDroneMs)} + ${person.classMap.tag} ${secs(lastPersonMs)}"

    override fun close() {
        drone.close()
        person.close()
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

        /** The concatenation of both mapped lists, score-descending, deduplicated once. */
        fun merge(droneDetections: List<Detection>, personDetections: List<Detection>, iouThreshold: Float = 0.5f): List<Detection> =
            Dedup.apply((droneDetections + personDetections).sortedByDescending { it.score }, iouThreshold)

        /**
         * The production pair: the RahulKate drone RT-DETR v2 in [droneDir], D-FINE S (COCO) in [personDir]. The
         * inner names are the directory names (`clikart:rtdetr_v2_drone_rahul_320`), so the `runtime up` line says
         * which input size runs; a `_320` drone dir adds ` @320` to the strip name.
         */
        fun clikaRt(droneDir: String, personDir: String): DualDetector = DualDetector(
            drone = ClikaRtDetector(droneDir, ClassMap.DRONE_RAHUL, "clikart:" + java.io.File(droneDir).name),
            person = ClikaRtDetector(personDir, ClassMap.DFINE_PERSON, "clikart:" + java.io.File(personDir).name),
            name = if (droneDir.trimEnd('/').endsWith("_320")) "$NAME @320" else NAME,
        )

        private fun secs(ms: Long): String = "%.1f s".format(java.util.Locale.US, ms / 1000.0)
    }
}
