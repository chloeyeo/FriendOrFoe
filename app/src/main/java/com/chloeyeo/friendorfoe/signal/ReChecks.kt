package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind

/**
 * The phone's own checks on a gadget [SignalEvent.Answer] — the three physical-layer gates the gadget cannot be
 * trusted alone with (README "Re-checks on the phone"), pure Kotlin, unit-tested:
 *
 * 1. the nonce was never seen before by this phone (`replayed nonce`); skipped with a note when the gadget did not
 *    report the nonce (its log line was lost),
 * 2. the phone-measured round trip and the gadget's own `responseMs` are both within [Config.rttBudgetMs]
 *    (`late response`),
 * 3. the RSSI is at or above [Config.rssiFloorDbm] (`signal mismatch`); when the gadget reports no RSSI the check
 *    is skipped with a note unless [Config.strict] is set, in which case it fails.
 *
 * Verdicts are ordered FRIEND < UNKNOWN < FOE and the stricter one wins. When the phone escalates, its reason
 * replaces the gadget's; when the gadget already said FOE its reason is kept (its verifier ran first and is more
 * specific) and the phone's finding goes to [Result.notes]. An UNKNOWN answer (nothing came back) passes through
 * untouched apart from recording the nonce: there is no answer to time or to measure.
 */
class ReChecks(val config: Config = Config()) {

    data class Config(
        val rttBudgetMs: Long = 2500,
        val rssiFloorDbm: Int = -70,
        val strict: Boolean = false,
    )

    /** The verdict after the re-checks, its reason, and anything worth logging. */
    data class Result(val verdict: VerdictKind, val reason: String?, val notes: List<String> = emptyList()) {
        val isEscalated: Boolean get() = notes.any { it.startsWith("phone:") }
    }

    private val seenNonces = HashSet<String>()

    /** How many distinct nonces this phone has accepted into its ledger. */
    val seenCount: Int get() = seenNonces.size

    /**
     * @param answer the gadget's answer
     * @param roundTripMs the phone-measured time from writing the challenge line to receiving the answer line
     */
    fun check(answer: SignalEvent.Answer, roundTripMs: Long): Result {
        val notes = ArrayList<String>(3)
        var verdict = answer.verdict
        var reason = answer.reason

        fun fail(phoneReason: String) {
            if (verdict != VerdictKind.FOE) {
                verdict = VerdictKind.FOE
                reason = phoneReason
                notes += "phone: $phoneReason"
            } else {
                notes += "phone also: $phoneReason"
            }
        }

        val nonce = answer.nonce
        val fresh = nonce == null || seenNonces.add(Hex.encode(nonce))
        if (nonce == null) notes += "nonce not reported; replay check skipped"

        if (answer.verdict == VerdictKind.UNKNOWN) {
            notes += "no answer to re-check"
            return Result(verdict, reason, notes)
        }

        if (!fresh) fail("replayed nonce")

        if (roundTripMs > config.rttBudgetMs || answer.responseMs > config.rttBudgetMs) fail("late response")

        val rssi = answer.rssiDbm
        when {
            rssi == null && config.strict -> fail("signal mismatch")
            rssi == null -> notes += "rssi not reported; signal check skipped"
            rssi < config.rssiFloorDbm -> fail("signal mismatch")
        }

        return Result(verdict, reason, notes)
    }

    /** Forgets the nonce ledger (a new day key, a new session). */
    fun reset() {
        seenNonces.clear()
    }
}
