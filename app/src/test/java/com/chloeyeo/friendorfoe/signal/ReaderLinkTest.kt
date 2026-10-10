package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderLink] against [FakeReaderPort] playing Ihan's reader from the captured fixtures. Timeouts are shortened
 * (tens of ms of real time); the handshake tests drive [ReaderLink.tick] with a fake clock.
 */
class ReaderLinkTest {
    private val fast = ReaderLink.Timing(
        ackProbeMs = 100, ackMs = 150, resultAfterAckMs = 250, recoveryStatusMs = 200, idleWaitMs = 150, nonceGraceMs = 60,
        readyWaitMs = 150, handshakeRetryMs = 50, handshakeGiveUpMs = 200,
    )

    private val log = ArrayList<String>()
    private val readerNotes = ArrayList<Pair<Boolean, String>>()
    private val stale = ArrayList<Pair<SignalEvent.Answer, String>>()

    private val listener = object : ReaderLink.Listener {
        override fun onLine(text: String) {
            synchronized(log) { log += text }
        }

        override fun onReader(ready: Boolean, note: String) {
            synchronized(readerNotes) { readerNotes += ready to note }
        }

        override fun onStaleAnswer(answer: SignalEvent.Answer, why: String) {
            synchronized(stale) { stale += answer to why }
        }
    }

    private fun attached(timing: ReaderLink.Timing = fast, clock: () -> Long = { System.nanoTime() / 1_000_000L }): Pair<ReaderLink, FakeReaderPort> {
        val link = ReaderLink(clock = clock, idPrefix = "t3st0001", timing = timing)
        link.listener = listener
        val port = FakeReaderPort(link)
        link.attach(port)
        return link to port
    }

    private class Outcome(val events: List<SignalEvent>, val error: String?) {
        val answer: SignalEvent.Answer? get() = events.filterIsInstance<SignalEvent.Answer>().singleOrNull()
        val statusTexts: List<String> get() = events.filterIsInstance<SignalEvent.Status>().map { it.text }
    }

    private fun ReaderLink.run(box: Int = 3): Outcome {
        val events = ArrayList<SignalEvent>()
        val error = try {
            runBlocking { challenge(ChallengeRequest(box, 42, 1_760_000_000L)).collect { events += it } }
            null
        } catch (e: GadgetUnavailableException) {
            e.reason
        }
        return Outcome(events, error)
    }

    private fun lines(name: String) = Fixtures.lines(name)

    @Test
    fun `attach handshakes with STATUS and the reader is ready`() {
        val (link, port) = attached()
        assertEquals(listOf("STATUS t3st0001-s1"), port.written)
        val info = link.reader.value!!
        assertEquals("ae07effed99ac4d4", info.bootId)
        assertEquals("IDLE", info.state)
        assertTrue(info.keyConfigured && info.radioReady)
        assertTrue(readerNotes.single().first)
        assertTrue(readerNotes.single().second, readerNotes.single().second.startsWith("reader ready · boot ae07effe"))
    }

    @Test
    fun `the handshake is retried every second and gives up after five`() {
        var now = 1_000L
        val link = ReaderLink(clock = { now }, idPrefix = "t3st0001")
        link.listener = listener
        val port = FakeReaderPort(link).apply { statusScript = null }
        link.attach(port)
        link.tick()
        now += 999; link.tick()
        assertEquals(1, port.written.size)
        now += 1; link.tick()
        assertEquals(listOf("STATUS t3st0001-s1", "STATUS t3st0001-s2"), port.written)
        now += 4_000; link.tick()
        assertEquals(2, port.written.size)
        assertEquals(false to "reader did not answer STATUS in 5 s (not a FoF reader v2?)", readerNotes.last())
        now += 5_000; link.tick()
        assertNull(link.reader.value)
    }

    @Test
    fun `a reader that boots after the port opened is picked up from its boot STATUS`() {
        val link = ReaderLink(idPrefix = "t3st0001", timing = fast)
        link.listener = listener
        val port = FakeReaderPort(link).apply { statusScript = null } // the board is in its 1 s boot delay
        link.attach(port)
        assertNull(link.reader.value)
        port.feed(lines("boot.txt"))
        assertEquals("ae07effed99ac4d4", link.reader.value?.bootId)
        assertTrue(log.contains("gadget: KEY: stored"))
        assertTrue(log.any { it.startsWith("gadget: RADIO: BLE5") })
        assertEquals(2, log.size) // the wake LF and the JSON lines are not logged as text
    }

