package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.ObjClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScenarioTest {
    private val now = 1_760_000_000_000L
    private val states = DemoScenario.states(now, 1920, 1080)

    private fun drone(state: ObserverUiState): TrackUi = state.tracks.single { it.cls == ObjClass.DRONE }

    @Test
    fun `states are non-empty with one title each`() {
        assertTrue(states.isNotEmpty())
        assertEquals(DemoScenario.titles.size, states.size)
    }

    @Test
    fun `every state has one drone and one person inside the preview`() {
        for (state in states) {
            assertEquals(2, state.tracks.size)
            assertEquals(1, state.tracks.count { it.cls == ObjClass.PERSON })
            val d = drone(state)
            assertTrue(d.box.left >= 0f && d.box.right <= 1920f && d.box.top >= 0f && d.box.bottom <= 1080f)
            assertTrue(state.status.offline)
            assertEquals("fake", state.status.detectorName)
        }
    }

    @Test
    fun `the scenario follows the demo order`() {
        assertEquals(IffState.Unchallenged, drone(states[0]).iff)

        val challenged = drone(states[1]).iff as IffState.Challenged
        assertTrue(challenged.until > now && challenged.until <= now + challenged.windowMs)

        val friend = drone(states[2]).iff as IffState.Verdict
        assertEquals(VerdictKind.FRIEND, friend.kind)
        assertNull(friend.reason)
        assertEquals("RQ-07", friend.droneId)
        assertEquals(-58, friend.rssiDbm)
        assertEquals(42L, friend.roundTripMs)

        val reasons = (3..5).map { (drone(states[it]).iff as IffState.Verdict) }
        assertTrue(reasons.all { it.kind == VerdictKind.FOE })
        assertEquals(listOf("replayed nonce", "position mismatch", "late response"), reasons.map { it.reason })

        val unknown = drone(states[6]).iff as IffState.Verdict
        assertEquals(VerdictKind.UNKNOWN, unknown.kind)
        assertEquals("no response", unknown.reason)
        assertNull(unknown.droneId)
    }

    @Test
    fun `events accumulate newest first and answers stack up`() {
        for (i in 1 until states.size) {
            assertTrue(states[i].events.size > states[i - 1].events.size)
            assertTrue(states[i].events.zipWithNext().all { (a, b) -> a.t >= b.t })
        }
        assertTrue(states[0].signal.isEmpty())
        assertEquals(1, states[2].signal.size)
        assertEquals(VerdictKind.UNKNOWN, states[6].signal.first().verdict)
        assertEquals(5, states[6].signal.size)
    }
}
