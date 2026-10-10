package com.chloeyeo.friendorfoe.app

import android.app.Activity
import android.os.Bundle

/**
 * Target of `USB_DEVICE_ATTACHED` for the devices in `res/xml/usb_device_filter.xml` (Ihan's reader). Launched by
 * the system, which grants the app USB permission for that device (and remembers it with "always open"); it shows
 * nothing and finishes at once. [com.chloeyeo.friendorfoe.signal.UsbGadgetSignalSource] then finds the device on
 * its attach broadcast or its 2 s poll. Kept apart from MainActivity so a plug-in never stacks a second camera
 * screen.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