    @Test
    fun `the captured FRIEND exchange becomes FRIEND with the nonce of its TX_CHALLENGE`() {
        val (link, port) = attached()
        port.queries += lines("friend.txt")
        val out = link.run(box = 3)
        assertNull(out.error)
        val a = out.answer!!
        assertEquals(VerdictKind.FRIEND, a.verdict)
        assertNull(a.reason)
        assertEquals("drone 1", a.droneId)
        assertEquals("44ad3f50c8539c3d2121b4cb9e7e1e25", a.nonceHex)
        assertEquals(460L, a.responseMs)
        assertNull(a.rssiDbm)
        assertTrue(a.linkRoundTripMs!! in 0L..1_000L)
        assertEquals(listOf("QUERY t3st0001-1-3"), port.queryLines)
        assertTrue(out.statusTexts.toString(), out.statusTexts.contains("challenge on air · nonce 44ad3f50…"))
        assertEquals("IDLE", link.reader.value?.state)
        assertTrue(port.written.none { it.startsWith("KEY") })
    }

    @Test
    fun `the captured NO_RESPONSE exchange becomes UNKNOWN no response`() {
        val (link, port) = attached()
        port.queries += lines("no_response.txt")
        val a = link.run().answer!!
        assertEquals(VerdictKind.UNKNOWN, a.verdict)
        assertEquals("no response", a.reason)
        assertNull(a.droneId)
        assertEquals(3000L, a.responseMs)
        assertEquals("c45efe0edcd984f4dd1a3f7df0ac7ef8", a.nonceHex)
    }

    @Test
    fun `a bad tag is FOE bad tag`() {
        val (link, port) = attached()
        port.queries += lines("bad_tag.txt")
        val a = link.run().answer!!
        assertEquals(VerdictKind.FOE, a.verdict)
        assertEquals("bad tag", a.reason)
        assertNull(a.droneId)
        assertEquals(212L, a.responseMs)
    }

    @Test
    fun `a replayed RESULT is UNKNOWN stale result, never FRIEND or FOE`() {
        val (link, port) = attached()
        port.queries += lines("replay.txt")
        val out = link.run()
        val a = out.answer!!
        assertEquals(VerdictKind.UNKNOWN, a.verdict)
        assertEquals("stale result", a.reason)
        assertNull(a.droneId)
        assertNull(a.nonce)
        assertTrue(out.statusTexts.contains("reader: duplicate request id"))
        assertTrue(out.statusTexts.contains("reader did not log the challenge nonce"))
    }

    @Test
    fun `refused ACKs end the challenge at once with the reader's reason`() {
        val (link, port) = attached()
        port.queries += lines("busy.txt")
        assertEquals("gadget busy", link.run().error)
        assertEquals("COOLDOWN", link.reader.value?.state)
        port.feed(listOf("""{"event":"STATE","boot_id":"@BOOT@","request_id":"x","state":"IDLE","reason":"CHANGED"}"""))
        port.queries += lines("key_missing.txt")
        assertEquals("gadget key not set", link.run().error)
    }

