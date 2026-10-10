package com.chloeyeo.friendorfoe.cross.fof

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * The reader gadget over USB CDC serial (README "Gadget link over USB", docs/GADGET_LINK.md; the native app's
 * `UsbGadgetSignalSource` plan): the phone is USB host over OTG, 115200 8N1, one JSON object per line. This class
 * only moves lines; `src/signal/gadgetLineCodec.ts` encodes and parses them and `src/signal/usbGadgetSignalSource.ts`
 * runs the challenge. Boards the default prober does not know (native USB on ESP32-S3 / RP2040) are tried as CDC ACM.
 */
class UsbGadgetLink(
  private val context: Context,
  private val onLine: (String) -> Unit,
  private val onState: (connected: Boolean, error: String?) -> Unit,
) : SerialInputOutputManager.Listener {
  private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  private var port: UsbSerialPort? = null
  private var io: SerialInputOutputManager? = null
  private val buffer = StringBuilder()

  val isOpen: Boolean get() = port != null

  fun devices(): List<Map<String, Any?>> = drivers().map { d ->
    mapOf(
      "deviceId" to d.device.deviceId,
      "vendorId" to d.device.vendorId,
      "productId" to d.device.productId,
      "name" to (d.device.productName ?: d.device.deviceName),
    )
  }

  /** Opens the first gadget (or [deviceId]), asking for USB permission when needed; [done] gets its name. */
  fun open(deviceId: Int?, done: (Result<String>) -> Unit) {
    close()
    val driver = drivers().firstOrNull { deviceId == null || it.device.deviceId == deviceId }
    if (driver == null) {
      done(Result.failure(IllegalStateException("NO_GADGET")))
      return
    }
    if (manager.hasPermission(driver.device)) {
      done(connect(driver))
      return
    }
    val action = context.packageName + ".FOF_USB_PERMISSION"
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != action) return
        context.unregisterReceiver(this)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        done(if (granted) connect(driver) else Result.failure(IllegalStateException("USB_PERMISSION_DENIED")))
      }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val intent = Intent(action).setPackage(context.packageName)
    manager.requestPermission(driver.device, PendingIntent.getBroadcast(context, 0, intent, flags))
  }

  /** Writes one line (the newline is added here). */
  fun write(line: String): Boolean {
    val p = port ?: return false
    return try {
      p.write((line + "\n").toByteArray(Charsets.UTF_8), 500)
      true
    } catch (e: Exception) {
      onState(false, e.message ?: "write failed")
      close()
      false
    }
  }

  fun close() {
    io?.stop()
    io = null
    try {
      port?.close()
    } catch (_: Exception) {
    }
    val wasOpen = port != null
    port = null
    synchronized(buffer) { buffer.setLength(0) }
    if (wasOpen) onState(false, null)
  }

  override fun onNewData(data: ByteArray) {
    val lines = ArrayList<String>()
    synchronized(buffer) {
      buffer.append(String(data, Charsets.UTF_8))
      while (true) {
        val i = buffer.indexOf("\n")
        if (i < 0) break
        val line = buffer.substring(0, i).trimEnd('\r')
        buffer.delete(0, i + 1)
        if (line.isNotBlank()) lines += line
      }
      if (buffer.length > 8192) buffer.setLength(0) // a gadget that never sends a newline
    }
    lines.forEach(onLine)
  }

  override fun onRunError(e: Exception) {
    onState(false, e.message ?: "link lost")
    port = null
    io = null
  }

  private fun drivers(): List<UsbSerialDriver> {
    val known = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    if (known.isNotEmpty()) return known
    val table = ProbeTable()
    manager.deviceList.values.forEach { table.addProduct(it.vendorId, it.productId, CdcAcmSerialDriver::class.java) }
    return UsbSerialProber(table).findAllDrivers(manager)
  }

  private fun connect(driver: UsbSerialDriver): Result<String> = try {
    val connection = manager.openDevice(driver.device) ?: throw IllegalStateException("USB_OPEN_FAILED")
    val p = driver.ports[0]
    p.open(connection)
    p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    try {
      p.dtr = true // native-USB boards only start talking once DTR is up
    } catch (_: Exception) {
    }
    port = p
    io = SerialInputOutputManager(p, this).also { it.start() }
    onState(true, null)
    Result.success(driver.device.productName ?: driver.device.deviceName)
  } catch (e: Exception) {
    Result.failure(e)
  }
}
