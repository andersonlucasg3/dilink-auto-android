package com.dilinkauto.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WifiGatewayProbe 单测：WiFi 网关查找的"取不到就返回 null"契约。
 * ContextWrapper(null) 下 getSystemService 链路 NPE → 被 catch 吞掉 → null。
 */
class WifiGatewayProbeTest {
    @Test
    fun `returnsFallbackWhenServiceUnavailable`() {
        val ctx = android.content.ContextWrapper(null)
        assertTrue("网关不可用必须回落 fallback", com.dilinkauto.server.adb.WifiGatewayProbe.gatewayIpOr(ctx) == "")
        assertEquals("1.2.3.4", com.dilinkauto.server.adb.WifiGatewayProbe.gatewayIpOr(ctx, "1.2.3.4"))
    }
}
