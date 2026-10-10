package com.chloeyeo.friendorfoe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-12: OFFLINE only when airplane mode is on AND no network is validated. */
class OfflineRuleTest {
    @Test
    fun `airplane on and nothing validated is offline`() {
        assertTrue(OfflineRule.isOffline(airplaneOn = true, validated = false))
    }

    @Test
    fun `airplane on but a validated network is not offline`() {
        assertFalse(OfflineRule.isOffline(airplaneOn = true, validated = true))
    }

    @Test
    fun `airplane off and nothing validated is not offline`() {
        assertFalse(OfflineRule.isOffline(airplaneOn = false, validated = false))
    }

    @Test
    fun `airplane off and validated is not offline`() {
        assertFalse(OfflineRule.isOffline(airplaneOn = false, validated = true))
    }
}
