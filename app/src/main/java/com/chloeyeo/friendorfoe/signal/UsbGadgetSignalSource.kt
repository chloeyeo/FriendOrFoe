package com.chloeyeo.friendorfoe.signal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real gadget link over USB (docs/GADGET_LINK.md, SIG-01). The phone is USB host (OTG); the reader gadget
 * enumerates as a USB serial device (CDC ACM, or a CP210x/CH34x/FTDI bridge) and is opened with
 * usb-serial-for-android at 115200 8N1 with DTR asserted. One JSON object per line, encoded/decoded by
 * [GadgetLineCodec], split by [LineSplitter] (`\n`, `\r\n` tolerated, 512 bytes max).
 *
 * Lifecycle: [start] registers for USB attach/detach and the permission answer and polls every 2 s; the first
 * serial device found is asked for permission once per attach (system dialog), then opened; `{"cmd":"status"}` is
 * written on open. A reader thread turns lines into [SignalEvent]s: while a [challenge] is open they go to its
 * flow, otherwise they go to [listener] (status replies, answers with no open challenge, skipped lines). Unplug,
 * a read error or a detach broadcast closes the port and reports `connected = false`; the 2 s poll reopens it on
 * re-plug. Nothing here touches the network, and no USB error crashes the app.
 *
 * Threading: connection management runs on one `gadget-link` thread, reading on one `gadget-read` thread per
 * open port; [challenge] writes from the caller's coroutine (on [Dispatchers.IO]).
 *
 * @param prober the driver lookup; [UsbSerialProber.getDefaultProber] knows CDC ACM (by interface class), FTDI,
 *   CP210x, CH34x and Prolific. A board the default table misses needs [proberWithCdcAcm].
 */
class UsbGadgetSignalSource(
    context: Context,
    private val prober: () -> UsbSerialProber = { UsbSerialProber.getDefaultProber() },
) : SignalSource {
    override val kind: SignalKind = SignalKind.USB_GADGET

    /** Link state and lines that belong to no challenge; called on the link and reader threads. */
    interface Listener {
        fun onLink(connected: Boolean, note: String)
        fun onLine(text: String)
    }

    @Volatile var listener: Listener? = null

    private val app = context.applicationContext
    private val manager: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var executor: ScheduledExecutorService? = null

    /** The open port; null while no gadget is connected. Written on the link thread only. */
    @Volatile private var port: UsbSerialPort? = null

    /** The open challenge's inbox; at most one (the coordinator refuses a second tap). */
    @Volatile private var pending: Channel<SignalEvent>? = null

    private val writeLock = Any()

    /** Link thread only: the device already asked for permission, so the dialog is not raised every 2 s. */
    private var askedFor: String? = null
    private var reportedUnknown: String? = null

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
    }

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
        val p = port ?: throw GadgetUnavailableException("no gadget")
        val inbox = Channel<SignalEvent>(Channel.UNLIMITED)
        pending?.close()
        pending = inbox
        try {
            withContext(Dispatchers.IO) { writeLine(p, GadgetLineCodec.encodeChallenge(req)) }
        } catch (e: IOException) {
            if (pending === inbox) pending = null
            onLink { drop(p, "gadget write failed") }
            throw GadgetUnavailableException("gadget write failed", e)
        }
        return flow {
            try {
                emit(SignalEvent.Status("challenge sent · box ${req.boxId}"))
                // An inbox closed with a cause (unplug mid-challenge) rethrows GadgetUnavailableException here.
                for (event in inbox) {
                    emit(event)
                    if (event is SignalEvent.Answer) break
                }
            } finally {
                if (pending === inbox) pending = null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (started.get()) runCatching { app.unregisterReceiver(receiver) }
        executor?.shutdownNow()
        port?.let(::closeQuietly)
        port = null
        pending?.close(GadgetUnavailableException("gadget link closed"))
        pending = null
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
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.setDTR(true)
        } catch (e: IOException) {
            closeQuietly(p)
            listener?.onLink(false, "gadget ${ids(device)} open failed: ${e.message}")
            return
        }
        port = p
        listener?.onLink(true, "gadget connected · ${ids(device)} · ${driver.javaClass.simpleName}")
        Thread({ readLoop(p) }, "gadget-read").apply { isDaemon = true }.start()
        try {
            writeLine(p, GadgetLineCodec.encodeStatusRequest())
        } catch (e: IOException) {
            drop(p, "gadget write failed")
        }
    }

    /** Closes [p] if it is still the open port, and tells the open challenge and the listener. */
    private fun drop(p: UsbSerialPort, why: String) {
        if (port !== p) return
        port = null
        askedFor = null
        closeQuietly(p)
        pending?.close(GadgetUnavailableException(why))
        pending = null
        listener?.onLink(false, why)
    }

    // ---- reader thread ----

    private fun readLoop(p: UsbSerialPort) {
        val splitter = LineSplitter()
        val buf = ByteArray(READ_BUFFER)
        try {
            while (!closed && port === p) {
                val n = p.read(buf, READ_TIMEOUT_MS)
                if (n > 0) splitter.feed(buf, 0, n).forEach(::handle)
            }
        } catch (e: IOException) {
            Log.i(TAG, "gadget read ended: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "gadget reader: ${t.javaClass.simpleName}")
        }
        onLink { drop(p, "gadget disconnected") }
    }

    private fun handle(piece: LineSplitter.Piece) {
        val line = when (piece) {
            is LineSplitter.Piece.Oversize -> {
                listener?.onLine("gadget line over ${LineSplitter.MAX_LINE_BYTES} bytes skipped (${piece.bytes} bytes)")
                return
            }
            is LineSplitter.Piece.Line -> piece.text
        }
        val event = try {
            GadgetLineCodec.parseLine(line)
        } catch (e: IllegalArgumentException) {
            // Boot banners, debug prints, a firmware/phone disagreement: skipped, logged without a full nonce.
            listener?.onLine("gadget line skipped: ${Redact.line(line)}")
            return
        }
        if (event == null) {
            if (line.isNotBlank()) listener?.onLine("gadget line ignored (unknown type): ${Redact.line(line)}")
            return
        }
        val inbox = pending
        if (inbox != null && inbox.trySend(event).isSuccess) return
        when (event) {
            is SignalEvent.Answer ->
                listener?.onLine("answer with no open challenge ignored · ${event.verdict} · nonce ${Redact.nonce(event.nonce)}")
            is SignalEvent.Status -> listener?.onLine("gadget ${event.text}")
        }
    }

    private fun writeLine(p: UsbSerialPort, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) { p.write(bytes, WRITE_TIMEOUT_MS) }
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
        private const val READ_BUFFER = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERMISSION = "com.chloeyeo.friendorfoe.USB_PERMISSION"

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
