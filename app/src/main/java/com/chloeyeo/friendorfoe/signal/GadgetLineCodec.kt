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
 * phone -> gadget   {"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000}
 *                   {"cmd":"status"}
 * gadget -> phone   {"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}
 *                   {"type":"answer","verdict":"FOE","reason":"replayed nonce","droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-61,"responseMs":40}
 *                   {"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"<32 hex>","responseMs":10000}
 *                   {"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}
 * ```
 */
object GadgetLineCodec {
    const val CMD_CHALLENGE = "challenge"
    const val CMD_STATUS = "status"
    const val TYPE_ANSWER = "answer"
    const val TYPE_STATUS = "status"

    /** `{"cmd":"challenge","boxId":..,"challengerId":..,"t":..}` without the trailing newline. */
    fun encodeChallenge(req: ChallengeRequest): String = JSONObject()
        .put("cmd", CMD_CHALLENGE)
        .put("boxId", req.boxId)
        .put("challengerId", req.challengerId)
        .put("t", req.t)
        .toString()

    /** `{"cmd":"status"}` without the trailing newline. */
    fun encodeStatusRequest(): String = JSONObject().put("cmd", CMD_STATUS).toString()

    /**
     * Parses one gadget -> phone line. Returns null for a blank line or an object whose `type` is not one of ours
     * (the transport logs and skips it); throws [IllegalArgumentException] for malformed JSON, an unknown verdict,
     * a missing `responseMs` or a bad nonce, so a firmware/phone disagreement fails loudly.
     */
    fun parseLine(line: String): SignalEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("gadget line is not a JSON object: $text", e)
        }
        return when (obj.optString("type", "")) {
            TYPE_ANSWER -> parseAnswer(obj, text)
            TYPE_STATUS -> SignalEvent.Status(statusText(obj))
            else -> null
        }
    }

    private fun parseAnswer(obj: JSONObject, text: String): SignalEvent.Answer {
        val verdictText = stringOrNull(obj, "verdict") ?: throw IllegalArgumentException("answer without verdict: $text")
        val verdict = VerdictKind.entries.firstOrNull { it.name == verdictText.uppercase() }
            ?: throw IllegalArgumentException("unknown verdict '$verdictText': $text")
        if (obj.isNull("responseMs")) throw IllegalArgumentException("answer without responseMs: $text")
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
            rssiDbm = if (obj.isNull("rssiDbm")) null else obj.getInt("rssiDbm"),
            responseMs = obj.getLong("responseMs"),
            source = SignalKind.USB_GADGET,
        )
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
