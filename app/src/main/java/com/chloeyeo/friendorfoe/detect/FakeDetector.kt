package com.chloeyeo.friendorfoe.detect

/**
 * Scripted boxes for UI development and the on-stage fallback: one drone box drifting slowly left and right
 * across the upper third of the frame (one full sweep every [sweepTicks] calls) and one person box standing
 * still on the right. Deterministic: the output depends only on the frame size and the number of calls so far.
 */
class FakeDetector(private val sweepTicks: Int = 60) : Detector {
    override val name: String = "fake"
    private var ticks = 0

    override fun detect(frame: Frame): List<Detection> {
        val tick = ticks++
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()

        // Triangle wave in [0, 1]: left -> right -> left over one sweep.
        val phase = (tick % sweepTicks) / sweepTicks.toFloat()
        val sweep = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        val droneCx = w * (0.15f + 0.45f * sweep)
        val droneCy = h * (0.28f + 0.04f * sweep)
        val droneW = w * 0.12f
        val droneH = h * 0.10f
        val drone = Detection(
            cls = ObjClass.DRONE,
            score = 0.62f,
            box = BoxF(droneCx - droneW / 2f, droneCy - droneH / 2f, droneCx + droneW / 2f, droneCy + droneH / 2f),
        )
        val person = Detection(
            cls = ObjClass.PERSON,
            score = 0.91f,
            box = BoxF(w * 0.66f, h * 0.30f, w * 0.80f, h * 0.98f),
        )
        return listOf(person, drone) // score-descending, as the contract says
    }
}
