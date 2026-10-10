package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * The drone-side gadget's attack modes, mirrored here so the phone can be demoed without hardware; [WEAK] is
 * phone-only (an honest answer from too far away, for the RSSI re-check).
 */
enum class FakeMode {
    HONEST, REPLAY, RELAY, LATE, SILENT, IMPOSTOR, WEAK;

    companion object {
        /** `honest`, `REPLAY`, ... (case-insensitive); null for anything else. */
        fun parse(text: String?): FakeMode? = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}

/**
 * Scripted answers for every verdict, through the real session path (the same events a gadget would produce).
 * One [SignalEvent.Status] ("challenge sent") then the matching [SignalEvent.Answer] after [delayMs]:
 *
 * - HONEST: FRIEND, -58 dBm, 42 ms, fresh nonce, after 180 ms
 * - REPLAY: FOE `replayed nonce`; re-uses the previous challenge's nonce (first call: a fresh one), so the
 *   phone's [ReChecks] ledger fires too
 * - RELAY: FOE `position mismatch`, a slower answer from farther away (400 ms)
 * - LATE: the gadget still says FRIEND but only after 3.5 s (responseMs 3450); [ReChecks] turns it into
 *   FOE `late response` with the 2.5 s budget
 * - SILENT: emits nothing at all; the coordinator's 10 s window ends in UNKNOWN `no response`
 * - IMPOSTOR: FOE `bad tag` (wrong key)
 * - WEAK: the gadget says FRIEND at -84 dBm; [ReChecks] turns it into FOE `signal mismatch` (floor -70 dBm)
 *
 * [mode] can be switched between challenges (Settings, `--es fake_mode replay`).
 *
 * @param delayMs how long each mode takes to answer; tests pass `{ 0L }`
 */
class FakeSignalSource(
    @Volatile var mode: FakeMode = FakeMode.HONEST,
    private val droneId: String = "RQ-07",
    private val random: Random = Random(7),
    private val delayMs: (FakeMode) -> Long = ::defaultDelayMs,
) : SignalSource {
    override val kind: SignalKind = SignalKind.FAKE

    @Volatile private var lastNonce: ByteArray? = null

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
        val current = mode
        return flow {
            if (current == FakeMode.SILENT) return@flow
            emit(SignalEvent.Status("challenge sent (fake, $current) · box ${req.boxId}"))
            delay(delayMs(current))
            emit(
                when (current) {
                    FakeMode.HONEST -> answer(VerdictKind.FRIEND, null, freshNonce(), -58, 42)
                    FakeMode.REPLAY -> answer(VerdictKind.FOE, "replayed nonce", lastNonce ?: freshNonce(), -61, 40)
                    FakeMode.RELAY -> answer(VerdictKind.FOE, "position mismatch", freshNonce(), -66, 180)
                    FakeMode.LATE -> answer(VerdictKind.FRIEND, null, freshNonce(), -60, 3450)
                    FakeMode.IMPOSTOR -> answer(VerdictKind.FOE, "bad tag", freshNonce(), -59, 45)
                    FakeMode.WEAK -> answer(VerdictKind.FRIEND, null, freshNonce(), -84, 48)
                    FakeMode.SILENT -> error("unreachable")
                },
            )
        }
    }

    private fun freshNonce(): ByteArray = synchronized(random) { random.nextBytes(16) }.also { lastNonce = it }

    private fun answer(verdict: VerdictKind, reason: String?, nonce: ByteArray, rssiDbm: Int?, responseMs: Long) =
        SignalEvent.Answer(verdict, reason, droneId, nonce, rssiDbm, responseMs, SignalKind.FAKE)

    companion object {
        /** Realistic answer times for the demo: 40-400 ms, LATE 3.5 s. */
        fun defaultDelayMs(mode: FakeMode): Long = when (mode) {
            FakeMode.HONEST -> 180
            FakeMode.REPLAY -> 160
            FakeMode.RELAY -> 400
            FakeMode.LATE -> 3_500
            FakeMode.IMPOSTOR -> 120
            FakeMode.WEAK -> 220
            FakeMode.SILENT -> 0
        }
    }
}
