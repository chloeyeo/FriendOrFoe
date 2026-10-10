package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import com.chloeyeo.friendorfoe.signal.GadgetLineCodec.ReaderEvent

/**
 * Ihan's reader says only FRIEND or UNKNOWN, with a reason from `NONE | NO_RESPONSE | INVALID_MESSAGE | AUTH_FAILED
 * | RADIO_ERROR` (FofProtocol.h). This is where the phone turns that into its ring (docs/GADGET_LINK.md, "Verdict
 * mapping"). Fails closed: FRIEND only for `verdict FRIEND · reason NONE · response_received true · replay false`.
 *
 * | reader | ring |
 * |---|---|
 * | `FRIEND` / `NONE` | FRIEND (no reason) |
 * | `UNKNOWN` / `NO_RESPONSE` | UNKNOWN · no response |
 * | `UNKNOWN` / `AUTH_FAILED` (current nonce, wrong HMAC tag) | FOE · bad tag |
 * | `UNKNOWN` / `INVALID_MESSAGE` (wrong length, version, type or ids) | UNKNOWN · invalid message |
 * | `UNKNOWN` / `RADIO_ERROR` | UNKNOWN · gadget radio error |
 * | any RESULT with `replay:true` (the reader re-sending a stored verdict: ACK DUPLICATE + old RESULT, STATUS `last_result`) | UNKNOWN · stale result |
 * | FRIEND with any other reason, or an unknown reason | UNKNOWN · invalid result / the reason in lower case |
 */
object ReaderVerdicts {
    const val NO_RESPONSE = "no response"
    const val BAD_TAG = "bad tag"
    const val INVALID_MESSAGE = "invalid message"
    const val RADIO_ERROR = "gadget radio error"
    const val STALE_RESULT = "stale result"
    const val INVALID_RESULT = "invalid result"

    fun map(r: ReaderEvent.Result): Pair<VerdictKind, String?> = when {
        // A stored verdict re-sent, not an answer to a fresh nonce: says nothing about the drone in view now,
        // so never FRIEND, and not FOE either (the drone did nothing wrong; the request id was reused).
        r.replay -> VerdictKind.UNKNOWN to STALE_RESULT
        r.verdict == "FRIEND" && r.reason == "NONE" && r.responseReceived -> VerdictKind.FRIEND to null
        r.verdict == "FRIEND" -> VerdictKind.UNKNOWN to INVALID_RESULT
        else -> when (r.reason) {
            "NO_RESPONSE" -> VerdictKind.UNKNOWN to NO_RESPONSE
            "AUTH_FAILED" -> VerdictKind.FOE to BAD_TAG
            "INVALID_MESSAGE" -> VerdictKind.UNKNOWN to INVALID_MESSAGE
            "RADIO_ERROR" -> VerdictKind.UNKNOWN to RADIO_ERROR
            "NONE" -> VerdictKind.UNKNOWN to INVALID_RESULT
            else -> VerdictKind.UNKNOWN to readable(r.reason)
        }
    }

    /**
     * An `ACK accepted:false` (the query never ran): the reason for `UNKNOWN · <reason>`.
     * BUSY (the 3 s cooldown from the previous query's start), KEY_NOT_CONFIGURED, RADIO_ERROR, and the
     * malformed-command ones the phone should never cause.
     */
    fun ackReason(reason: String): String = when (reason) {
        "BUSY" -> "gadget busy"
        "KEY_NOT_CONFIGURED" -> "gadget key not set"
        "RADIO_ERROR" -> RADIO_ERROR
        "INVALID_REQUEST_ID" -> "gadget refused request id"
        "INVALID_COMMAND", "COMMAND_TOO_LONG" -> "gadget refused command"
        else -> "gadget refused (${readable(reason)})"
    }

    /** The ring's display id for a drone the reader authenticated (`drone_id` from its packet log). */
    fun droneLabel(droneId: Int?): String? = droneId?.let { "drone $it" }

    /**
     * The [SignalEvent.Answer] for [r]: [nonce], [droneId] and [rssiDbm] come from the query's packet lines when
     * they arrived; `responseMs` is the reader's `response_ms`, or its `elapsed_ms` when nothing answered.
     */
    fun answer(r: ReaderEvent.Result, nonce: ByteArray?, droneId: Int?, rssiDbm: Int?, linkRoundTripMs: Long?): SignalEvent.Answer {
        val (verdict, reason) = map(r)
        return SignalEvent.Answer(
            verdict = verdict,
            reason = reason,
            droneId = if (verdict == VerdictKind.FRIEND) droneLabel(droneId) else null,
            nonce = nonce,
            rssiDbm = r.rssiDbm ?: rssiDbm,
            responseMs = r.responseMs ?: r.elapsedMs,
            source = SignalKind.USB_GADGET,
            linkRoundTripMs = linkRoundTripMs,
        )
    }

    private fun readable(code: String): String = code.lowercase().replace('_', ' ').take(40)
}
