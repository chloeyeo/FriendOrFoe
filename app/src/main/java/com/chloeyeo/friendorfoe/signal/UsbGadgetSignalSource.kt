package com.chloeyeo.friendorfoe.signal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real gadget link over USB (docs/GADGET_LINK.md, SIG-01): Ihan's ESP32-C3 reader on its built-in
 * USB-Serial/JTAG (CDC ACM, 303a:1001), opened with usb-serial-for-android at 115200. The protocol (plain-text
 * commands in, JSON lines out, the STATUS handshake, request-id attribution, timeouts) lives in [ReaderLink]; this
 * class only finds, opens, reads and closes the port.
 *
 * **Control lines, as Ihan's host:** on the C3's USB-Serial/JTAG, DTR together with an RTS change can drop the chip
 * into download mode, and it talks fine without DTR, so 303a:1001 is opened with DTR and RTS **off**; any other CDC
 * board gets DTR on and RTS off. (pySerial sets them before open; Android can only set them right after.)
 *
 * Lifecycle: [start] registers for USB attach/detach and the permission answer and polls every 2 s; the first
 * serial device found is asked for permission once per attach (system dialog; `UsbAttachActivity` and its device
 * filter let Android remember it), then opened and handed to [link], which starts the STATUS handshake. The reader
 * thread feeds every byte to [link] in a loop (the C3 only sends while the host reads). Unplug, a read or write
 * error, or a detach broadcast closes the port; the 2 s poll reopens it on re-plug. No USB error crashes the app.
 *
 * Threading: connection management and [ReaderLink.tick] (every 250 ms) on one `gadget-link` thread, reading on one
 * `gadget-read` thread per open port; [challenge] writes from the caller's coroutine.
 *
 * @param prober the driver lookup: the default table (CDC ACM by interface class, FTDI, CP210x, CH34x, Prolific)
 *   plus the C3's 303a:1001 mapped to CDC ACM explicitly, in case the composite CDC + JTAG device is not claimed.
 */
