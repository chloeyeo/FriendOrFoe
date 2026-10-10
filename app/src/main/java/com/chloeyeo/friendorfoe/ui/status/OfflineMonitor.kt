package com.chloeyeo.friendorfoe.ui.status

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Feeds StatusUi.offline: airplane mode is on and no network is validated (the hotspot LAN has no
 * internet, so it is never validated). Polled, because both reads are cheap and a poll cannot miss
 * a toggle the way a dropped callback can.
 */
object OfflineMonitor {
    private const val POLL_MS = 2_000L

    fun isOffline(airplaneOn: Boolean, validatedNetwork: Boolean): Boolean = airplaneOn && !validatedNetwork

    fun isOffline(context: Context): Boolean {
        val airplaneOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return isOffline(airplaneOn, validatedNetwork = false)
        val validated = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return isOffline(airplaneOn, validated)
    }

    fun flow(context: Context): Flow<Boolean> = flow {
        while (true) {
            emit(isOffline(context))
            delay(POLL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
