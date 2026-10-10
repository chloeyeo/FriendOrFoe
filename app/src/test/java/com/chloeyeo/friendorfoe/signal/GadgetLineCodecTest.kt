package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.signal.GadgetLineCodec.ReaderEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Ihan's reader lines (firmware/reader/reader.ino) through [GadgetLineCodec], mostly from the captured fixtures. */
class GadgetLineCodecTest {
    private fun fixture(name: String, id: String = "abc12345-1-3") =
        Fixtures.lines(name).map { it.replace("@ID@", id).replace("@BOOT@", "ae07effed99ac4d4").replace("@SID@", "s1") }

    @Test
    fun `commands are the reader's plain text lines`() {
        assertEquals("QUERY abc12345-12-3", GadgetLineCodec.encodeQuery("abc12345-12-3"))
        assertEquals("STATUS", GadgetLineCodec.encodeStatus())
        assertEquals("STATUS abc12345-s1", GadgetLineCodec.encodeStatus("abc12345-s1"))
    }

    @Test
    fun `request ids follow the reader's rule, 1-32 of letters, digits, dash, underscore`() {
        assertTrue(GadgetLineCodec.isValidRequestId("a"))
        assertTrue(GadgetLineCodec.isValidRequestId("A_b-9".padEnd(32, 'x')))
        assertFalse(GadgetLineCodec.isValidRequestId(""))
        assertFalse(GadgetLineCodec.isValidRequestId("a".repeat(33)))
        assertFalse(GadgetLineCodec.isValidRequestId("bad!"))
        assertFalse(GadgetLineCodec.isValidRequestId("has space"))
        assertThrows(IllegalArgumentException::class.java) { GadgetLineCodec.encodeQuery("bad!") }
        assertThrows(IllegalArgumentException::class.java) { GadgetLineCodec.encodeStatus("") }
        // The longest QUERY still fits the reader's 79-byte line buffer.
        assertTrue(GadgetLineCodec.encodeQuery("x".repeat(32)).length <= GadgetLineCodec.MAX_COMMAND_BYTES)
    }

    @Test
    fun `the captured FRIEND exchange parses into ACK, STATE, TX, RESULT and RX`() {
        val events = fixture("friend.txt").map { GadgetLineCodec.parse(it) }
        val ack = events[0] as ReaderEvent.Ack
        assertEquals(ReaderEvent.Ack("abc12345-1-3", true, "ACCEPTED", "QUERYING"), ack)
        assertEquals(ReaderEvent.State("ae07effed99ac4d4", "abc12345-1-3", "QUERYING", "CHANGED"), events[1])
        val tx = events[2] as ReaderEvent.Packet
        assertTrue(tx.tx)
        assertEquals(1, tx.droneId)
        assertEquals("44ad3f50c8539c3d2121b4cb9e7e1e25", Hex.encode(tx.nonce!!))
        assertEquals("BROADCAST_STARTED", tx.result)
        val result = events[3] as ReaderEvent.Result
        assertEquals(
            ReaderEvent.Result("abc12345-1-3", 1, true, "FRIEND", "NONE", 460, 460, false),
            result,
        )
        val rx = events[5] as ReaderEvent.Packet
        assertFalse(rx.tx)
        assertEquals("FRIEND", rx.result)
        assertFalse(rx.ignored)
        assertTrue((events[6] as ReaderEvent.Packet).ignored)
        assertEquals("IDLE", (events.last() as ReaderEvent.State).state)
    }

    @Test
    fun `the captured NO_RESPONSE result has a null response_ms`() {
        val result = fixture("no_response.txt").map { GadgetLineCodec.parse(it) }.filterIsInstance<ReaderEvent.Result>().single()
        assertEquals("UNKNOWN", result.verdict)
        assertEquals("NO_RESPONSE", result.reason)
        assertFalse(result.responseReceived)
        assertNull(result.responseMs)
        assertEquals(3000L, result.elapsedMs)
    }

    @Test
    fun `STATUS is a compatible reader only for role reader, interface 2, manual_query and a boot id`() {
        val status = GadgetLineCodec.parse(fixture("status.txt").single()) as ReaderEvent.Status
        assertTrue(status.isCompatibleReader)
        assertEquals("s1", status.requestId)
        assertTrue(status.keyConfigured)
        assertTrue(status.radioReady)
        assertEquals("IDLE", status.state)
        assertEquals(3000L, status.responseTimeoutMs)
        assertNull(status.lastResult)
        assertFalse(status.copy(role = "drone").isCompatibleReader)
        assertFalse(status.copy(interfaceVersion = 1).isCompatibleReader)
        assertFalse(status.copy(manualQuery = false).isCompatibleReader)
        assertFalse(status.copy(bootId = null).isCompatibleReader)
    }

    @Test
    fun `STATUS last_result carries the completed RESULT, marked replay`() {
        val line = fixture("status_last_result.txt").single()
        assertTrue("about 500 bytes, under the splitter's limit", line.length in 400..LineSplitter.MAX_LINE_BYTES)
        val last = (GadgetLineCodec.parse(line) as ReaderEvent.Status).lastResult!!
        assertEquals("abc12345-1-3", last.requestId)
        assertEquals("FRIEND", last.verdict)
        assertTrue(last.replay)
    }

