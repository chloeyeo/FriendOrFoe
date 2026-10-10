package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.ChallengeRequest
import com.chloeyeo.friendorfoe.signal.FakeMode
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import com.chloeyeo.friendorfoe.signal.GadgetUnavailableException
import com.chloeyeo.friendorfoe.signal.SignalEvent
import com.chloeyeo.friendorfoe.signal.SignalKind
import com.chloeyeo.friendorfoe.signal.SignalSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap -> signal -> re-check -> verdict through [IffCoordinator.challenge] (Phase 5). The scope is unconfined, so a
 * source that answers at once (or when the test completes its deferred) lands synchronously; wall clock and the
 * monotonic round-trip clock are both injected.
 */
class IffCoordinatorChallengeTest {
    private class ScriptedDetector(private val frames: ArrayDeque<List<Detection>>) : Detector {
        override val name: String = "scripted"
        override fun detect(frame: Frame): List<Detection> = frames.removeFirstOrNull() ?: emptyList()
    }

    /** Answers each challenge when the test completes [next]; records every request. */
    private class ManualSource : SignalSource {
        override val kind: SignalKind = SignalKind.USB_GADGET
        val requests = ArrayList<ChallengeRequest>()
        var next = CompletableDeferred<SignalEvent.Answer>()
        var fail: GadgetUnavailableException? = null

