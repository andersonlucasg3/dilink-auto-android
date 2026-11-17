package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WifiGatewayIp.format 单测：DhcpInfo.gateway 打包整数的字节序契约。
 * 与 android 的 Formatter.formatIpAddress 惯例一致（LSB first）。车机服务回退 / 手填默认 /
 * 手机 CarIpLocator 三处共用，此前全仓零覆盖。
 */
class WifiGatewayIpTest {

    private fun pack(a: Int, b: Int, c: Int, d: Int) =
        (a and 0xFF) or ((b and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((d and 0xFF) shl 24)

    @Test
    fun `zero gateway is null`() {
        assertNull(WifiGatewayIp.format(0))
    }

    @Test
    fun `typical gateway bytes are extracted lsb first`() {
        assertEquals("192.168.43.1", WifiGatewayIp.format(pack(192, 168, 43, 1)))
        assertEquals("10.0.0.1", WifiGatewayIp.format(pack(10, 0, 0, 1)))
    }

    @Test
    fun `all ones int formats without sign extension`() {
        assertEquals("255.255.255.255", WifiGatewayIp.format(-1))
    }

    @Test
    fun `every octet value round trips`() {
        val octets = listOf(1, 2, 127, 168, 192, 254, 255)
        for (a in octets) for (b in octets) for (c in octets) for (d in octets) {
            val gw = pack(a, b, c, d)
            if (gw == 0) continue
            assertEquals("$a.$b.$c.$d", WifiGatewayIp.format(gw))
        }
    }
}
