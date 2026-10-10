package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SIG-03 attribution on the USB path: an answer is only ever delivered to the challenge it belongs to. */
class AnswerRouterTest {
    private var now = 100_000L
    private val router = AnswerRouter(clock = { now })

    private fun answer(verdict: VerdictKind = VerdictKind.FRIEND, reason: String? = null, responseMs: Long = 42) =
        SignalEvent.Answer(verdict, reason, "RQ-07", ByteArray(16) { it.toByte() }, -58, responseMs, SignalKind.USB_GADGET)

    private fun line(a: SignalEvent.Answer, seq: Long? = null, boxId: Int? = null) = GadgetLineCodec.Line(a, seq, boxId)

    private val timeout = answer(VerdictKind.UNKNOWN, "no response", responseMs = 10_000)

    @Test
    fun `the matching answer is delivered and the gadget is free again`() = runBlocking {
        val open = router.begin(boxId = 3)
        now += 50
        assertEquals(AnswerRouter.Route.Delivered, router.route(line(answer(), seq = open.seq, boxId = 3)))
        assertEquals(VerdictKind.FRIEND, (open.inbox.receive() as SignalEvent.Answer).verdict)
        router.finish(open)
        assertFalse(router.isBusy())
        router.begin(boxId = 4) // no throw
        Unit
    }

    @Test
    fun `the gadget's late timeout answer for box A never reaches the challenge on box B`() {
        // Box A: the phone's 10 s window passes, its flow ends without the answer.
        val a = router.begin(boxId = 1)
        now += 10_000
        router.finish(a)
        assertTrue(router.isBusy())
        // A tap on box B in the gap is refused instead of inheriting A's answer.
        val busy = assertThrows(GadgetUnavailableException::class.java) { router.begin(boxId = 2) }
        assertEquals(AnswerRouter.BUSY, busy.reason)
        // A's UNKNOWN arrives (gadget clock: 10 s after its radio send) and is swallowed.
        now += 120
        assertEquals(AnswerRouter.Route.Dropped("late answer for box 1 ignored"), router.route(line(timeout, seq = a.seq)))
        assertFalse(router.isBusy())
        // Now B can be challenged, and only B's own answer reaches it.
        val b = router.begin(boxId = 2)
        assertTrue(b.seq > a.seq)
        assertEquals(AnswerRouter.Route.Delivered, router.route(line(answer(), seq = b.seq, boxId = 2)))
    }

    @Test
    fun `busy ends by itself 11_5 s after the silent challenge was written`() {
        val a = router.begin(boxId = 1)
        now += 10_000
        router.finish(a)
        now += 1_499
        assertTrue(router.isBusy())
        now += 1
        assertFalse(router.isBusy())
        router.begin(boxId = 2)
    }

    @Test
    fun `an answer echoing another seq or box is dropped, not delivered`() {
        val open = router.begin(boxId = 5)
        now += 100
        assertTrue(router.route(line(answer(), seq = open.seq + 7, boxId = 5)) is AnswerRouter.Route.Dropped)
        assertTrue(router.route(line(answer(), seq = null, boxId = 6)) is AnswerRouter.Route.Dropped)
        assertTrue(open.inbox.isEmpty)
        assertEquals(AnswerRouter.Route.Delivered, router.route(line(answer(), seq = open.seq, boxId = 5)))
    }

    @Test
    fun `without an echo an answer older than this challenge's write is dropped`() {
        // Firmware without seq: after busy expired, A's stale timeout answer shows up 0.2 s into B's challenge.
        val a = router.begin(boxId = 1)
        now += 10_000
        router.finish(a)
        now += 1_500
        val b = router.begin(boxId = 2)
        now += 200
        assertTrue(router.route(line(timeout)) is AnswerRouter.Route.Dropped)
        assertTrue(b.inbox.isEmpty)
        // B's own answer (gadget 150 ms, phone 400 ms since the write) passes.
        now += 200
        assertEquals(AnswerRouter.Route.Delivered, router.route(line(answer(responseMs = 150))))
    }

    @Test
    fun `status lines go to the open challenge, otherwise to the listener`() {
        val status = GadgetLineCodec.Line(SignalEvent.Status("fw 0.1"))
        assertEquals(AnswerRouter.Route.Unclaimed, router.route(status))
        val open = router.begin(boxId = 1)
        assertEquals(AnswerRouter.Route.Delivered, router.route(status))
        assertEquals(1, generateSequence { open.inbox.tryReceive().getOrNull() }.count())
        router.finish(open)
        assertEquals(AnswerRouter.Route.Unclaimed, router.route(status))
    }

    @Test
    fun `an answer with nothing open and nothing stale is unclaimed`() {
        assertEquals(AnswerRouter.Route.Unclaimed, router.route(line(answer())))
    }

    @Test
    fun `unplug ends the open challenge with the cause and forgets the stale one`() = runBlocking {
        val open = router.begin(boxId = 1)
        router.failAll(GadgetUnavailableException("gadget unplugged"))
        val e = runCatching { open.inbox.receive() }.exceptionOrNull()
        assertTrue(e is GadgetUnavailableException && e.reason == "gadget unplugged")
        assertFalse(router.isBusy())

        val silent = router.begin(boxId = 2)
        router.finish(silent)
        assertTrue(router.isBusy())
        router.failAll(GadgetUnavailableException("gadget disconnected"))
        assertFalse(router.isBusy()) // a re-plugged gadget starts clean
    }

    @Test
    fun `a write failure leaves nothing stale`() = runBlocking {
        val open = router.begin(boxId = 1)
        router.abort(open)
        assertFalse(router.isBusy())
        assertTrue(runCatching { open.inbox.receive() }.exceptionOrNull() is ClosedReceiveChannelException)
    }

    @Test
    fun `an open challenge whose flow was never collected heals after the gadget timeout`() {
        router.begin(boxId = 1)
        assertThrows(GadgetUnavailableException::class.java) { router.begin(boxId = 2) }
        now += 11_500
        router.begin(boxId = 2)
    }
}
