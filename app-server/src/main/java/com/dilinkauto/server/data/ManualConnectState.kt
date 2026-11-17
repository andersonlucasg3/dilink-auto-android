package com.dilinkauto.server.data

import android.content.Context
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.server.adb.WifiGatewayProbe

/**
 * Default-IP resolution + last-used-IP memory for the manual-connect box
 * (audit R3-SRP-08). Prefs access and the WiFi-gateway probe used to live
 * inside the composable; the box now only binds to this state.
 */
class ManualConnectState(private val context: Context) {

    private val prefs = context.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)

    /** Saved IP, else the WiFi gateway, else the Android-hotspot default. */
    val defaultIp: String
        get() = prefs.getString(KEY_LAST_MANUAL_IP, null)
            ?: WifiGatewayProbe.gatewayIpOr(context).ifEmpty { HOTSPOT_DEFAULT_IP }

    /** Remember [ip] as the last-used address for the next visit. */
    fun remember(ip: String) {
        prefs.edit().putString(KEY_LAST_MANUAL_IP, ip).apply()
    }

    private companion object {
        const val KEY_LAST_MANUAL_IP = "last_manual_ip"
        const val HOTSPOT_DEFAULT_IP = "192.168.43.1"
    }
}
