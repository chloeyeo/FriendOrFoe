package com.chloeyeo.friendorfoe.ui.log

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.session.VerdictKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EventCountsTest {
    private fun row(track: Int?, cls: ObjClass, verdict: VerdictKind? = null) =
        EventUi(0, "", trackId = track, cls = cls, verdict = verdict)

    @Test
    fun tracksCountOnceAndVerdictsCountEach() {
        val events = listOf(
            row(1, ObjClass.PERSON),
            row(1, ObjClass.PERSON),
            row(2, ObjClass.DRONE),
            row(3, ObjClass.DRONE),
            row(null, ObjClass.DRONE),
            row(2, ObjClass.DRONE, VerdictKind.FRIEND),
            row(2, ObjClass.DRONE, VerdictKind.FOE),
            row(3, ObjClass.DRONE, VerdictKind.FOE),
            row(3, ObjClass.DRONE, VerdictKind.UNKNOWN),
            EventUi(0, "challenge sent · box 2"),
        )
        assertEquals(EventCounts(drones = 3, people = 1, friend = 1, foe = 2, unknown = 1), EventCounts.from(events))
    }

    @Test
    fun demoScenarioEndState() {
        val events = DemoScenario.states(0L).last().events
        assertEquals(EventCounts(drones = 1, people = 1, friend = 1, foe = 3, unknown = 1), EventCounts.from(events))
    }

    @Test
    fun ages() {
        assertEquals("now", ageText(400))
        assertEquals("now", ageText(-2_000))
        assertEquals("12 s ago", ageText(12_000))
        assertEquals("3 min ago", ageText(200_000))
        assertEquals("2 h ago", ageText(7_300_000))
    }

    @Test
    fun box() {
        assertEquals("576,238 → 806,432", boxText(BoxF(576.2f, 237.6f, 806.4f, 432f)))
    }
}
