package com.dilinkauto.server.adb

import android.content.Context
import android.net.wifi.WifiManager
import com.dilinkauto.protocol.WifiGatewayIp

/**
 * Reads the current WiFi gateway IP.
 *
 * [WifiGatewayIp] in protocol-core already owns the packed-int formatting and
 * deliberately takes a raw int so that module stays free of the wifi framework
 * dependency. What it cannot own is the *lookup* — resolving `WIFI_SERVICE` and
 * surviving a device where it returns null (WiFi off, permission not yet granted,
 * or a stubbed framework under test).
 *
 * That lookup was duplicated verbatim at two sites
 * (docs/audit-srp-dry.md DRY-7): the car service's gateway-based phone-IP
 * fallback, and the car UI's ManualConnectBox default. Both wrapped it in the
 * same try/catch.
 *
 * Lives in app-server rather than protocol-core because it is Android-framework
 * code; it is `internal` so it cannot be mistaken for part of the protocol
 * surface. The phone's `CarIpLocator` intentionally does *not* use this — it
 * receives an injected `WifiManager` to avoid a static Service reference.
 */
internal object WifiGatewayProbe {

    /**
     * @return the gateway IP, or null when WiFi is unavailable, the service
     *   cannot be obtained, or the gateway is unset.
     */
    fun gatewayIp(context: Context): String? = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.dhcpInfo?.let { WifiGatewayIp.format(it.gateway) }
    } catch (_: Exception) {
        null
    }

    /**
     * @return the gateway IP, or [fallback] when unavailable. Convenience for
     *   UI call sites that need a non-null default.
     */
    fun gatewayIpOr(context: Context, fallback: String = ""): String =
        gatewayIp(context) ?: fallback
}