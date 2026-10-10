package com.chloeyeo.friendorfoe.signal

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.Flow

/**
 * TODO(stream 2 / Ihan): the real gadget link over USB (docs/GADGET_LINK.md). The phone is USB host (OTG); the
 * reader gadget enumerates as a USB CDC serial device (native on ESP32-S3/RP2040/SAMD, or through a CP210x/CH34x/
 * FTDI bridge); 115200 8N1; one JSON object per line, encoded/decoded by [GadgetLineCodec].
 *
 * Plan with `com.github.mik3y:usb-serial-for-android:3.9.0` (already on the classpath):
 * 1. `val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager`;
 *    `UsbSerialProber.getDefaultProber().findAllDrivers(manager)` (add a custom `ProbeTable` if the board's VID/PID
 *    is not in the default table); `manager.requestPermission(device, PendingIntent)` once, wait for the broadcast.
 * 2. `val port = driver.ports[0]; port.open(manager.openDevice(device)); port.setParameters(115200, 8,
 *    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); port.dtr = true`.
 * 3. Reader loop on `Dispatchers.IO` (or `SerialInputOutputManager`): accumulate bytes, split on '\n', feed each
 *    line to [GadgetLineCodec.parseLine], fan the events out to the current [challenge] flow.
 * 4. [challenge]: record `SystemClock.elapsedRealtime()`, write `encodeChallenge(req) + "\n"`, emit every
 *    [SignalEvent.Status] line, emit the [SignalEvent.Answer] and complete; give up after 10 s. The coordinator
 *    measures the phone-side round trip from the write to the answer and hands it to [ReChecks].
 * 5. `{"cmd":"status"}` on connect for the gadget panel (`fw`, `battery`, `mode`); [close] closes the port.
 */
class UsbGadgetSignalSource(private val context: Context) : SignalSource {
    override val kind: SignalKind = SignalKind.USB_GADGET

    /** The open CDC port once stream 2 connects it; null until then. */
    private var port: UsbSerialPort? = null

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> = throw NotImplementedError("stream 2")

    override fun close() {
        // TODO(stream 2): port?.close(); port = null; stop the reader loop.
        port = null
    }
}
