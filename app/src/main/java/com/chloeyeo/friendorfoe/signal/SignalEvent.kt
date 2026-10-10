package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind

/** What a [SignalSource] emits while a challenge runs: progress lines and, at most once, the answer. */
sealed interface SignalEvent {
    /**
     * What the gadget reports after the radio exchange and its own crypto; the phone re-checks it ([ReChecks]).
     * @property verdict FRIEND, FOE or UNKNOWN as decided by the gadget's verifier
     * @property reason `replayed nonce`, `bad tag`, `position mismatch`, `late response`, `no response`, ... or null for FRIEND
     * @property droneId the responder's display id (`RQ-07`), null when nothing answered
     * @property nonce the 16-byte nonce the gadget used; the phone rejects any nonce it has seen before
     * @property rssiDbm received signal strength of the answer, null when the radio does not report it
     * @property responseMs challenge-to-answer time measured by the gadget (10000 on timeout)
     * @property source which source produced it
     */
    class Answer(
        val verdict: VerdictKind,
        val reason: String?,
        val droneId: String?,
        val nonce: ByteArray,
        val rssiDbm: Int?,
        val responseMs: Long,
        val source: SignalKind,
    ) : SignalEvent {
        val nonceHex: String get() = Hex.encode(nonce)

        override fun equals(other: Any?): Boolean = other is Answer &&
            verdict == other.verdict && reason == other.reason && droneId == other.droneId &&
            nonce.contentEquals(other.nonce) && rssiDbm == other.rssiDbm && responseMs == other.responseMs &&
            source == other.source

        override fun hashCode(): Int {
            var h = verdict.hashCode()
            h = 31 * h + (reason?.hashCode() ?: 0)
            h = 31 * h + (droneId?.hashCode() ?: 0)
            h = 31 * h + nonce.contentHashCode()
            h = 31 * h + (rssiDbm ?: 0)
            h = 31 * h + responseMs.hashCode()
            h = 31 * h + source.hashCode()
            return h
        }

        override fun toString(): String =
            "Answer($verdict, reason=$reason, droneId=$droneId, nonce=$nonceHex, rssiDbm=$rssiDbm, responseMs=$responseMs, source=$source)"

        fun copy(
            verdict: VerdictKind = this.verdict,
            reason: String? = this.reason,
            droneId: String? = this.droneId,
            nonce: ByteArray = this.nonce,
            rssiDbm: Int? = this.rssiDbm,
            responseMs: Long = this.responseMs,
            source: SignalKind = this.source,
        ): Answer = Answer(verdict, reason, droneId, nonce, rssiDbm, responseMs, source)
    }

    /** A progress line for the status strip / event log: "gadget connected", "challenge sent", ... */
    data class Status(val text: String) : SignalEvent
}
