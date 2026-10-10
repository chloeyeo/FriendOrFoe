package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.FakeReaderPort
import com.chloeyeo.friendorfoe.signal.Fixtures
import com.chloeyeo.friendorfoe.signal.ReaderLink
import com.chloeyeo.friendorfoe.signal.SignalEvent
import com.chloeyeo.friendorfoe.signal.SignalKind
import com.chloeyeo.friendorfoe.ui.overlay.Tone
import com.chloeyeo.friendorfoe.ui.overlay.tagFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end through the real protocol engine: tap -> [IffCoordinator.challenge] -> [ReaderLink] writes
 * `QUERY <id>` to a fake serial port that answers with Ihan's captured reader lines -> mapping -> [ReChecks] ->
 * the ring on the tapped box.
 */
class IffCoordinatorReaderTest {
    private class ScriptedDetector(private val frame: List<Detection>) : Detector {
        override val name: String = "scripted"
        override fun detect(frame: Frame): List<Detection> = this.frame
    }

    private var clock = 1_000_000L
    private val oneDrone = listOf(Detection(ObjClass.DRONE, 0.6f, BoxF(10f, 10f, 50f, 40f)))

    private fun frame() = Frame(width = 128, height = 72, rgba = ByteArray(128 * 72 * 4))

    private class Rig(val c: IffCoordinator, val link: ReaderLink, val port: FakeReaderPort)

    private fun rig(): Rig {
        val link = ReaderLink(idPrefix = "e2e00001")
        val port = FakeReaderPort(link)
        link.attach(port)
        val c = IffCoordinator(
            ScriptedDetector(oneDrone),
            link,
            clock = { clock },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        link.listener = object : ReaderLink.Listener {
            override fun onLine(text: String) = c.log(text)
            override fun onStaleAnswer(answer: SignalEvent.Answer, why: String) = c.lateAnswer(answer, why)
        }
        c.tick(frame())
        return Rig(c, link, port)
    }

    private fun Rig.droneId(): Int = c.state.value.tracks.first { it.cls == ObjClass.DRONE }.id

    /** Taps the drone and waits (real time) until its ring shows a verdict. */
    private fun Rig.tap(script: List<String>?, waitMs: Long = 3_000): IffState.Verdict {
        script?.let { port.queries += it }
        val id = droneId()
        c.challenge(id)
        val deadline = System.currentTimeMillis() + waitMs
        while (c.state.value.tracks.first { it.id == id }.iff !is IffState.Verdict && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        return c.state.value.tracks.first { it.id == id }.iff as IffState.Verdict
    }

    private fun Rig.tag(): String = tagFor(c.state.value.tracks.first { it.id == droneId() }, clock).text

    private fun Rig.nextTap() {
        clock += IffCoordinator.VERDICT_HOLD_MS
        c.tick(frame())
    }

    @Test
    fun `the captured FRIEND exchange draws a green FRIEND ring on the tapped box`() {
        val r = rig()
        val v = r.tap(Fixtures.lines("friend.txt"))
        assertEquals(VerdictKind.FRIEND, v.kind)
        assertNull(v.reason)
        assertEquals("drone 1", v.droneId)
        assertNull(v.rssiDbm)
        assertEquals(SignalKind.USB_GADGET, v.source)
        assertTrue(r.tag(), r.tag().startsWith("FRIEND · drone 1 · "))
        assertEquals(Tone.GREEN, tagFor(r.c.state.value.tracks.first { it.id == r.droneId() }, clock).tone)
        val texts = r.c.state.value.events.map { it.text }
        assertTrue(texts.toString(), texts.any { it.startsWith("QUERY e2e00001-1-${r.droneId()}") })
        assertTrue(texts.any { it.startsWith("FRIEND · drone 1 · box ${r.droneId()}") && it.contains("gadget 460 ms") && it.contains("RSSI n/a") })
        assertEquals(listOf("QUERY e2e00001-1-${r.droneId()}"), r.port.queryLines)
    }

    @Test
    fun `the same captured nonce on a second tap is FOE replayed nonce from the phone's ledger`() {
        val r = rig()
        assertEquals(VerdictKind.FRIEND, r.tap(Fixtures.lines("friend.txt")).kind)
        r.nextTap()
        val v = r.tap(Fixtures.lines("friend.txt"))
        assertEquals(VerdictKind.FOE, v.kind)
        assertEquals("replayed nonce", v.reason)
        assertEquals("FOE · replayed nonce", r.tag())
        assertEquals(2, r.port.queryLines.distinct().size)
    }

    @Test
    fun `NO_RESPONSE, bad tag, a replayed RESULT and BUSY each draw their ring`() {
        val r = rig()
        val cases = listOf(
            "no_response.txt" to "UNKNOWN · no response",
            "bad_tag.txt" to "FOE · bad tag",
            "replay.txt" to "UNKNOWN · stale result",
            "busy.txt" to "UNKNOWN · gadget busy",
        )
        for ((fixture, ring) in cases) {
            val v = r.tap(Fixtures.lines(fixture))
            assertEquals(fixture, ring, r.tag())
            assertTrue(fixture, v.kind != VerdictKind.FRIEND)
            r.nextTap()
            // Back to IDLE for the next case (busy.txt leaves the reader in COOLDOWN).
            r.port.feed(listOf("""{"event":"STATE","boot_id":"@BOOT@","request_id":"x","state":"IDLE","reason":"CHANGED"}"""))
        }
    }

    @Test
    fun `boot text, DIAG and ignored packets around a challenge only reach the event log`() {
        val r = rig()
        r.port.feed(Fixtures.lines("boot.txt"))
        val v = r.tap(Fixtures.lines("ignored.txt") + Fixtures.lines("diag.txt") + Fixtures.lines("friend.txt"))
        assertEquals(VerdictKind.FRIEND, v.kind)
        val texts = r.c.state.value.events.map { it.text }
        assertTrue(texts.contains("gadget: KEY: stored"))
        assertTrue(texts.contains("gadget dropped 3 packet log lines"))
        assertTrue(texts.contains("gadget result for another request ignored · FRIEND NONE"))
    }

    @Test
    fun `a FRIEND recovered from STATUS at 3_5 s is timed by the reader, not by the STATUS arrival`() {
        val r = rig()
        val f = Fixtures.lines("friend.txt")
        r.port.statusScript = Fixtures.lines("status_last_result.txt")
        // ACK and TX_CHALLENGE, then the RESULT line is lost; the real 3.5 s recovery STATUS brings it back.
        val v = r.tap(listOf(f[0], f[2]), waitMs = 6_000)
        assertEquals(r.tag(), VerdictKind.FRIEND, v.kind)
        assertNull(v.reason)
        assertEquals(460L, v.roundTripMs)
        assertEquals(listOf("QUERY e2e00001-1-${r.droneId()}"), r.port.queryLines)
        val texts = r.c.state.value.events.map { it.text }
        assertTrue(texts.toString(), texts.contains("result recovered from STATUS"))
    }

    @Test
    fun `no gadget attached is UNKNOWN no gadget at once`() {
        val link = ReaderLink(idPrefix = "e2e00001")
        val c = IffCoordinator(ScriptedDetector(oneDrone), link, clock = { clock }, scope = CoroutineScope(Dispatchers.Unconfined))
        c.tick(frame())
        val id = c.state.value.tracks.first().id
        c.challenge(id)
        val v = c.state.value.tracks.first().iff as IffState.Verdict
        assertEquals(VerdictKind.UNKNOWN, v.kind)
        assertEquals("no gadget", v.reason)
    }
}
