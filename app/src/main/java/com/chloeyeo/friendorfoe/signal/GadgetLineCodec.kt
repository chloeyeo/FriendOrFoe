package com.chloeyeo.friendorfoe.signal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Ihan's reader protocol (firmware/reader/reader.ino, firmware/README.md; docs/GADGET_LINK.md): plain-text command
 * lines in, one JSON object per line out, keyed by `"event"`. Pure (org.json only) and unit-tested; the protocol
 * engine is [ReaderLink], the USB transport [UsbGadgetSignalSource].
 *
 * ```
 * phone -> reader   QUERY <request_id>          STATUS [request_id]          HELP
 * reader -> phone   {"event":"ACK","request_id":"..","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}
 *                   {"event":"STATE","boot_id":"..","request_id":"..","state":"IDLE","reason":"CHANGED"}
 *                   {"event":"TX_CHALLENGE","request_id":"..","query":1,..,"drone_id":1,"nonce":"<32 hex>",..}
 *                   {"event":"RESULT","request_id":"..","query":1,"response_received":true,"verdict":"FRIEND",
 *                    "reason":"NONE","response_ms":460,"elapsed_ms":460,"replay":false}
 *                   {"event":"STATUS","request_id":"..","role":"reader","interface_version":2,..}
 *                   KEY: stored            (legacy text from the key store and the radio, CRLF)
 * ```
 *
 * The phone never sends `KEY` (the shared key is provisioned by Ihan over his own serial tool).
 */
object GadgetLineCodec {
    /** The reader's line buffer is `char[80]`: 79 bytes before the LF, longer lines get `COMMAND_TOO_LONG`. */
    const val MAX_COMMAND_BYTES = 79

