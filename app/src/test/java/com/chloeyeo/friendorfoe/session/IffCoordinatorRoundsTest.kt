package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.detect.RoundDetector
import com.chloeyeo.friendorfoe.detect.RoundPlan
import com.chloeyeo.friendorfoe.detect.RoundScheduler
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import com.chloeyeo.friendorfoe.track.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IffCoordinatorRoundsTest {
    private val droneBox = BoxF(40f, 20f, 60f, 36f)
    private val personBox = BoxF(80f, 10f, 110f, 70f)

    /** Sees one drone (full scan or a crop on it) and one person (only when the plan runs the person model). */
    private class RoundsFake(private val drone: BoxF?, private val person: BoxF, private val clock: () -> Unit) : RoundDetector {
        override val name = "rounds"
        val plans = ArrayList<RoundPlan>()

        override fun detect(frame: Frame): List<Detection> = detectRound(frame, RoundPlan.FULL)

        override fun detectRound(frame: Frame, plan: RoundPlan): List<Detection> {
            plans += plan
            clock() // the round takes time
            val out = ArrayList<Detection>()
            if (drone != null && (plan.full || plan.crops.isNotEmpty())) out += Detection(ObjClass.DRONE, 0.6f, drone)
            if (plan.person) out += Detection(ObjClass.PERSON, 0.9f, person)
            return out
        }
    }

    private fun frame() = Frame(width = 128, height = 72, rgba = ByteArray(128 * 72 * 4))

    @Test
    fun `person tracks survive the rounds that skip the person model`() {
        var now = 10_000L
        val detector = RoundsFake(droneBox, personBox) { now += 900 } // 0.9 s rounds: 2.7 s between person rounds
        val coordinator = IffCoordinator(
            detector, FakeSignalSource(), clock = { now }, scheduler = RoundScheduler(RoundScheduler.Config()),
        )
        coordinator.tick(frame())
        val personId = coordinator.state.value.tracks.single { it.cls == ObjClass.PERSON }.id
        repeat(8) {
            coordinator.tick(frame())
            val person = coordinator.state.value.tracks.filter { it.cls == ObjClass.PERSON }
            assertEquals("round ${it + 1}", listOf(personId), person.map { p -> p.id })
        }
        val kinds = detector.plans.map { it.kind + if (it.person) "P" else "" }
        assertEquals(listOf("fullP", "crop", "crop", "cropP", "full", "crop", "cropP", "crop", "full"), kinds)
        // the crop rounds were planned on the tracked drone's box
        assertEquals(listOf(droneBox), detector.plans[1].crops)
    }

    @Test
    fun `the old fixed 1_5 s person window would drop the person between person rounds`() {
        val person = Detection(ObjClass.PERSON, 0.9f, personBox)
        val fixed = Tracker()
        fixed.update(listOf(person), 0L)
        fixed.update(emptyList(), 1_800L) // two skipped 0.9 s rounds
        assertTrue(fixed.tracks.isEmpty())

        val following = Tracker()
        following.update(listOf(person), 0L)
        following.personStickyMs = 4_050L // what the scheduler sets for 0.9 s rounds, person every 3rd
        following.update(emptyList(), 900L)
        following.update(emptyList(), 1_800L)
        assertEquals(1, following.update(listOf(person), 2_700L).size)
        assertEquals(1, following.tracks.single().id)
    }

    @Test
    fun `no tracked drone means full scans only`() {
        var now = 0L
        val detector = RoundsFake(null, personBox) { now += 1500 }
        val coordinator = IffCoordinator(detector, FakeSignalSource(), clock = { now }, scheduler = RoundScheduler(RoundScheduler.Config()))
        repeat(5) { coordinator.tick(frame()) }
        assertTrue(detector.plans.all { it.full })
        assertTrue(coordinator.lastPlan!!.crops.isEmpty())
    }
}
