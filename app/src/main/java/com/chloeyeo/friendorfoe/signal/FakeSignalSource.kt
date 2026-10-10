package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/** The drone-side gadget's attack modes, mirrored here so the phone can be demoed without hardware. */
enum class FakeMode { HONEST, REPLAY, RELAY, LATE, SILENT, IMPOSTOR }

/**
 * Scripted answers for every verdict, through the real session path (the same lines a gadget would send).
 * One [SignalEvent.Status] ("challenge sent") then the matching [SignalEvent.Answer] after a short delay:
 *
 * - HONEST: FRIEND, -58 dBm, 42 ms, fresh nonce
 * - REPLAY: FOE `replayed nonce`; re-uses the previous challenge's nonce (first call: a fresh one), so the
 *   phone's [ReChecks] ledger fires too
 * - RELAY: FOE `position mismatch`, a slower answer from farther away
 * - LATE: the gadget still says FRIEND but only after 3.5 s (responseMs 3450); [ReChecks] turns it into
 *   FOE `late response` with the 2.5 s budget
 * - SILENT: emits nothing at all; the coordinator's 10 s window ends in UNKNOWN `no response`
 * - IMPOSTOR: FOE `bad tag` (wrong key)
 *
 * [mode] can be switched between challenges (settings / demo buttons).
 */
class FakeSignalSource(
    @Volatile var mode: FakeMode = FakeMode.HONEST,
    private val droneId: String = "RQ-07",
    private val random: Random = Random(7),
) : SignalSource {
    override val kind: SignalKind = SignalKind.FAKE

    private var lastNonce: ByteArray? = null

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> = flow {
        val current = mode
        if (current == FakeMode.SILENT) return@flow
        emit(SignalEvent.Status("challenge sent (fake, $current)"))
        when (current) {
            FakeMode.HONEST -> {
                delay(300)
                emit(answer(VerdictKind.FRIEND, null, droneId, freshNonce(), -58, 42))
            }
            FakeMode.REPLAY -> {
                delay(300)
                val replayed = lastNonce ?: freshNonce()
                emit(answer(VerdictKind.FOE, "replayed nonce", droneId, replayed, -61, 40))
            }
            FakeMode.RELAY -> {
                delay(600)
                emit(answer(VerdictKind.FOE, "position mismatch", droneId, freshNonce(), -66, 180))
            }
            FakeMode.LATE -> {
                delay(3500)
                emit(answer(VerdictKind.FRIEND, null, droneId, freshNonce(), -60, 3450))
            }
            FakeMode.IMPOSTOR -> {
                delay(300)
                emit(answer(VerdictKind.FOE, "bad tag", droneId, freshNonce(), -59, 45))
            }
            FakeMode.SILENT -> Unit // handled above
        }
    }

    private fun freshNonce(): ByteArray = random.nextBytes(16).also { lastNonce = it }

    private fun answer(
        verdict: VerdictKind,
        reason: String?,
        droneId: String?,
        nonce: ByteArray,
        rssiDbm: Int?,
        responseMs: Long,
    ) = SignalEvent.Answer(verdict, reason, droneId, nonce, rssiDbm, responseMs, SignalKind.FAKE)
}
