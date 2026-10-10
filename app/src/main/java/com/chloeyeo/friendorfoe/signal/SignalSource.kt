package com.chloeyeo.friendorfoe.signal

import kotlinx.coroutines.flow.Flow

/**
 * Contract 2 of 3 (README section 3): stream 2 provides, the session consumes. The gadget generates the nonce,
 * runs the radio challenge-response and the crypto, and reports the verdict with its response time; the phone
 * re-checks ([ReChecks]) and draws. Implementations: [UsbGadgetSignalSource] (Ihan's ESP32-C3 reader over USB
 * serial, `QUERY <id>` in and JSON event lines out via [ReaderLink], see docs/GADGET_LINK.md) and
 * [FakeSignalSource] (scripted answers for every verdict, the on-stage fallback).
 */
interface SignalSource {
    /**
     * Runs one challenge for [req] and emits the gadget's lines as they arrive: zero or more
     * [SignalEvent.Status], then at most one [SignalEvent.Answer]; the flow completes after the answer or when the
     * source gives up. A source that emits nothing within the 10 s window means `UNKNOWN · no response`.
     */
    suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent>

    /** [SignalKind.USB_GADGET] or [SignalKind.FAKE]. */
    val kind: SignalKind

    /** Releases the link (closes the USB port); a second call is a no-op. */
    fun close() {}
}
