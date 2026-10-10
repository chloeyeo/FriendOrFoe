package com.chloeyeo.friendorfoe.app

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.chloeyeo.friendorfoe.BuildConfig
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.session.IffCoordinator
import com.chloeyeo.friendorfoe.signal.FakeMode
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import com.chloeyeo.friendorfoe.signal.ReChecks
import com.chloeyeo.friendorfoe.signal.SignalChoice
import com.chloeyeo.friendorfoe.signal.SwitchingSignalSource
import com.chloeyeo.friendorfoe.signal.UsbGadgetSignalSource

/**
 * The signal path's wiring, kept out of [FofApplication] and [LaunchOptions]: one [SwitchingSignalSource] over the
 * USB gadget and the fake, chosen per tap from [Prefs.signalChoice] / [Prefs.fakeMode]; the USB link's state goes
 * to the gadget dot ([com.chloeyeo.friendorfoe.session.StatusUi.gadgetConnected]) and its stray lines to the event
 * log.
 *
 * Debug launch extras (persisted, like `demo`):
 *
 * | extra | adb form | effect |
 * |---|---|---|
 * | `signal` | `--es signal auto` / `usb` / `fake` | [Prefs.signalChoice] |
 * | `fake_mode` | `--es fake_mode honest` / `replay` / `relay` / `late` / `silent` / `impostor` / `weak` | [Prefs.fakeMode] |
 */
object SignalWiring {
    private const val TAG = "FoF"

    /** The pieces [attach] needs after the coordinator exists. */
    class Parts(val source: SwitchingSignalSource, val usb: UsbGadgetSignalSource)

    fun create(app: FofApplication, prefs: Prefs): Parts {
        val usb = UsbGadgetSignalSource(app)
        val source = SwitchingSignalSource(
            usb = usb,
            fake = FakeSignalSource(),
            choice = { prefs.signalChoice },
            fakeMode = { prefs.fakeMode },
            usbConnected = { usb.isConnected },
        )
        return Parts(source, usb)
    }

    /** The coordinator for [FofApplication.coordinator]: the given detector, the switching source, the prefs' budgets. */
    fun coordinator(app: FofApplication, prefs: Prefs, detector: Detector): IffCoordinator {
        val parts = create(app, prefs)
        val coordinator = IffCoordinator(
            detector = detector,
            signalSource = parts.source,
            reChecks = ReChecks(prefs.reChecksConfig()),
            offlineProbe = OfflineProbe(app),
            challengerId = { prefs.challengerId },
            elapsedMillis = SystemClock::elapsedRealtime,
        )
        attach(parts.usb, coordinator)
        return coordinator
    }

    /** Routes the USB link's state and stray lines into [coordinator], then starts watching USB. */
    fun attach(usb: UsbGadgetSignalSource, coordinator: IffCoordinator) {
        usb.listener = object : UsbGadgetSignalSource.Listener {
            override fun onLink(connected: Boolean, note: String) {
                coordinator.setStatus { it.copy(gadgetConnected = connected, rssiDbm = if (connected) it.rssiDbm else null) }
                coordinator.log(note)
                Log.i(TAG, note)
            }

            override fun onLine(text: String) {
                coordinator.log(text)
            }
        }
        usb.start()
    }

    /** `signal` and `fake_mode` launch extras (debug builds only). */
    fun applyLaunchExtras(prefs: Prefs, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("signal")?.let { text ->
            SignalChoice.parse(text)?.let { prefs.signalChoice = it; Log.i(TAG, "launch extra signal=$it") }
                ?: Log.w(TAG, "signal extra ignored: $text")
        }
        intent.getStringExtra("fake_mode")?.let { text ->
            FakeMode.parse(text)?.let { prefs.fakeMode = it; Log.i(TAG, "launch extra fake_mode=$it") }
                ?: Log.w(TAG, "fake_mode extra ignored: $text")
        }
    }
}
