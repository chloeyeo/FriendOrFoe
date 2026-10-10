package com.chloeyeo.friendorfoe.ui.status

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineMonitorTest {
    @Test
    fun offlineOnlyWithAirplaneModeAndNoValidatedNetwork() {
        assertEquals(true, OfflineMonitor.isOffline(airplaneOn = true, validatedNetwork = false))
        // Airplane mode with Wi-Fi re-enabled to a hotspot that has internet: not offline.
        assertEquals(false, OfflineMonitor.isOffline(airplaneOn = true, validatedNetwork = true))
        // Mobile data off but airplane mode off: the radio can still transmit, not offline.
        assertEquals(false, OfflineMonitor.isOffline(airplaneOn = false, validatedNetwork = false))
    }
}
