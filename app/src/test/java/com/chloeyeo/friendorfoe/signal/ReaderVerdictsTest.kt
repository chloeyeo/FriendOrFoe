package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import com.chloeyeo.friendorfoe.signal.GadgetLineCodec.ReaderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mapping table from Ihan's reader verdicts to the phone's ring (docs/GADGET_LINK.md "Verdict mapping"). */
class ReaderVerdictsTest {
    private fun result(verdict: String, reason: String, received: Boolean = true, replay: Boolean = false, responseMs: Long? = 200) =
        ReaderEvent.Result("r1", 1, received, verdict, reason, responseMs, if (responseMs == null) 3000 else responseMs, replay)

    @Test
    fun `every reader reason maps to one ring`() {
        val table = mapOf(
            result("FRIEND", "NONE") to (VerdictKind.FRIEND to null),
            result("UNKNOWN", "NO_RESPONSE", received = false, responseMs = null) to (VerdictKind.UNKNOWN to "no response"),
            result("UNKNOWN", "AUTH_FAILED") to (VerdictKind.FOE to "bad tag"),
            result("UNKNOWN", "INVALID_MESSAGE") to (VerdictKind.UNKNOWN to "invalid message"),
            result("UNKNOWN", "RADIO_ERROR", received = false, responseMs = null) to (VerdictKind.UNKNOWN to "gadget radio error"),
            result("FRIEND", "NONE", replay = true) to (VerdictKind.FOE to "replayed nonce"),
            result("UNKNOWN", "NO_RESPONSE", received = false, replay = true, responseMs = null) to (VerdictKind.FOE to "replayed nonce"),
        )
        for ((r, expected) in table) assertEquals(r.toString(), expected, ReaderVerdicts.map(r))
    }

    @Test
    fun `FRIEND needs reason NONE and a received response, anything odd is UNKNOWN`() {
        assertEquals(VerdictKind.UNKNOWN to "invalid result", ReaderVerdicts.map(result("FRIEND", "AUTH_FAILED")))
        assertEquals(VerdictKind.UNKNOWN to "invalid result", ReaderVerdicts.map(result("FRIEND", "NONE", received = false)))
        assertEquals(VerdictKind.UNKNOWN to "invalid result", ReaderVerdicts.map(result("UNKNOWN", "NONE")))
        assertEquals(VerdictKind.UNKNOWN to "new thing", ReaderVerdicts.map(result("UNKNOWN", "NEW_THING")))
        assertEquals(VerdictKind.UNKNOWN to "no response", ReaderVerdicts.map(result("FOE", "NO_RESPONSE")))
    }

    @Test
    fun `refused ACKs become UNKNOWN reasons`() {
        assertEquals("gadget busy", ReaderVerdicts.ackReason("BUSY"))
        assertEquals("gadget key not set", ReaderVerdicts.ackReason("KEY_NOT_CONFIGURED"))
        assertEquals("gadget radio error", ReaderVerdicts.ackReason("RADIO_ERROR"))
        assertEquals("gadget refused request id", ReaderVerdicts.ackReason("INVALID_REQUEST_ID"))
        assertEquals("gadget refused command", ReaderVerdicts.ackReason("INVALID_COMMAND"))
        assertEquals("gadget refused command", ReaderVerdicts.ackReason("COMMAND_TOO_LONG"))
        assertEquals("gadget refused (odd one)", ReaderVerdicts.ackReason("ODD_ONE"))
    }

    @Test
    fun `the answer carries the packet facts and the reader's timing`() {
        val nonce = ByteArray(16) { 7 }
        val friend = ReaderVerdicts.answer(result("FRIEND", "NONE", responseMs = 460), nonce, 1, null, 480)
        assertEquals(VerdictKind.FRIEND, friend.verdict)
        assertNull(friend.reason)
        assertEquals("drone 1", friend.droneId)
        assertEquals(460L, friend.responseMs)
        assertEquals(480L, friend.linkRoundTripMs)
        assertNull(friend.rssiDbm)
        assertEquals(SignalKind.USB_GADGET, friend.source)

        val silent = ReaderVerdicts.answer(result("UNKNOWN", "NO_RESPONSE", received = false, responseMs = null), nonce, 1, null, 3010)
        assertEquals(3000L, silent.responseMs)
        assertNull("no display id without authentication", silent.droneId)

        val badTag = ReaderVerdicts.answer(result("UNKNOWN", "AUTH_FAILED"), null, 1, -60, 3010)
        assertNull(badTag.droneId)
        assertNull(badTag.nonce)
        assertEquals(-60, badTag.rssiDbm)
    }
}
