package com.chloeyeo.friendorfoe.signal

import java.io.IOException

/**
 * Thrown by a [SignalSource] when it cannot run a challenge at all (no gadget on USB, no USB permission, the port
 * failed or was unplugged mid-challenge). The coordinator turns it into `UNKNOWN · <reason>` at once instead of
 * waiting out the 10 s window (OBS-02: no gadget and demo mode off -> `UNKNOWN · no gadget` immediately).
 */
class GadgetUnavailableException(val reason: String, cause: Throwable? = null) : IOException(reason, cause)
