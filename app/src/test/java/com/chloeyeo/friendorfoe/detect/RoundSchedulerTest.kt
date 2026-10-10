package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundSchedulerTest {
    private val drone = BoxF(100f, 100f, 140f, 130f)

    /** Runs [n] rounds [roundMs] apart with [boxes] tracked, returns each plan's `kind`+`P` when the person model ran. */
    private fun run(s: RoundScheduler, n: Int, roundMs: Long, boxes: List<BoxF>, start: Long = 0L): List<String> {
        var t = start
        return (0 until n).map {
            val plan = s.plan(boxes, t)
            t += roundMs
            s.done(plan, t)
            plan.kind + if (plan.person) "P" else ""
        }
    }

    @Test
    fun `nothing tracked means every round is a full scan`() {
        assertEquals(listOf("fullP", "full", "full", "fullP", "full", "full", "fullP"), run(RoundScheduler(RoundScheduler.Config()), 7, 1500, emptyList()))
    }

    @Test
    fun `a tracked drone gets crop rounds and a full scan every 4th round`() {
        val kinds = run(RoundScheduler(RoundScheduler.Config()), 9, 700, listOf(drone))
        // round 0 is full (no full yet), then crop crop crop full ...; the person model every 3rd round
        assertEquals(listOf("fullP", "crop", "crop", "cropP", "full", "crop", "cropP", "crop", "full"), kinds)
    }

    @Test
    fun `a crop plan carries the tracked boxes`() {
        val s = RoundScheduler(RoundScheduler.Config())
        s.done(s.plan(listOf(drone), 0), 2000)
        val plan = s.plan(listOf(drone), 2000)
        assertFalse(plan.full)
        assertEquals(listOf(drone), plan.crops)
    }

    @Test
    fun `slow crop rounds still get a full scan within 3 s of the last one`() {
        // 1.5 s crop rounds: the full scan ends at 1.5 s, crops start at 1.5 and 3.0; at 4.5 s 3 s have passed -> full
        // although only two crop rounds ran (the 4th-round rule alone would crop once more)
        assertEquals(listOf("fullP", "crop", "crop", "fullP", "crop"), run(RoundScheduler(RoundScheduler.Config()), 5, 1500, listOf(drone)))
    }

    @Test
    fun `more drones than max crops means a full scan`() {
        val many = List(4) { i -> BoxF(i * 200f, 0f, i * 200f + 40f, 40f) }
        assertTrue(run(RoundScheduler(RoundScheduler.Config(maxCrops = 3)), 6, 700, many).all { it.startsWith("full") })
        assertTrue(run(RoundScheduler(RoundScheduler.Config(maxCrops = 0)), 4, 700, listOf(drone)).all { it.startsWith("full") })
    }

    @Test
    fun `person every round when person_every is 1`() {
        assertTrue(run(RoundScheduler(RoundScheduler.Config(personEvery = 1)), 5, 700, listOf(drone)).all { it.endsWith("P") })
    }

    @Test
    fun `person sticky time covers the measured gap between person rounds`() {
        val s = RoundScheduler(RoundScheduler.Config())
        assertEquals(1500L, s.personStickyMs()) // nothing measured yet
        run(s, 1, 700, listOf(drone))
        assertEquals(1500L, s.personStickyMs()) // one round: no interval yet
        run(s, 1, 700, listOf(drone), start = 700)
        assertEquals(3 * 700L * 3 / 2, s.personStickyMs()) // 3 rounds x 700 ms x 1.5 before two person rounds
        run(s, 2, 700, listOf(drone), start = 1400)
        // person rounds ended at 700 and 2800: 2100 ms apart -> 3150 ms
        assertEquals(3150L, s.personStickyMs())
        // slower rounds raise it at once
        run(s, 3, 2000, emptyList(), start = 2800)
        assertEquals((8800L - 2800L) * 3 / 2, s.personStickyMs())
    }

    @Test
    fun `fast rounds never drop the sticky time below the base`() {
        val s = RoundScheduler(RoundScheduler.Config(personEvery = 1))
        run(s, 5, 100, emptyList())
        assertEquals(1500L, s.personStickyMs())
    }

    @Test
    fun droneStickyFollowsTheRoundTimeBetweenOneAndFiveSeconds() {
        val s = RoundScheduler()
        assertEquals(5_000L, s.droneStickyMs())
        s.done(RoundPlan.FULL, 1_000L)
        s.done(RoundPlan.FULL, 1_540L)
        assertEquals(1_350L, s.droneStickyMs())
        val fast = RoundScheduler()
        fast.done(RoundPlan.FULL, 0L)
        fast.done(RoundPlan.FULL, 290L)
        assertEquals(1_000L, fast.droneStickyMs())
        val slow = RoundScheduler()
        slow.done(RoundPlan.FULL, 0L)
        slow.done(RoundPlan.FULL, 4_400L)
        assertEquals(5_000L, slow.droneStickyMs())
    }
}
