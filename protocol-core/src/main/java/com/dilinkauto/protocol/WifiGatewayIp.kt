package com.dilinkauto.protocol

/**
 * Formats a WiFi gateway IP from its packed-int representation.
 *
 * Android's [android.net.wifi.DhcpInfo.gateway] is stored as a packed int
 * (LSB first), so the four bytes must be masked and shifted out in order.
 * Returns null when the gateway is 0 — callers treat null as "no gateway known".
 *
 * Shared by the car service (gateway-based phone-IP fallback), the car UI
 * (ManualConnectBox default), and the phone's car-IP locator (gateway probe).
 * Takes the raw int rather than `DhcpInfo` so this module stays free of the
 * wifi framework dependency — callers pass `wm.dhcpInfo.gateway`.
 */
object WifiGatewayIp {
    fun format(gatewayInt: Int): String? {
        val gw = gatewayInt
        if (gw == 0) return null
        return String.format(
            "%d.%d.%d.%d",
            gw and 0xFF,
            (gw shr 8) and 0xFF,
            (gw shr 16) and 0xFF,
            (gw shr 24) and 0xFF
        )
    }
}
