package com.chloeyeo.friendorfoe.signal

import kotlinx.coroutines.flow.Flow

/** Which source a tap goes to (Settings → Gadget, `--es signal auto|usb|fake`). */
enum class SignalChoice(val label: String) {
    /** The USB gadget when one is connected, otherwise the fake (marked `(fake)` in the log and the gadget panel). */
    AUTO("Auto"),

    /** The USB gadget only; no gadget -> `UNKNOWN · no gadget` at once (OBS-02). */
    USB("USB gadget"),

    /** Always the scripted [FakeSignalSource] in the selected [FakeMode]. */
    FAKE("Fake");

    companion object {
        fun parse(text: String?): SignalChoice? = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}

/**
 * The one [SignalSource] the coordinator holds: decides per challenge between the USB gadget and the fake, from
 * [choice] and [fakeMode] (read on every tap, so a Settings change applies to the next tap without a restart).
 * [kind] reports the source the last challenge went to.
 *
 * @param usbConnected whether the USB gadget's port is open right now (AUTO falls back to the fake when not)
 */
class SwitchingSignalSource(
    private val usb: SignalSource,
    private val fake: FakeSignalSource,
    private val choice: () -> SignalChoice,
    private val fakeMode: () -> FakeMode,
    private val usbConnected: () -> Boolean,
) : SignalSource {
    @Volatile private var last: SignalKind = SignalKind.FAKE

    override val kind: SignalKind get() = last

    /** The source the next challenge would use. */
    fun pick(): SignalSource = when (choice()) {
        SignalChoice.USB -> usb
        SignalChoice.FAKE -> fake
        SignalChoice.AUTO -> if (usbConnected()) usb else fake
    }

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
        val source = pick()
        if (source === fake) fake.mode = fakeMode()
        last = source.kind
        return source.challenge(req)
    }

    override fun close() {
        usb.close()
        fake.close()
    }
}