    @Test
    fun `boot text, blank lines and ROM text are tolerated, download mode is recognised`() {
        val boot = fixture("boot.txt").map { GadgetLineCodec.parse(it) }
        assertNull(boot[0]) // the bare wake LF
        assertEquals(ReaderEvent.Text("KEY: stored"), boot[1])
        assertTrue(boot[2] is ReaderEvent.Text)
        assertEquals("BOOT", (boot[3] as ReaderEvent.State).reason)
        assertTrue((boot[4] as ReaderEvent.Status).isCompatibleReader)
        assertNull(GadgetLineCodec.parse("   \r"))
        assertEquals(ReaderEvent.Text("CONFIG: invalid hex"), GadgetLineCodec.parse("CONFIG: invalid hex\r"))
        assertTrue((GadgetLineCodec.parse("waiting for download") as ReaderEvent.Text).downloadMode)
        assertFalse((GadgetLineCodec.parse("KEY: missing; enter KEY <64 hex digits>") as ReaderEvent.Text).downloadMode)
    }

    @Test
    fun `DIAG, HELP and events of other roles parse without failing`() {
        val diag = fixture("diag.txt").map { GadgetLineCodec.parse(it) }
        assertEquals(ReaderEvent.Diag(3, false, false), diag[0])
        assertEquals(ReaderEvent.Diag(null, true, false), diag[1])
        assertEquals(4, (diag[2] as ReaderEvent.Help).commands.size)
        assertEquals(ReaderEvent.Other("RX_CHALLENGE"), GadgetLineCodec.parse("""{"event":"RX_CHALLENGE","role":"drone"}"""))
        assertEquals(ReaderEvent.Other(""), GadgetLineCodec.parse("""{"type":"answer"}"""))
    }

    @Test
    fun `the raw packet dump and unknown keys are ignored`() {
        val raw = "ab".repeat(38)
        val line = """{"event":"RX_RESPONSE","request_id":"r1","query":1,"at_ms":5,"elapsed_ms":5,"bytes":38,"version":1,""" +
            """"type":2,"reader_id":1,"drone_id":1,"nonce":"${"cd".repeat(16)}","tag":"${"ef".repeat(16)}","raw":"$raw","result":"FRIEND","extra":{}}"""
        val p = GadgetLineCodec.parse(line) as ReaderEvent.Packet
        assertArrayEquals(ByteArray(16) { 0xCD.toByte() }, p.nonce)
        assertNull(p.rssiDbm)
    }

    @Test
    fun `a short packet has no nonce fields`() {
        val p = GadgetLineCodec.parse(
            """{"event":"RX_RESPONSE","request_id":"r1","query":1,"at_ms":5,"elapsed_ms":5,"bytes":12,"raw":"00","result":"INVALID_MESSAGE"}""",
        ) as ReaderEvent.Packet
        assertNull(p.nonce)
        assertNull(p.droneId)
    }

    @Test
    fun `an rssi field is read if a later firmware adds one`() {
        val r = GadgetLineCodec.parse(
            """{"event":"RESULT","request_id":"r1","query":1,"response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":40,"elapsed_ms":40,"replay":false,"rssi_dbm":-61}""",
        ) as ReaderEvent.Result
        assertEquals(-61, r.rssiDbm)
    }

    @Test
    fun `broken JSON and wrong-typed fields fail with IllegalArgumentException only`() {
        val bad = listOf(
            """{"event":"ACK",""",
            """{"event":"ACK","request_id":"r1","accepted":"yes","reason":"ACCEPTED"}""",
            """{"event":"ACK","accepted":true,"reason":"ACCEPTED"}""",
            """{"event":"RESULT","request_id":"r1","response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":"fast"}""",
            """{"event":"RESULT","request_id":"r1","response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":-5}""",
            """{"event":"RESULT","request_id":"r1","verdict":"FRIEND","reason":"NONE"}""",
            """{"event":"TX_CHALLENGE","request_id":"r1","nonce":"xyz","result":"BROADCAST_STARTED"}""",
            """{"event":"TX_CHALLENGE","request_id":"r1","nonce":"0011","result":"BROADCAST_STARTED"}""",
            """{"event":"STATE","boot_id":7,"request_id":"","state":"IDLE","reason":"BOOT"}""",
            """{"event":"RESULT","request_id":"r1","response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":1,"rssi":900}""",
        )
        for (line in bad) assertThrows(line, IllegalArgumentException::class.java) { GadgetLineCodec.parse(line) }
    }

    /** Every JSON line in docs/GADGET_LINK.md parses as the event it names. */
    @Test
    fun `every reader line in GADGET_LINK md parses`() {
        val doc = listOf(File("../docs/GADGET_LINK.md"), File("docs/GADGET_LINK.md")).firstOrNull { it.isFile }
        assumeTrue("docs/GADGET_LINK.md not found from ${File(".").absolutePath}", doc != null)
        val lines = doc!!.readLines().map { it.trim() }.filter { it.startsWith("{\"event\"") && it.endsWith("}") }
        assertTrue("expected the documented reader lines, got ${lines.size}", lines.size >= 8)
        val kinds = HashSet<String>()
        for (line in lines) {
            val event = GadgetLineCodec.parse(line)
            assertTrue(line, event != null && event !is ReaderEvent.Other)
            kinds += event!!.javaClass.simpleName
        }
        assertTrue(kinds.toString(), kinds.containsAll(listOf("Ack", "State", "Packet", "Result", "Status")))
        val commands = doc.readLines().map { it.trim() }.filter { it.startsWith("QUERY ") || it.startsWith("STATUS ") }
        assertTrue(commands.isNotEmpty())
        for (c in commands) {
            val (verb, id) = c.split(" ", limit = 2)
            assertEquals(c, if (verb == "QUERY") GadgetLineCodec.encodeQuery(id) else GadgetLineCodec.encodeStatus(id))
        }
    }
}
