package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReChecksTest {
    private var counter = 0

    private fun nonce(): ByteArray = ByteArray(16) { i -> if (i == 15) (counter++).toByte() else 0x5A }

    private fun answer(
        verdict: VerdictKind = VerdictKind.FRIEND,
        reason: String? = null,
        nonce: ByteArray = nonce(),
        rssi: Int? = -58,
        responseMs: Long = 42,
    ) = SignalEvent.Answer(verdict, reason, "RQ-07", nonce, rssi, responseMs, SignalKind.USB_GADGET)

    @Test
    fun `an honest answer passes through as FRIEND`() {
        val r = ReChecks().check(answer(), roundTripMs = 300)
        assertEquals(VerdictKind.FRIEND, r.verdict)
        assertNull(r.reason)
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun `a nonce seen before is a replayed nonce`() {
        val checks = ReChecks()
        val n = nonce()
        assertEquals(VerdictKind.FRIEND, checks.check(answer(nonce = n), 300).verdict)
        val r = checks.check(answer(nonce = n.copyOf()), 300)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("replayed nonce", r.reason)
        assertEquals(1, checks.seenCount)
    }

    @Test
    fun `gadget responseMs over the budget is a late response`() {
        val r = ReChecks().check(answer(responseMs = 3450), roundTripMs = 3500)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("late response", r.reason)
    }

    @Test
    fun `phone-measured round trip over the budget is a late response even if the gadget was quick`() {
        val r = ReChecks().check(answer(responseMs = 42), roundTripMs = 2600)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("late response", r.reason)
    }

    @Test
    fun `exactly the budget is still in time`() {
        val r = ReChecks().check(answer(responseMs = 2500), roundTripMs = 2500)
        assertEquals(VerdictKind.FRIEND, r.verdict)
    }

    @Test
    fun `rssi below the floor is a signal mismatch`() {
        val r = ReChecks().check(answer(rssi = -75), roundTripMs = 300)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("signal mismatch", r.reason)
        assertEquals(VerdictKind.FRIEND, ReChecks().check(answer(rssi = -70), 300).verdict)
    }

    @Test
    fun `missing rssi is skipped with a note unless strict`() {
        val lenient = ReChecks().check(answer(rssi = null), roundTripMs = 300)
        assertEquals(VerdictKind.FRIEND, lenient.verdict)
        assertTrue(lenient.notes.any { it.contains("rssi not reported") })

        val strict = ReChecks(ReChecks.Config(strict = true)).check(answer(rssi = null), roundTripMs = 300)
        assertEquals(VerdictKind.FOE, strict.verdict)
        assertEquals("signal mismatch", strict.reason)
    }

    @Test
    fun `custom budgets are honoured`() {
        val checks = ReChecks(ReChecks.Config(rttBudgetMs = 100, rssiFloorDbm = -50))
        assertEquals("late response", checks.check(answer(responseMs = 150), 50).reason)
        assertEquals("signal mismatch", checks.check(answer(rssi = -58), 50).reason)
    }

    @Test
    fun `the gadget's own FOE verdict and reason pass through`() {
        val r = ReChecks().check(answer(VerdictKind.FOE, "bad tag"), roundTripMs = 300)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("bad tag", r.reason)
    }

    @Test
    fun `the gadget's FOE reason is kept when the phone also finds something`() {
        val r = ReChecks().check(answer(VerdictKind.FOE, "position mismatch", rssi = -80), roundTripMs = 300)
        assertEquals(VerdictKind.FOE, r.verdict)
        assertEquals("position mismatch", r.reason)
        assertTrue(r.notes.any { it.contains("signal mismatch") })
    }

    @Test
    fun `UNKNOWN no response passes through untouched`() {
        val r = ReChecks().check(
            SignalEvent.Answer(VerdictKind.UNKNOWN, "no response", null, nonce(), null, 10000, SignalKind.USB_GADGET),
            roundTripMs = 10050,
        )
        assertEquals(VerdictKind.UNKNOWN, r.verdict)
        assertEquals("no response", r.reason)
    }

    @Test
    fun `nonce check comes before the timing check`() {
        val checks = ReChecks()
        val n = nonce()
        checks.check(answer(nonce = n), 300)
        val r = checks.check(answer(nonce = n.copyOf(), responseMs = 9000), 9000)
        assertEquals("replayed nonce", r.reason)
        assertTrue(r.notes.any { it.contains("late response") })
    }

    @Test
    fun `reset forgets the ledger`() {
        val checks = ReChecks()
        val n = nonce()
        checks.check(answer(nonce = n), 300)
        checks.reset()
        assertEquals(VerdictKind.FRIEND, checks.check(answer(nonce = n.copyOf()), 300).verdict)
    }

    @Test
    fun `a missing nonce skips only the replay check, with a note`() {
        val checks = ReChecks()
        val missing = SignalEvent.Answer(VerdictKind.FRIEND, null, "drone 1", null, null, 460, SignalKind.USB_GADGET)
        val r = checks.check(missing, 480)
        assertEquals(VerdictKind.FRIEND, r.verdict)
        assertTrue(r.notes.contains("nonce not reported; replay check skipped"))
        assertTrue(r.notes.contains("rssi not reported; signal check skipped"))
        assertEquals(0, checks.seenCount)
        assertEquals(VerdictKind.FRIEND, checks.check(missing, 480).verdict)
        assertEquals("late response", checks.check(missing.copy(responseMs = 2600), 2700).reason)
    }
}
