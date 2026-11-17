package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarConnectionService 提取出的纯策略函数单测：
 *  - reconnectBackoffMs（重连退避：此前内联、从未被测试）
 *  - iconBudgetAccepts（聚合图标预算边界，对真实常量）
 * 两者为行为不变的提取，把原先"测试里另抄一份算术"变成测真实代码。
 */
class CarConnectionServicePolicyTest {

    @Test
    fun `reconnect backoff doubles then caps at eight seconds`() {
        assertEquals(500L, CarConnectionService.reconnectBackoffMs(0))  // <=1 → 500
        assertEquals(500L, CarConnectionService.reconnectBackoffMs(1))
        assertEquals(1_000L, CarConnectionService.reconnectBackoffMs(2))
        assertEquals(2_000L, CarConnectionService.reconnectBackoffMs(3))
        assertEquals(4_000L, CarConnectionService.reconnectBackoffMs(4))
        assertEquals(8_000L, CarConnectionService.reconnectBackoffMs(5)) // 500*16
        assertEquals(8_000L, CarConnectionService.reconnectBackoffMs(6)) // shift clamp
        assertEquals(8_000L, CarConnectionService.reconnectBackoffMs(100))
    }

    @Test
    fun `reconnect backoff is monotonic and bounded across the whole range`() {
        var prev = Long.MIN_VALUE
        var f = 1
        while (f <= 5000) {
            val ms = CarConnectionService.reconnectBackoffMs(f)
            assertTrue("退避必须单调不减 (f=$f ms=$ms)", ms >= prev)
            assertTrue("下限 500", ms >= 500L)
            assertTrue("上限 8000", ms <= 8000L)
            prev = ms
            f += 137
        }
    }

    @Test
    fun `icon budget boundary uses the real constant`() {
        val max = CarConnectionService.MAX_APP_LIST_ICON_BYTES
        assertEquals(64L shl 20, max)
        assertTrue(CarConnectionService.iconBudgetAccepts(0L, 1 shl 20))
        assertTrue("恰好在预算上限应被接受", CarConnectionService.iconBudgetAccepts(max - 1, 1))
        assertTrue("预算上限本身接受", CarConnectionService.iconBudgetAccepts(max, 0))
        assertFalse("超出 1 字节应被拒绝", CarConnectionService.iconBudgetAccepts(max, 1))
    }
}
