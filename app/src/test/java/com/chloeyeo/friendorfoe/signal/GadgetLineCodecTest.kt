package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GadgetLineCodecTest {
    private val nonceHex = "00112233445566778899aabbccddeeff"

    @Test
    fun `challenge line has exactly the README fields`() {
        val line = GadgetLineCodec.encodeChallenge(ChallengeRequest(boxId = 3, challengerId = 7, t = 1760000000L))
        val obj = JSONObject(line)
        assertEquals("challenge", obj.getString("cmd"))
        assertEquals(3, obj.getInt("boxId"))
        assertEquals(7, obj.getInt("challengerId"))
        assertEquals(1760000000L, obj.getLong("t"))
        assertEquals(4, obj.length())
        assertTrue("one line, no newline inside", !line.contains('\n'))
    }

    @Test
    fun `status request line`() {
        assertEquals("{\"cmd\":\"status\"}", GadgetLineCodec.encodeStatusRequest())
    }

    @Test
    fun `parses a FRIEND answer with null reason and an extra airtime field`() {
        val line = """{"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"$nonceHex","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}"""
        val answer = GadgetLineCodec.parseLine(line) as SignalEvent.Answer
        assertEquals(VerdictKind.FRIEND, answer.verdict)
        assertNull(answer.reason)
        assertEquals("RQ-07", answer.droneId)
        assertEquals(nonceHex, answer.nonceHex)
        assertEquals(-58, answer.rssiDbm)
        assertEquals(42L, answer.responseMs)
        assertEquals(SignalKind.USB_GADGET, answer.source)
    }

    @Test
    fun `parses a FOE replayed nonce answer`() {
        val line = """{"type":"answer","verdict":"FOE","reason":"replayed nonce","droneId":"RQ-07","nonce":"$nonceHex","rssiDbm":-61,"responseMs":40}"""
        val answer = GadgetLineCodec.parseLine(line) as SignalEvent.Answer
        assertEquals(VerdictKind.FOE, answer.verdict)
        assertEquals("replayed nonce", answer.reason)
        assertEquals(-61, answer.rssiDbm)
        assertEquals(40L, answer.responseMs)
    }

    @Test
    fun `parses an UNKNOWN no response answer without droneId or rssi`() {
        val line = """{"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"$nonceHex","responseMs":10000}"""
        val answer = GadgetLineCodec.parseLine(line) as SignalEvent.Answer
        assertEquals(VerdictKind.UNKNOWN, answer.verdict)
        assertEquals("no response", answer.reason)
        assertNull(answer.droneId)
        assertNull(answer.rssiDbm)
        assertEquals(10000L, answer.responseMs)
    }

    @Test
    fun `parses a status line into readable text`() {
        val status = GadgetLineCodec.parseLine("""{"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}""")
        assertEquals(SignalEvent.Status("fw 0.1 · battery 3.9 V · mode HONEST"), status)
    }

    @Test
    fun `blank lines and unknown types are skipped`() {
        assertNull(GadgetLineCodec.parseLine(""))
        assertNull(GadgetLineCodec.parseLine("   \r"))
        assertNull(GadgetLineCodec.parseLine("""{"type":"debug","text":"hello"}"""))
        assertNull(GadgetLineCodec.parseLine("""{"cmd":"challenge"}"""))
    }

    @Test
    fun `malformed lines fail loudly`() {
        assertThrows(IllegalArgumentException::class.java) { GadgetLineCodec.parseLine("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            GadgetLineCodec.parseLine("""{"type":"answer","verdict":"MAYBE","nonce":"$nonceHex","responseMs":1}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GadgetLineCodec.parseLine("""{"type":"answer","verdict":"FRIEND","nonce":"$nonceHex"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GadgetLineCodec.parseLine("""{"type":"answer","verdict":"FRIEND","nonce":"xyz","responseMs":1}""")
        }
    }

    @Test
    fun `nonce hex round trips`() {
        val bytes = GadgetLineCodec.hexToNonce(nonceHex)
        assertEquals(16, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0xFF, bytes[15].toInt() and 0xFF)
        assertEquals(nonceHex, GadgetLineCodec.nonceToHex(bytes))
        assertArrayEquals(bytes, GadgetLineCodec.hexToNonce(nonceHex.uppercase()))
    }

    /** Every JSON line in docs/GADGET_LINK.md: commands equal what the phone encodes, gadget lines parse. */
    @Test
    fun `every example line in GADGET_LINK md round trips`() {
        val doc = listOf(File("../docs/GADGET_LINK.md"), File("docs/GADGET_LINK.md")).firstOrNull { it.isFile }
        assumeTrue("docs/GADGET_LINK.md not found from ${File(".").absolutePath}", doc != null)
        val lines = doc!!.readLines().map { it.trim() }.filter { it.startsWith("{\"") && it.endsWith("}") }
            .map { it.replace("<32 hex>", nonceHex) }
        assertTrue("expected the 6 example lines, got ${lines.size}", lines.size >= 6)
        var commands = 0
        var events = 0
        for (line in lines) {
            val obj = JSONObject(line)
            when (obj.optString("cmd")) {
                "challenge" -> {
                    val req = ChallengeRequest(obj.getInt("boxId"), obj.getInt("challengerId"), obj.getLong("t"))
                    assertEquals(obj.asMap(), JSONObject(GadgetLineCodec.encodeChallenge(req)).asMap())
                    commands++
                }
                "status" -> {
                    assertEquals(obj.asMap(), JSONObject(GadgetLineCodec.encodeStatusRequest()).asMap())
                    commands++
                }
                "mode" -> Unit // drone-side bench command, not the phone's
                else -> {
                    val event = GadgetLineCodec.parseLine(line)
                    assertTrue(line, event != null)
                    if (event is SignalEvent.Answer) {
                        assertEquals(obj.getString("verdict"), event.verdict.name)
                        assertEquals(nonceHex, event.nonceHex)
                        assertEquals(obj.getLong("responseMs"), event.responseMs)
                        assertEquals(if (obj.isNull("rssiDbm")) null else obj.getInt("rssiDbm"), event.rssiDbm)
                    }
                    events++
                }
            }
        }
        assertEquals(2, commands)
        assertEquals(4, events)
    }

    /** android.jar's org.json has no toMap(); compare keys and value texts instead. */
    private fun JSONObject.asMap(): Map<String, String> = keys().asSequence().associateWith { get(it).toString() }

    @Test
    fun `an encoded challenge parses back to the same request`() {
        val req = ChallengeRequest(boxId = 12, challengerId = 65_535, t = 1_760_000_123L)
        val obj = JSONObject(GadgetLineCodec.encodeChallenge(req))
        assertEquals(req, ChallengeRequest(obj.getInt("boxId"), obj.getInt("challengerId"), obj.getLong("t")))
    }
}
