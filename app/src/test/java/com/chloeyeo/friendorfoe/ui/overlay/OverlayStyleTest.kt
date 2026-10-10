package com.chloeyeo.friendorfoe.ui.overlay

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.session.VerdictKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayStyleTest {
    private val box = BoxF(500f, 200f, 700f, 500f)
    private val now = 1_000_000L

    private fun drone(iff: IffState) = TrackUi(2, ObjClass.DRONE, box, iff)

    @Test
    fun person() {
        assertEquals(TrackTag("person", Tone.NEUTRAL), tagFor(TrackUi(1, ObjClass.PERSON, box, IffState.Unchallenged), now))
    }

    @Test
    fun unchallengedDrone() {
        assertEquals(TrackTag("drone · ?", Tone.AMBER), tagFor(drone(IffState.Unchallenged), now))
    }

    @Test
    fun challengedDroneCountsDown() {
        val tag = tagFor(drone(IffState.Challenged(until = now + 7_200)), now)
        assertEquals("drone · challenge 8 s", tag.text)
        assertEquals(Tone.AMBER, tag.tone)
        assertEquals(Countdown(0.72f, 8), tag.countdown)
    }

    @Test
    fun countdownClampsAtBothEnds() {
        assertEquals(Countdown(0f, 0), countdown(untilMs = now - 500, nowMs = now))
        assertEquals(Countdown(1f, 10), countdown(untilMs = now + 60_000, nowMs = now))
        assertEquals(Countdown(0.5f, 3), countdown(untilMs = now + 2_500, nowMs = now, windowMs = 5_000))
    }

    @Test
    fun friend() {
        val tag = tagFor(drone(IffState.Verdict(VerdictKind.FRIEND, null, "RQ-07", -58, 42)), now)
        assertEquals(TrackTag("FRIEND · RQ-07 · −58 dBm · 42 ms", Tone.GREEN), tag)
    }

    @Test
    fun foeShowsReasonAndEvidence() {
        val tag = tagFor(drone(IffState.Verdict(VerdictKind.FOE, "late response", "RQ-07", -60, 3_450)), now)
        assertEquals("FOE · late response", tag.text)
        assertEquals(Tone.RED, tag.tone)
        assertEquals("RQ-07 · −60 dBm · 3450 ms", tag.detail)
    }

    @Test
    fun unknownIsRedAndDefaultsToNoResponse() {
        val tag = tagFor(drone(IffState.Verdict(VerdictKind.UNKNOWN, null, null, null, null)), now)
        assertEquals(TrackTag("UNKNOWN · no response", Tone.RED), tag)
        assertNull(tag.detail)
    }
}
