package com.chloeyeo.friendorfoe.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Ihan's port-opening rule (serial_connection.py) and the USB device filter that lets Android remember it. */
class UsbOpenRulesTest {
    @Test
    fun `the C3 USB-Serial-JTAG opens with DTR off, any other CDC board with DTR on`() {
        assertTrue(UsbGadgetSignalSource.isEspUsbJtag(0x303A, 0x1001))
        assertFalse(UsbGadgetSignalSource.dtrOnOpen(0x303A, 0x1001))
        assertTrue(UsbGadgetSignalSource.dtrOnOpen(0x239A, 0x80AC)) // NU40 / TinyUSB CDC
        assertTrue(UsbGadgetSignalSource.dtrOnOpen(0x303A, 0x4001)) // an ESP32-S3 TinyUSB CDC is a standard CDC
        assertEquals(115_200, UsbGadgetSignalSource.BAUD)
    }

    @Test
    fun `the device filter lists the reader's 303a 1001 in decimal`() {
        val xml = listOf(File("src/main/res/xml/usb_device_filter.xml"), File("app/src/main/res/xml/usb_device_filter.xml"))
            .firstOrNull { it.isFile }
        assumeTrue("usb_device_filter.xml not found from ${File(".").absolutePath}", xml != null)
        val ids = Regex("""vendor-id="(\d+)" product-id="(\d+)"""").findAll(xml!!.readText())
            .map { "%04x:%04x".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
        assertEquals(listOf("303a:1001", "239a:80ac"), ids)
    }
}
