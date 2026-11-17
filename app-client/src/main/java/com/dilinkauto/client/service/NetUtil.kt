package com.dilinkauto.client.service

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Local IPv4 address enumeration shared by the phone UI's network-info card
 * ([com.dilinkauto.client.ui.NetworkInfo]) and the car-IP locator
 * ([CarIpLocator]).
 *
 * Both previously carried the same `NetworkInterface.getNetworkInterfaces()`
 * walk with IPv4 + non-loopback filters. Centralizing it here means a change
 * to the filter (e.g. excluding VPN interfaces) or the interface predicate
 * lands in one place — the UI and the locator previously drifted.
 *
 * Returns raw IPs without port; callers append their own port suffix.
 */
internal object NetUtil {

    /** Non-loopback IPv4 host addresses across all up interfaces. */
    fun localIpv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
                    .map { it.hostAddress!! }
            }
            .filter { !it.startsWith("127.") }
    } catch (_: Exception) {
        emptyList()
    }
}