        override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
            requests += req
            fail?.let { throw it }
            val answer = next
            return flow {
                emit(SignalEvent.Status("challenge sent · box ${req.boxId}"))
                emit(answer.await())
            }
        }
    }

    private val nonceA = ByteArray(16) { it.toByte() }
    private val nonceB = ByteArray(16) { (it + 100).toByte() }

    private fun answer(
        verdict: VerdictKind = VerdictKind.FRIEND,
        reason: String? = null,
        nonce: ByteArray = nonceA,
        rssi: Int? = -58,
        responseMs: Long = 42,
    ) = SignalEvent.Answer(verdict, reason, "RQ-07", nonce, rssi, responseMs, SignalKind.USB_GADGET)

    private fun drone(l: Float, t: Float, r: Float, b: Float) = Detection(ObjClass.DRONE, 0.6f, BoxF(l, t, r, b))

    private fun frame() = Frame(width = 128, height = 72, rgba = ByteArray(128 * 72 * 4))

    private var clock = 1_000_000L
    private var elapsed = 50_000L

    private fun coordinator(source: SignalSource, vararg frames: List<Detection>): IffCoordinator {
        val detector = ScriptedDetector(ArrayDeque(frames.toList()))
        return IffCoordinator(
            detector,
            source,
            clock = { clock },
            challengerId = { 42 },
            elapsedMillis = { elapsed },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun IffCoordinator.iffOf(id: Int): IffState = state.value.tracks.single { it.id == id }.iff

    private fun IffCoordinator.droneId(): Int = state.value.tracks.first { it.cls == ObjClass.DRONE }.id

    private val oneDrone = listOf(drone(10f, 10f, 50f, 40f))

    @Test
    fun `an honest fake answer becomes FRIEND on the tapped box with a signal row and an event row`() {
        val c = coordinator(FakeSignalSource(FakeMode.HONEST, delayMs = { 0L }), oneDrone)
        c.tick(frame())
        val id = c.droneId()

        c.challenge(id)

        val v = c.iffOf(id) as IffState.Verdict
        assertEquals(VerdictKind.FRIEND, v.kind)
        assertEquals("RQ-07", v.droneId)
        assertEquals(-58, v.rssiDbm)
        assertEquals(0L, v.roundTripMs)
        assertNull(c.lock)
        val row = c.state.value.signal.single()
        assertEquals(VerdictKind.FRIEND, row.verdict)
        assertEquals("RQ-07", row.droneId)
        val event = c.state.value.events.first()
        assertEquals(VerdictKind.FRIEND, event.verdict)
        assertEquals(id, event.trackId)
        assertTrue(event.text, event.text.startsWith("FRIEND · RQ-07 · box $id"))
        assertTrue(event.text, event.text.endsWith("(fake)"))
        assertEquals(-58, c.state.value.status.rssiDbm)
        assertEquals(1, c.reChecks.seenCount)
    }

    @Test
    fun `the challenge line carries the box, the challenger id and unix seconds`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        assertEquals(listOf(ChallengeRequest(id, 42, clock / 1000)), source.requests)
        assertTrue(c.iffOf(id) is IffState.Challenged)
        assertTrue(c.state.value.events.any { it.text == "challenge sent · box $id" })
    }

    @Test
    fun `the phone-measured round trip goes on the verdict`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        elapsed += 120
        source.next.complete(answer())
        assertEquals(120L, (c.iffOf(id) as IffState.Verdict).roundTripMs)
    }

    @Test
    fun `a nonce answered twice is FOE replayed nonce`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        source.next.complete(answer(nonce = nonceA))
        assertEquals(VerdictKind.FRIEND, (c.iffOf(id) as IffState.Verdict).kind)

        clock += IffCoordinator.VERDICT_HOLD_MS
        c.tick(frame())
        assertEquals(IffState.Unchallenged, c.iffOf(id))
        source.next = CompletableDeferred()
        c.challenge(id)
        source.next.complete(answer(nonce = nonceA))

        val v = c.iffOf(id) as IffState.Verdict
        assertEquals(VerdictKind.FOE, v.kind)
        assertEquals("replayed nonce", v.reason)
        assertTrue(c.state.value.events.first().text.contains("phone: replayed nonce"))
        assertEquals(2, c.state.value.signal.size)
    }

    @Test
    fun `the fake REPLAY mode is FOE replayed nonce`() {
        val c = coordinator(FakeSignalSource(FakeMode.REPLAY, delayMs = { 0L }), oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        val v = c.iffOf(id) as IffState.Verdict
        assertEquals(VerdictKind.FOE, v.kind)
        assertEquals("replayed nonce", v.reason)
    }

    @Test
    fun `the fake LATE mode is FOE late response`() {
        val c = coordinator(FakeSignalSource(FakeMode.LATE, delayMs = { 0L }), oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        val v = c.iffOf(id) as IffState.Verdict
        assertEquals(VerdictKind.FOE, v.kind)
        assertEquals("late response", v.reason)
    }

    @Test
    fun `a slow phone round trip is FOE late response even when the gadget was quick`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        elapsed += 3_000
        clock += 3_000
        source.next.complete(answer(responseMs = 40))
        val v = c.iffOf(id) as IffState.Verdict
        assertEquals(VerdictKind.FOE, v.kind)
        assertEquals("late response", v.reason)
        assertEquals(3_000L, v.roundTripMs)
    }

    @Test
    fun `the fake WEAK mode is FOE signal mismatch`() {
        val c = coordinator(FakeSignalSource(FakeMode.WEAK, delayMs = { 0L }), oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        assertEquals("signal mismatch", (c.iffOf(id) as IffState.Verdict).reason)
    }

    @Test
    fun `silence stays Challenged and ends UNKNOWN no response when the window closes`() {
        val c = coordinator(FakeSignalSource(FakeMode.SILENT), oneDrone, oneDrone, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        clock += 9_000
        c.tick(frame())
        assertTrue(c.iffOf(id) is IffState.Challenged)

        clock += 1_001
        c.tick(frame())
        assertEquals(IffState.Verdict(VerdictKind.UNKNOWN, "no response", null, null, null), c.iffOf(id))
        assertNull(c.lock)
        assertEquals(VerdictKind.UNKNOWN, c.state.value.signal.single().verdict)
        assertEquals(VerdictKind.UNKNOWN, c.state.value.events.first().verdict)
    }

    @Test
    fun `no gadget is UNKNOWN no gadget at once`() {
        val source = ManualSource().apply { fail = GadgetUnavailableException("no gadget") }
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        assertEquals(IffState.Verdict(VerdictKind.UNKNOWN, "no gadget", null, null, null), c.iffOf(id))
        assertNull(c.lock)
    }

    @Test
    fun `the answer goes to the tapped box even after the detector lost it`() {
        val source = ManualSource()
        // Frame 1: drone A. Frame 2 (6 s later, past the 5 s drone window): only a new drone B far away.
        val c = coordinator(source, oneDrone, listOf(drone(90f, 40f, 120f, 60f)))
        c.setPreviewSize(256, 144)
        c.tick(frame())
        val a = c.droneId()
        c.challenge(a)
        clock += 6_000
        c.tick(frame())
        val b = c.state.value.tracks.single { it.id != a }.id
        assertTrue(c.state.value.tracks.single { it.id == a }.iff is IffState.Challenged)

        source.next.complete(answer())

        val tracks = c.state.value.tracks
        val tappedUi = tracks.single { it.id == a }
        assertEquals(VerdictKind.FRIEND, (tappedUi.iff as IffState.Verdict).kind)
        assertEquals(BoxF(20f, 20f, 100f, 80f), tappedUi.box) // the frozen tap rect, in preview pixels
        assertEquals(IffState.Unchallenged, tracks.single { it.id == b }.iff)
        assertEquals(a, c.state.value.events.first().trackId)
    }

    @Test
    fun `a second tap during the window is refused and sends nothing`() {
        val source = ManualSource()
        val c = coordinator(source, listOf(drone(10f, 10f, 30f, 25f), drone(80f, 40f, 110f, 60f)))
        c.tick(frame())
        val (a, b) = c.state.value.tracks.map { it.id }
        c.challenge(a)
        c.challenge(b)
        c.challenge(a)
        assertEquals(1, source.requests.size)
        assertEquals(IffState.Unchallenged, c.iffOf(b))
    }

    @Test
    fun `a verdict is held, then the box can be challenged again`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone, oneDrone, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        source.next.complete(answer())

        clock += IffCoordinator.VERDICT_HOLD_MS - 1
        c.tick(frame())
        assertTrue(c.iffOf(id) is IffState.Verdict)
        source.next = CompletableDeferred()
        c.challenge(id) // still showing its verdict: refused
        assertEquals(1, source.requests.size)

        clock += 1
        c.tick(frame())
        assertEquals(IffState.Unchallenged, c.iffOf(id))
        c.challenge(id)
        assertEquals(2, source.requests.size)
        assertTrue(c.iffOf(id) is IffState.Challenged)
    }

    @Test
    fun `an answer after the window is logged and ignored`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        clock += 10_001
        c.tick(frame())
        assertEquals(VerdictKind.UNKNOWN, (c.iffOf(id) as IffState.Verdict).kind)

        source.next.complete(answer())

        assertEquals(VerdictKind.UNKNOWN, (c.iffOf(id) as IffState.Verdict).kind)
        assertTrue(c.state.value.events.first().text.startsWith("late answer ignored · FRIEND · nonce 00010203…"))
        assertEquals(1, c.state.value.signal.size)
        assertEquals(1, c.reChecks.seenCount) // the late nonce is still spent
    }

    @Test
    fun `an answer that arrives after a reset is ignored and the reset state stays clean`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        val id = c.droneId()
        c.challenge(id)
        c.reset()
        source.next.complete(answer(nonce = nonceB))
        val s = c.state.value
        assertTrue(s.tracks.isEmpty())
        assertTrue(s.signal.isEmpty())
        assertTrue(s.events.single().text.startsWith("late answer ignored"))
    }

    @Test
    fun `reset clears verdicts, signal rows and the nonce ledger`() {
        val c = coordinator(FakeSignalSource(FakeMode.HONEST, delayMs = { 0L }), oneDrone, oneDrone)
        c.tick(frame())
        c.challenge(c.droneId())
        assertEquals(1, c.reChecks.seenCount)
        c.reset()
        assertEquals(0, c.reChecks.seenCount)
        c.tick(frame())
        val s = c.state.value
        assertTrue(s.signal.isEmpty() && s.events.isEmpty())
        assertTrue(s.tracks.all { it.iff == IffState.Unchallenged })
    }

    @Test
    fun `log lines never carry a full nonce`() {
        val source = ManualSource()
        val c = coordinator(source, oneDrone)
        c.tick(frame())
        c.challenge(c.droneId())
        source.next.complete(answer())
        val full = com.chloeyeo.friendorfoe.signal.Hex.encode(nonceA)
        assertTrue(c.state.value.events.none { full in it.text })
        assertTrue(c.state.value.events.first().text.contains("nonce 00010203…"))
    }
}