class UsbGadgetSignalSource(
    context: Context,
    private val prober: () -> UsbSerialProber = proberWithCdcAcm(ESP_VID to ESP_USB_JTAG_PID),
    clock: () -> Long = SystemClock::elapsedRealtime,
) : SignalSource {
    override val kind: SignalKind = SignalKind.USB_GADGET

    /** Link state and lines that belong to no challenge; called on the link and reader threads. */
    interface Listener {
        /** [connected] once the port is open AND the reader answered the handshake; false on any loss. */
        fun onLink(connected: Boolean, note: String)
        fun onLine(text: String)

        /** A RESULT for one of this phone's earlier queries, after its challenge ended: never drawn. */
        fun onStaleAnswer(answer: SignalEvent.Answer, why: String) {
            onLine("$why · ${answer.verdict}")
        }
    }

    @Volatile var listener: Listener? = null

    /** The protocol engine; exposed for the status strip (reader state) and tests. */
    val link = ReaderLink(clock)

    private val app = context.applicationContext
    private val manager: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var executor: ScheduledExecutorService? = null

    /** The open port; null while no gadget is connected. Written on the link thread only. */
    @Volatile private var port: UsbSerialPort? = null

    private val writeLock = Any()

    /** Link thread only: the device already asked for permission, so the dialog is not raised every 2 s. */
    private var askedFor: String? = null
    private var reportedUnknown: String? = null

    /** The port is open (the reader may still be handshaking; a tap then waits briefly or says `gadget not ready`). */
    val isConnected: Boolean get() = port != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                onLink {
                    val p = port
                    if (p != null && (device == null || p.device.deviceName == device.deviceName)) drop(p, "gadget unplugged")
                }
            } else {
                // Attached, or the permission answer: the immutable PendingIntent carries no extras, so look again.
                onLink { poll() }
            }
        }
    }

    init {
        link.listener = object : ReaderLink.Listener {
            override fun onLine(text: String) {
                listener?.onLine(text)
            }

            override fun onReader(ready: Boolean, note: String) {
                listener?.onLink(ready && port != null, note)
            }

            override fun onStaleAnswer(answer: SignalEvent.Answer, why: String) {
                listener?.onStaleAnswer(answer, why)
            }
        }
    }

    /** Starts watching USB; idempotent and cheap with nothing plugged in. */
    fun start() {
        if (manager == null || closed || !started.compareAndSet(false, true)) return
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gadget-link").apply { isDaemon = true } }
        executor = ex
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { Log.w(TAG, "USB receiver not registered; polling only", it) }
        ex.scheduleWithFixedDelay({ guarded { poll() } }, 0L, POLL_MS, TimeUnit.MILLISECONDS)
        ex.scheduleWithFixedDelay({ guarded { if (port != null) link.tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
        if (port == null) throw GadgetUnavailableException(ReaderLink.NO_GADGET)
        return link.challenge(req)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (started.get()) runCatching { app.unregisterReceiver(receiver) }
        executor?.shutdownNow()
        port?.let(::closeQuietly)
        port = null
        link.detach("gadget link closed")
    }

    // ---- link thread ----

    private fun onLink(block: () -> Unit) {
        val ex = executor ?: return
        runCatching { ex.execute { guarded(block) } }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "gadget link: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun poll() {
        val usb = manager ?: return
        if (closed || port != null) return
        val driver: UsbSerialDriver? = prober().findAllDrivers(usb).firstOrNull()
        if (driver == null) {
            askedFor = null
            reportUnknownDevice(usb)
            return
        }
        reportedUnknown = null
        val device = driver.device
        if (!usb.hasPermission(device)) {
            if (askedFor != device.deviceName) {
                askedFor = device.deviceName
                usb.requestPermission(device, permissionIntent())
                listener?.onLink(false, "gadget ${ids(device)} found · asking USB permission")
            }
            return
        }
        open(usb, driver)
    }

    /** A USB device no driver claims: report its VID:PID once, for a [proberWithCdcAcm] entry. */
    private fun reportUnknownDevice(usb: UsbManager) {
        val other = usb.deviceList.values.firstOrNull()
        if (other == null) {
            reportedUnknown = null
            return
        }
        if (reportedUnknown == other.deviceName) return
        reportedUnknown = other.deviceName
        listener?.onLine("USB device ${ids(other)} is not a known serial device (needs a ProbeTable entry)")
    }

    private fun open(usb: UsbManager, driver: UsbSerialDriver) {
        val device = driver.device
        val connection = usb.openDevice(device)
        if (connection == null) {
            listener?.onLink(false, "gadget ${ids(device)} could not be opened")
            return
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            connection.close()
            listener?.onLink(false, "gadget ${ids(device)} has no serial port")
            return
        }
        val native = isEspUsbJtag(device.vendorId, device.productId)
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.setDTR(dtrOnOpen(device.vendorId, device.productId))
            p.setRTS(false)
        } catch (e: IOException) {
            closeQuietly(p)
            listener?.onLink(false, "gadget ${ids(device)} open failed: ${e.message}")
            return
        } catch (e: UnsupportedOperationException) {
            closeQuietly(p)
            listener?.onLink(false, "gadget ${ids(device)} open failed: ${e.message}")
            return
        }
        port = p
        listener?.onLine(
            "gadget port open · ${ids(device)} · ${driver.javaClass.simpleName} · DTR ${if (native) "off" else "on"} · RTS off · handshaking",
        )
        Thread({ readLoop(p) }, "gadget-read").apply { isDaemon = true }.start()
        link.attach { bytes -> writeOrDrop(p, bytes) }
    }

    /** Closes [p] if it is still the open port, and tells the open challenge and the listener. */
    private fun drop(p: UsbSerialPort, why: String) {
        if (port !== p) return
        port = null
        askedFor = null
        closeQuietly(p)
        link.detach(why)
        listener?.onLink(false, why)
    }

    // ---- reader thread ----

    /** Keeps reading even when idle: the C3's USB-Serial/JTAG only sends while the host is reading. */
    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(READ_BUFFER)
        try {
            while (!closed && port === p) {
                val n = p.read(buf, READ_TIMEOUT_MS)
                if (n > 0) link.feed(buf, 0, n)
            }
        } catch (e: IOException) {
            Log.i(TAG, "gadget read ended: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "gadget reader: ${t.javaClass.simpleName}")
        }
        onLink { drop(p, "gadget disconnected") }
    }

    private fun writeOrDrop(p: UsbSerialPort, bytes: ByteArray) {
        try {
            synchronized(writeLock) { p.write(bytes, WRITE_TIMEOUT_MS) }
        } catch (e: IOException) {
            onLink { drop(p, "gadget write failed") }
            throw e
        }
    }

    private fun permissionIntent(): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        // Immutable on API 31+ (explicit intent); the receiver re-checks hasPermission instead of reading extras.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(app, 0, intent, flags)
    }

    private fun closeQuietly(p: UsbSerialPort) {
        runCatching { p.close() }
    }

    companion object {
        private const val TAG = "FoF"
        const val BAUD = 115_200
        const val POLL_MS = 2_000L
        const val TICK_MS = 250L
        private const val READ_BUFFER = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERMISSION = "com.chloeyeo.friendorfoe.USB_PERMISSION"

        /** Espressif's vendor id and the ESP32-C3/S3 built-in USB-Serial/JTAG product id (Ihan's reader). */
        const val ESP_VID = 0x303A
        const val ESP_USB_JTAG_PID = 0x1001

        fun isEspUsbJtag(vid: Int, pid: Int): Boolean = vid == ESP_VID && pid == ESP_USB_JTAG_PID

        /** Ihan's rule (serial_connection.py): DTR off for the C3's USB-Serial/JTAG, on for any other CDC board. */
        fun dtrOnOpen(vid: Int, pid: Int): Boolean = !isEspUsbJtag(vid, pid)

        /**
         * The default prober plus explicit VID/PID pairs mapped to [CdcAcmSerialDriver], for a native-USB board
         * the default table misses: `proberWithCdcAcm(0x303A to 0x1001)`.
         */
        fun proberWithCdcAcm(vararg vidPid: Pair<Int, Int>): () -> UsbSerialProber = {
            val table = UsbSerialProber.getDefaultProbeTable()
            vidPid.forEach { (vid, pid) -> table.addProduct(vid, pid, CdcAcmSerialDriver::class.java) }
            UsbSerialProber(table)
        }

        private fun ids(d: UsbDevice): String = "%04x:%04x".format(d.vendorId, d.productId)
    }
}
