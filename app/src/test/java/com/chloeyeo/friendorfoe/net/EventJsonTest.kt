package com.chloeyeo.friendorfoe.net

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.session.VerdictKind
import com.chloeyeo.friendorfoe.signal.SignalKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonTest {
    private val box = BoxF(576f, 238f, 806.25f, 432f)

    @Test
    fun verdictEventUsesTheDashboardSchema() {
        val e = EventUi(
            t = 1_760_000_000_000, text = "FOE · replayed nonce · RQ-07", trackId = 2, cls = ObjClass.DRONE, score = 0.42f,
            box = box, verdict = VerdictKind.FOE, reason = "replayed nonce", droneId = "RQ-07", rssiDbm = -61, roundTripMs = 40,
        )
        assertEquals(
            """{"t":1760000000000,"class":"drone","box":[576.0,238.0,806.3,432.0],"iffStatus":"FOE",""" +
                """"droneId":"RQ-07","reason":"replayed nonce","text":"FOE · replayed nonce · RQ-07","trackId":2,""" +
                """"score":0.42,"rssiDbm":-61,"roundTripMs":40,"source":null}""",
            EventJson.event(e),
        )
    }

    @Test
    fun fakeVerdictEventCarriesItsSource() {
        val e = EventUi(t = 1, text = "FRIEND · RQ-07 (fake)", verdict = VerdictKind.FRIEND, source = SignalKind.FAKE)
        assertEquals("FAKE", JSONObject(EventJson.event(e)).getString("source"))
    }

    @Test
    fun statusLineHasNullStructuredFields() {
        val json = JSONArray(EventJson.events(listOf(EventUi(5, "challenge sent · box 2 · nonce 3f9a…"))))
        val o = json.getJSONObject(0)
        assertEquals(5L, o.getLong("t"))
        assertTrue(o.isNull("class") && o.isNull("box") && o.isNull("iffStatus"))
        assertEquals("challenge sent · box 2 · nonce 3f9a…", o.getString("text"))
    }

    @Test
    fun frameCarriesThePhonesTagText() {
        val friend = TrackUi(2, ObjClass.DRONE, box, IffState.Verdict(VerdictKind.FRIEND, null, "RQ-07", -58, 42))
        val o = JSONObject(EventJson.frame(9, listOf(friend), width = 1920, height = 1080))
        assertEquals(1920, o.getInt("width"))
        val track = o.getJSONArray("tracks").getJSONObject(0)
        assertEquals("FRIEND · RQ-07 · −58 dBm · 42 ms", track.getString("tag"))
        assertEquals("green", track.getString("tone"))
        assertEquals(806.3, track.getJSONArray("box").getDouble(2), 1e-9)
    }

    @Test
    fun quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", EventJson.quote("a\"b\\c\nd\u0001"))
    }
}
