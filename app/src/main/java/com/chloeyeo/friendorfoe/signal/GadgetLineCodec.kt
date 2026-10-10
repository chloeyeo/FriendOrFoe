package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import org.json.JSONException
import org.json.JSONObject

/**
 * The USB gadget link's line format (README "Gadget link over USB", docs/GADGET_LINK.md): one JSON object per
 * line, 115200 8N1. This codec is pure (org.json only) and unit-tested; the transport lives in
 * [UsbGadgetSignalSource].
 *
 * ```
 * phone -> gadget   {"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000,"seq":12}
 *                   {"cmd":"status"}
 * gadget -> phone   {"type":"answer","seq":12,"boxId":3,"verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}
 *                   {"type":"answer","verdict":"FOE","reason":"replayed nonce","droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-61,"responseMs":40}
 *                   {"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"<32 hex>","responseMs":10000}
 *                   {"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}
 * ```
 *
 * `seq` (the phone's per-challenge counter) and `boxId` are echoed by the gadget in its answer when the firmware
 * supports it; both are optional on the way back, and the transport discards an answer whose echo does not match
 * the open challenge (SIG-03 attribution).
 */
object GadgetLineCodec {
    const val CMD_CHALLENGE = "challenge"
    const val CMD_STATUS = "status"
    const val TYPE_ANSWER = "answer"
    const val TYPE_STATUS = "status"

    /** `{"cmd":"challenge","boxId":..,"challengerId":..,"t":..[,"seq":..]}` without the trailing newline. */
    fun encodeChallenge(req: ChallengeRequest, seq: Long? = null): String = JSONObject()
        .put("cmd", CMD_CHALLENGE)
        .put("boxId", req.boxId)
        .put("challengerId", req.challengerId)
        .put("t", req.t)
        .apply { if (seq != null) put("seq", seq) }
        .toString()

    /** One parsed gadget line: the event plus the answer's optional `seq` / `boxId` echo (null when absent). */
    data class Line(val event: SignalEvent, val seq: Long? = null, val boxId: Int? = null)

    /** `{"cmd":"status"}` without the trailing newline. */
    fun encodeStatusRequest(): String = JSONObject().put("cmd", CMD_STATUS).toString()

    /**
     * Parses one gadget -> phone line. Returns null for a blank line or an object whose `type` is not one of ours
     * (the transport logs and skips it); throws [IllegalArgumentException] for malformed JSON, an unknown verdict,
     * a missing, non-numeric or negative `responseMs`, a non-numeric `rssiDbm` or a bad nonce, so a firmware/phone
     * disagreement fails loudly. Never throws anything but [IllegalArgumentException].
     */
    fun parseLine(line: String): SignalEvent? = parse(line)?.event

    /** [parseLine] plus the answer's `seq` / `boxId` echo. */
    fun parse(line: String): Line? {
        val text = line.trim()
        if (text.isEmpty()) return null
        return try {
            val obj = JSONObject(text)
            when (obj.optString("type", "")) {
                TYPE_ANSWER -> Line(parseAnswer(obj, text), longOrNull(obj, "seq", text), longOrNull(obj, "boxId", text)?.toInt())
                TYPE_STATUS -> Line(SignalEvent.Status(statusText(obj)))
                else -> null
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException("gadget line is not a valid JSON object: $text", e)
        }
    }

    private fun parseAnswer(obj: JSONObject, text: String): SignalEvent.Answer {
        val verdictText = stringOrNull(obj, "verdict") ?: throw IllegalArgumentException("answer without verdict: $text")
        val verdict = VerdictKind.entries.firstOrNull { it.name == verdictText.uppercase() }
            ?: throw IllegalArgumentException("unknown verdict '$verdictText': $text")
        val responseMs = longOrNull(obj, "responseMs", text) ?: throw IllegalArgumentException("answer without responseMs: $text")
        if (responseMs < 0) throw IllegalArgumentException("answer with a negative responseMs: $text")
        val nonceHex = stringOrNull(obj, "nonce") ?: throw IllegalArgumentException("answer without nonce: $text")
        val nonce = try {
            Hex.decode(nonceHex)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("answer with a bad nonce '$nonceHex': ${e.message}", e)
        }
        return SignalEvent.Answer(
            verdict = verdict,
            reason = stringOrNull(obj, "reason"),
            droneId = stringOrNull(obj, "droneId"),
            nonce = nonce,
            rssiDbm = longOrNull(obj, "rssiDbm", text)?.let {
                if (it !in -255L..255L) throw IllegalArgumentException("answer with an rssiDbm out of range: $text")
                it.toInt()
            },
            responseMs = responseMs,
            source = SignalKind.USB_GADGET,
        )
    }

    /** A JSON number as Long; null when absent or JSON null; [IllegalArgumentException] for a string, boolean, object. */
    private fun longOrNull(obj: JSONObject, key: String, text: String): Long? {
        if (obj.isNull(key)) return null
        val n = obj.opt(key) as? Number ?: throw IllegalArgumentException("answer field '$key' is not a number: $text")
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite() || d > Long.MAX_VALUE || d < Long.MIN_VALUE) {
            throw IllegalArgumentException("answer field '$key' is out of range: $text")
        }
        return n.toLong()
    }

    /** `fw 0.1 · battery 3.9 V · mode HONEST` from whichever of the fields are present; `status` when none is. */
    private fun statusText(obj: JSONObject): String {
        val parts = ArrayList<String>(3)
        stringOrNull(obj, "fw")?.let { parts += "fw $it" }
        if (!obj.isNull("battery")) parts += "battery ${obj.get("battery")} V"
        stringOrNull(obj, "mode")?.let { parts += "mode $it" }
        return if (parts.isEmpty()) "status" else parts.joinToString(" · ")
    }

    /** `isNull` covers both an absent key and a JSON null on Android's and on Maven's org.json alike. */
    private fun stringOrNull(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.get(key).toString()

    fun nonceToHex(nonce: ByteArray): String = Hex.encode(nonce)

    fun hexToNonce(hex: String): ByteArray = Hex.decode(hex)
}
