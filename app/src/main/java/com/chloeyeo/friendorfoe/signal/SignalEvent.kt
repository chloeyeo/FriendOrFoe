package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind

/** What a [SignalSource] emits while a challenge runs: progress lines and, at most once, the answer. */
sealed interface SignalEvent {
    /**
     * What the gadget reports after the radio exchange and its own crypto; the phone re-checks it ([ReChecks]).
     * @property verdict FRIEND, FOE or UNKNOWN after mapping the gadget's result (docs/GADGET_LINK.md)
     * @property reason `bad tag`, `replayed nonce`, `no response`, ... or null for FRIEND
     * @property droneId the responder's display id (`drone 1`), null when nothing authenticated
     * @property nonce the 16-byte nonce the gadget used, null when the gadget's log line carrying it was lost; the
     *   phone rejects any nonce it has seen before and skips that check (with a note) when it is null
     * @property rssiDbm received signal strength of the answer, null when the radio does not report it
     * @property responseMs challenge-to-answer time measured by the gadget; the gadget's elapsed time when nothing
     *   answered (3000 for Ihan's reader)
     * @property source which source produced it
     * @property linkRoundTripMs the phone-measured time from writing the command to reading the result, when the
     *   source measured it itself (it may wait for the gadget to be idle before writing); the coordinator uses it in
     *   place of its own tap-to-answer time
     */
    class Answer(
        val verdict: VerdictKind,
        val reason: String?,
        val droneId: String?,
        val nonce: ByteArray?,
        val rssiDbm: Int?,
        val responseMs: Long,
        val source: SignalKind,
        val linkRoundTripMs: Long? = null,
    ) : SignalEvent {
        val nonceHex: String? get() = nonce?.let(Hex::encode)

        override fun equals(other: Any?): Boolean = other is Answer &&
            verdict == other.verdict && reason == other.reason && droneId == other.droneId &&
            (nonce?.contentEquals(other.nonce) ?: (other.nonce == null)) && rssiDbm == other.rssiDbm &&
            responseMs == other.responseMs && source == other.source && linkRoundTripMs == other.linkRoundTripMs

        override fun hashCode(): Int {
            var h = verdict.hashCode()
            h = 31 * h + (reason?.hashCode() ?: 0)
            h = 31 * h + (droneId?.hashCode() ?: 0)
            h = 31 * h + (nonce?.contentHashCode() ?: 0)
            h = 31 * h + (rssiDbm ?: 0)
            h = 31 * h + responseMs.hashCode()
            h = 31 * h + source.hashCode()
            h = 31 * h + (linkRoundTripMs?.hashCode() ?: 0)
            return h
        }

        override fun toString(): String =
            "Answer($verdict, reason=$reason, droneId=$droneId, nonce=$nonceHex, rssiDbm=$rssiDbm, responseMs=$responseMs, " +
                "source=$source, linkRoundTripMs=$linkRoundTripMs)"

        fun copy(
            verdict: VerdictKind = this.verdict,
            reason: String? = this.reason,
            droneId: String? = this.droneId,
            nonce: ByteArray? = this.nonce,
            rssiDbm: Int? = this.rssiDbm,
            responseMs: Long = this.responseMs,
            source: SignalKind = this.source,
            linkRoundTripMs: Long? = this.linkRoundTripMs,
        ): Answer = Answer(verdict, reason, droneId, nonce, rssiDbm, responseMs, source, linkRoundTripMs)
    }

    /** A progress line for the status strip / event log: "gadget connected", "challenge sent", ... */
    data class Status(val text: String) : SignalEvent
}
