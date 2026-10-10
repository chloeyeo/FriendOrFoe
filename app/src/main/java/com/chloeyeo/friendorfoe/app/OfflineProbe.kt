package com.chloeyeo.friendorfoe.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * The phone half of [OfflineRule]: reads airplane mode and whether the active network is validated, and answers
 * the rule. Injected as `IffCoordinator(offlineProbe = OfflineProbe(context))` and polled from `IffCoordinator.tick`
 * at ~1 Hz on the runtime lane (RESEARCH section O: two cheap binder calls, no callbacks to register or leak).
 * Needs only `ACCESS_NETWORK_STATE`, already in the manifest.
 */
class OfflineProbe(context: Context) : () -> Boolean {
    private val app: Context = context.applicationContext
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)

    override fun invoke(): Boolean {
        val airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val validated = try {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (_: SecurityException) {
            false
        }
        return OfflineRule.isOffline(airplane, validated)
    }
}