    @Test
    fun `ignored packets and other hosts' lines never change the verdict`() {
        val (link, port) = attached()
        port.queries += lines("ignored.txt") + lines("friend.txt")
        val a = link.run().answer!!
        assertEquals(VerdictKind.FRIEND, a.verdict)
        assertEquals("44ad3f50c8539c3d2121b4cb9e7e1e25", a.nonceHex)
        assertTrue(log.toString(), log.contains("gadget ack accepted for 2f3bbff70e654663a1c7ecc725b4c1fc ignored"))
        assertTrue(log.contains("gadget result for another request ignored · FRIEND NONE"))
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `a RESULT before its TX_CHALLENGE waits briefly for the nonce`() {
        val (link, port) = attached()
        val f = lines("friend.txt")
        port.queries += listOf(f[0], f[1], f[3], f[2]) // ACK, STATE, RESULT, then TX_CHALLENGE
        assertEquals("44ad3f50c8539c3d2121b4cb9e7e1e25", link.run().answer!!.nonceHex)
    }

    @Test
    fun `DIAG lines are logged and shown on the challenge`() {
        val (link, port) = attached()
        val n = lines("no_response.txt")
        port.queries += listOf(n[0]) + lines("diag.txt") + n.drop(1)
        val out = link.run()
        assertEquals("no response", out.answer!!.reason)
        assertTrue(out.statusTexts.contains("reader radio diag · error true · overflow false"))
        assertTrue(log.contains("gadget dropped 3 packet log lines"))
        assertTrue(log.any { it.startsWith("gadget help · QUERY <request_id>") })
    }

    @Test
    fun `no ACK, no STATUS for the id and no STATE QUERYING within the ACK window is gadget no ack`() {
        val (link, port) = attached()
        val out = link.run()
        assertEquals("gadget no ack", out.error)
        assertEquals(listOf("STATUS t3st0001-s1", "QUERY t3st0001-1-3", "STATUS t3st0001-s2"), port.written)
        assertTrue(out.statusTexts.contains("no ACK yet · asking STATUS"))
    }

    /** status.txt as the reader prints it while it runs query @ID@. */
    private fun statusQuerying(): List<String> = lines("status.txt").map {
        it.replace("\"active_request_id\":\"\"", "\"active_request_id\":\"@ID@\"").replace("\"state\":\"IDLE\"", "\"state\":\"QUERYING\"")
    }

    /** Feeds the RESULT and the rest of friend.txt [afterMs] after the QUERY, from another thread. */
    private fun FakeReaderPort.resultLater(afterMs: Long) {
        val f = lines("friend.txt")
        Thread {
            Thread.sleep(afterMs)
            feed(f.drop(3))
        }.start()
    }

    @Test
    fun `a dropped ACK is covered by the 1_5 s STATUS whose active_request_id is the open query`() {
        val (link, port) = attached()
        port.statusScript = statusQuerying()
        port.queries += listOf(lines("friend.txt")[2]) // TX_CHALLENGE only: the ACK and the STATE line are dropped
        port.resultLater(afterMs = 240) // after the 150 ms ACK window
        val out = link.run()
        assertNull(out.error)
        assertEquals(VerdictKind.FRIEND, out.answer!!.verdict)
        assertTrue(out.statusTexts.toString(), out.statusTexts.contains("ACK lost · reader querying (STATUS)"))
        assertEquals("STATUS t3st0001-s2", port.written[2])
    }

    @Test
    fun `a dropped ACK is covered by a STATE QUERYING line for the open query`() {
        val (link, port) = attached()
        port.statusScript = null
        val f = lines("friend.txt")
        port.queries += listOf(f[1], f[2]) // STATE QUERYING and TX_CHALLENGE; the ACK is dropped
        port.resultLater(afterMs = 180) // after the 150 ms ACK window, inside the 250 ms after the STATE
        val out = link.run()
        assertNull(out.error)
        assertEquals(VerdictKind.FRIEND, out.answer!!.verdict)
        assertTrue(out.statusTexts.toString(), out.statusTexts.contains("ACK lost · reader querying (STATE)"))
    }

    @Test
    fun `a STATE QUERYING for another request id is not an ACK`() {
        val (link, port) = attached()
        port.statusScript = null
        port.queries += listOf("""{"event":"STATE","boot_id":"@BOOT@","request_id":"someoneelse","state":"QUERYING","reason":"CHANGED"}""")
        assertEquals("gadget no ack", link.run().error)
    }

    @Test
    fun `an ACK but no RESULT asks STATUS once, then ends gadget no result, not the radio's no response`() {
        val (link, port) = attached()
        port.queries += listOf(lines("friend.txt")[0])
        assertEquals("gadget no result", link.run().error)
        assertEquals(listOf("STATUS t3st0001-s1", "QUERY t3st0001-1-3", "STATUS t3st0001-s2"), port.written)
    }

    @Test
    fun `a lost RESULT is recovered from STATUS last_result`() {
        val (link, port) = attached()
        val f = lines("friend.txt")
        port.queries += listOf(f[0], f[2]) // ACK and TX_CHALLENGE, then the RESULT line is lost
        port.statusScript = lines("status_last_result.txt")
        val out = link.run()
        val a = out.answer!!
        assertEquals(VerdictKind.FRIEND, a.verdict)
        assertEquals("44ad3f50c8539c3d2121b4cb9e7e1e25", a.nonceHex)
        assertTrue(out.statusTexts.contains("result recovered from STATUS"))
        assertEquals("the reader's response_ms, not the STATUS arrival", 460L, a.linkRoundTripMs)
        assertTrue(out.statusTexts.contains("round trip from the reader's timing · 460 ms"))
    }

    @Test
    fun `a reboot mid-query ends it gadget rebooted and handshakes again`() {
        val (link, port) = attached()
        port.queries += listOf(
            lines("friend.txt")[0],
            """{"event":"STATE","boot_id":"0123456789abcdef","request_id":"","state":"IDLE","reason":"BOOT"}""",
        )
        port.bootId = "0123456789abcdef"
        assertEquals("gadget rebooted", link.run().error)
        assertEquals("STATUS t3st0001-s2", port.written.last())
        assertEquals("0123456789abcdef", link.reader.value?.bootId)
        assertTrue(readerNotes.contains(false to "reader rebooted · checking STATUS"))
    }

    @Test
    fun `download mode refuses the tap and says how to recover`() {
        val (link, port) = attached()
        port.feed(listOf("ESP-ROM:esp32c3-api1-20210207", "waiting for download"))
        assertEquals("gadget in download mode", link.run().error)
        assertEquals(false to "reader in download mode · release BOOT, then press RESET or re-plug", readerNotes.last())
    }

    @Test
    fun `download mode is cleared by the reader's boot STATUS after RESET`() {
        val (link, port) = attached()
        port.feed(listOf("ESP-ROM:esp32c3-api1-20210207", "waiting for download"))
        assertEquals("gadget in download mode", link.run().error)
        port.feed(lines("status.txt"))
        port.queries += lines("friend.txt")
        assertEquals(VerdictKind.FRIEND, link.run().answer!!.verdict)
    }

    @Test
    fun `download mode is cleared by STATE BOOT even before a STATUS arrives`() {
        val (link, port) = attached()
        port.feed(listOf("waiting for download"))
        port.statusScript = null
        port.feed(listOf("""{"event":"STATE","boot_id":"@BOOT@","request_id":"","state":"IDLE","reason":"BOOT"}"""))
        assertEquals("gadget not ready", link.run().error)
        assertEquals("STATUS t3st0001-s2", port.written[1]) // the boot restarted the handshake
    }

    @Test
    fun `a board that is not a reader v2 is never queried`() {
        val (link, port) = attached()
        port.feed(listOf(Fixtures.lines("status.txt").single().replace("\"role\":\"reader\"", "\"role\":\"drone\"")))
        assertNull(link.reader.value)
        assertEquals(false to "USB device is not a FoF reader v2 (role drone, interface 2)", readerNotes.last())
        port.statusScript = null
        assertEquals("gadget not ready", link.run().error)
        assertTrue(port.queryLines.isEmpty())
    }

    @Test
    fun `a tap during the cooldown waits for STATE IDLE before its QUERY`() {
        val (link, port) = attached(fast.copy(idleWaitMs = 2_000))
        port.queries += lines("friend.txt").dropLast(1) // ends in COOLDOWN
        assertEquals(VerdictKind.FRIEND, link.run().answer!!.verdict)
        assertEquals("COOLDOWN", link.reader.value?.state)
        port.queries += lines("no_response.txt")
        Thread {
            Thread.sleep(80)
            port.feed(listOf("""{"event":"STATE","boot_id":"@BOOT@","request_id":"x","state":"IDLE","reason":"CHANGED"}"""))
        }.start()
        val out = link.run(box = 4)
        assertTrue(out.statusTexts.contains("reader cooldown · waiting for idle"))
        assertEquals("no response", out.answer!!.reason)
        assertEquals(listOf("QUERY t3st0001-1-3", "QUERY t3st0001-2-4"), port.queryLines)
    }

    @Test
    fun `a RESULT for an earlier query of ours is reported stale, never delivered`() {
        val (link, port) = attached()
        assertEquals("gadget no ack", link.run(box = 5).error)
        port.feed(lines("no_response.txt"), id = "t3st0001-1-5")
        val (answer, why) = stale.single()
        assertEquals("late result for box 5 ignored", why)
        assertEquals(VerdictKind.UNKNOWN, answer.verdict)
    }

    @Test
    fun `unplugging mid-query ends it with the transport's reason`() {
        val (link, port) = attached(fast.copy(ackMs = 5_000))
        val error = runBlocking {
            val job = async {
                try {
                    link.challenge(ChallengeRequest(3, 42, 0L)).collect { }
                    null
                } catch (e: GadgetUnavailableException) {
                    e.reason
                }
            }
            delay(50)
            link.detach("gadget unplugged")
            job.await()
        }
        assertEquals("gadget unplugged", error)
        assertFalse(link.isAttached)
        assertEquals(1, port.queryLines.size)
        val after = try {
            runBlocking { link.challenge(ChallengeRequest(3, 42, 0L)) }
            null
        } catch (e: GadgetUnavailableException) {
            e.reason
        }
        assertEquals("no gadget", after)
    }

    @Test
    fun `request ids are unique per launch and always valid for the reader`() {
        assertNotEquals(ReaderLink.newPrefix(), ReaderLink.newPrefix())
        assertTrue(GadgetLineCodec.isValidRequestId(ReaderLink.newPrefix()))
        val (link, port) = attached(fast.copy(ackMs = 20))
        link.run(box = Int.MAX_VALUE)
        link.run(box = 0)
        val ids = port.queryLines.map { it.removePrefix("QUERY ") }
        assertEquals(listOf("t3st0001-1-2147483647", "t3st0001-2-0"), ids)
        assertTrue(ids.all { GadgetLineCodec.isValidRequestId(it) })
    }

    @Test
    fun `malformed reader lines are skipped and the link carries on`() {
        val (link, port) = attached()
        port.feed(listOf("""{"event":"RESULT","request_id":"x","response_ms":"fast"}""", """{"event":"ACK","""))
        assertEquals(2, log.count { it.startsWith("gadget line skipped") })
        port.queries += lines("friend.txt")
        assertEquals(VerdictKind.FRIEND, link.run().answer!!.verdict)
    }
}
