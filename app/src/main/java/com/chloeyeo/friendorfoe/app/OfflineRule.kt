package com.chloeyeo.friendorfoe.app

/**
 * The OFFLINE badge rule (D-12), pure so it is unit-tested on the JVM; [OfflineProbe] feeds it on the phone.
 *
 * OFFLINE means airplane mode is on AND no network is validated (has reached the internet). Wi-Fi re-enabled under
 * airplane mode for the laptop LAN has no internet, stays not-validated, and so the badge stays OFFLINE.
 */
object OfflineRule {
    /**
     * @param airplaneOn `Settings.Global.AIRPLANE_MODE_ON != 0`
     * @param validated the active network has `NET_CAPABILITY_VALIDATED`
     */
    fun isOffline(airplaneOn: Boolean, validated: Boolean): Boolean = airplaneOn && !validated
}