    /** reader.ino `validId`: 1-32 of `[A-Za-z0-9_-]`. */
    private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,32}")

    const val INTERFACE_VERSION = 2L

    fun isValidRequestId(id: String): Boolean = REQUEST_ID.matches(id)

    /** `QUERY <id>` without the trailing LF. */
    fun encodeQuery(requestId: String): String {
        require(isValidRequestId(requestId)) { "request id must be 1-32 of [A-Za-z0-9_-]: '$requestId'" }
        return "QUERY $requestId"
    }

    /** `STATUS <id>` (or bare `STATUS`) without the trailing LF. */
    fun encodeStatus(requestId: String? = null): String {
        if (requestId == null) return "STATUS"
        require(isValidRequestId(requestId)) { "request id must be 1-32 of [A-Za-z0-9_-]: '$requestId'" }
        return "STATUS $requestId"
    }

    /** One reader -> phone line. */
    sealed interface ReaderEvent {
        /** First reply to every QUERY (and the reply to a malformed or refused command; `requestId` is then ""). */
        data class Ack(val requestId: String, val accepted: Boolean, val reason: String, val state: String?) : ReaderEvent

        /** A mode change (`IDLE`, `QUERYING`, `COOLDOWN`); `reason` is `BOOT` once per boot, else `CHANGED`. */
        data class State(val bootId: String, val requestId: String, val state: String, val reason: String) : ReaderEvent

        /** The final verdict of one query (one per nonce); `replay` when re-sent for a duplicate request id. */
        data class Result(
            val requestId: String,
            val query: Long,
            val responseReceived: Boolean,
            val verdict: String,
            val reason: String,
            val responseMs: Long?,
            val elapsedMs: Long,
            val replay: Boolean,
            /** Not in Ihan's firmware today; read if a later build adds `rssi_dbm` / `rssi`. */
            val rssiDbm: Int? = null,
        ) : ReaderEvent

        /** The reader's snapshot; [lastResult] recovers a lost RESULT (the firmware marks it `replay:true`). */
        data class Status(
            val requestId: String,
            val role: String?,
            val interfaceVersion: Long?,
            val bootId: String?,
            val manualQuery: Boolean,
            val keyConfigured: Boolean,
            val radioReady: Boolean,
            val state: String?,
            val activeRequestId: String,
            val responseTimeoutMs: Long?,
            val lastResult: Result?,
        ) : ReaderEvent {
            /** What Ihan's host checks before it lets anyone query (reader_backend.py). */
            val isCompatibleReader: Boolean
                get() = role == "reader" && interfaceVersion == INTERFACE_VERSION && manualQuery && bootId != null
        }

        /** `TX_CHALLENGE` / `RX_RESPONSE`: one radio packet, from the lossy diagnostic queue. */
        class Packet(
            val tx: Boolean,
            val requestId: String,
            val query: Long?,
            val elapsedMs: Long?,
            val droneId: Int?,
            val nonce: ByteArray?,
            val result: String,
            val rssiDbm: Int? = null,
        ) : ReaderEvent {
            /** An `RX_RESPONSE` the reader did not count (old nonce, after the deadline, after the verdict). */
            val ignored: Boolean get() = result.startsWith("IGNORED_")

            override fun toString(): String =
                "Packet(${if (tx) "TX" else "RX"}, $requestId, query=$query, elapsedMs=$elapsedMs, droneId=$droneId, " +
                    "nonce=${Redact.nonce(nonce)}, result=$result, rssiDbm=$rssiDbm)"
        }

        /** `DIAG`: dropped packet logs, or a radio error / receive-queue overflow. */
        data class Diag(val droppedPacketLogs: Long?, val radioError: Boolean, val queueOverflow: Boolean) : ReaderEvent

        data class Help(val commands: List<String>) : ReaderEvent

        /** A JSON line with an `event` this phone does not use (drone-side events, a later firmware's extras). */
        data class Other(val event: String) : ReaderEvent

        /** A non-JSON line: the boot banner, key-store replies, ESP ROM text. */
        data class Text(val text: String) : ReaderEvent {
            /** The ESP ROM's download-mode banner: the board needs RESET with BOOT released (Ihan: fatal). */
            val downloadMode: Boolean get() = text.contains("waiting for download", ignoreCase = true)
        }
    }

    /**
     * Parses one reader line. Null for a blank line (the C3 writes one bare LF to wake its USB flag). A line that is
     * not a JSON object is [ReaderEvent.Text]. Throws [IllegalArgumentException] (and nothing else) for a JSON line
     * that is broken, has anything after its closing brace, or is one of the events above with a missing or
     * wrong-typed field.
     */
    fun parse(line: String): ReaderEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return ReaderEvent.Text(text)
        val obj = try {
            val tokens = JSONTokener(text)
            val value = tokens.nextValue() as? JSONObject
            // JSONObject(String) stops at the closing brace; anything after it means two lines glued together.
            require(value != null && !tokens.more()) { "reader line is not exactly one JSON object" }
            value
        } catch (e: JSONException) {
            throw IllegalArgumentException("reader line is not a valid JSON object", e)
        }
        return try {
            parseObject(obj)
        } catch (e: JSONException) {
            throw IllegalArgumentException("reader line has a bad field: ${e.message}", e)
        }
    }

    private fun parseObject(obj: JSONObject): ReaderEvent {
        val event = obj.optString("event", "")
        return when (event) {
            "ACK" -> ReaderEvent.Ack(str(obj, "request_id"), bool(obj, "accepted"), str(obj, "reason"), optStr(obj, "state"))
            "STATE" -> ReaderEvent.State(str(obj, "boot_id"), str(obj, "request_id"), str(obj, "state"), str(obj, "reason"))
            "RESULT" -> result(obj)
            "STATUS" -> ReaderEvent.Status(
                requestId = str(obj, "request_id"),
                role = optStr(obj, "role"),
                interfaceVersion = optLong(obj, "interface_version"),
                bootId = optStr(obj, "boot_id"),
                manualQuery = obj.opt("manual_query") == true,
                keyConfigured = obj.opt("key_configured") == true,
                radioReady = obj.opt("radio_ready") == true,
                state = optStr(obj, "state"),
                activeRequestId = optStr(obj, "active_request_id") ?: "",
                responseTimeoutMs = optLong(obj, "response_timeout_ms"),
                lastResult = (obj.opt("last_result") as? JSONObject)?.let(::result),
            )
            "TX_CHALLENGE", "RX_RESPONSE" -> ReaderEvent.Packet(
                tx = event == "TX_CHALLENGE",
                requestId = str(obj, "request_id"),
                query = optLong(obj, "query"),
                elapsedMs = optLong(obj, "elapsed_ms"),
                droneId = optLong(obj, "drone_id")?.toInt(),
                nonce = optStr(obj, "nonce")?.let(::nonce),
                result = str(obj, "result"),
                rssiDbm = rssi(obj),
            )
            "DIAG" -> ReaderEvent.Diag(
                droppedPacketLogs = optLong(obj, "dropped_packet_logs"),
                radioError = obj.opt("radio_error") == true,
                queueOverflow = obj.opt("receive_queue_overflow") == true,
            )
            "HELP" -> ReaderEvent.Help(
                (obj.opt("commands") as? JSONArray)?.let { a -> List(a.length()) { a.get(it).toString() } } ?: emptyList(),
            )
            else -> ReaderEvent.Other(event)
        }
    }

    private fun result(obj: JSONObject) = ReaderEvent.Result(
        requestId = str(obj, "request_id"),
        query = optLong(obj, "query") ?: 0L,
        responseReceived = bool(obj, "response_received"),
        verdict = str(obj, "verdict"),
        reason = str(obj, "reason"),
        responseMs = optLong(obj, "response_ms")?.also { require(it >= 0) { "negative response_ms" } },
        elapsedMs = optLong(obj, "elapsed_ms") ?: 0L,
        replay = obj.opt("replay") == true,
        rssiDbm = rssi(obj),
    )

    private fun nonce(hex: String): ByteArray {
        val bytes = Hex.decode(hex)
        require(bytes.size == 16) { "nonce must be 16 bytes, got ${bytes.size}" }
        return bytes
    }

    private fun rssi(obj: JSONObject): Int? = (optLong(obj, "rssi_dbm") ?: optLong(obj, "rssi"))?.let {
        require(it in -255L..255L) { "rssi out of range: $it" }
        it.toInt()
    }

    private fun str(obj: JSONObject, key: String): String {
        val v = obj.opt(key)
        require(v is String) { "field '$key' is missing or not a string" }
        return v
    }

    private fun optStr(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val v = obj.opt(key)
        require(v is String) { "field '$key' is not a string" }
        return v
    }

    private fun bool(obj: JSONObject, key: String): Boolean {
        val v = obj.opt(key)
        require(v is Boolean) { "field '$key' is missing or not a boolean" }
        return v
    }

    /** A JSON number as Long; null when absent or JSON null; [IllegalArgumentException] for anything else. */
    private fun optLong(obj: JSONObject, key: String): Long? {
        if (obj.isNull(key)) return null
        val n = obj.opt(key) as? Number ?: throw IllegalArgumentException("field '$key' is not a number")
        val d = n.toDouble()
        require(!d.isNaN() && !d.isInfinite() && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) { "field '$key' is out of range" }
        return n.toLong()
    }
}
