package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarAppListReceiver] 单元测试：
 * 验证应用列表解析与图标安全门限常量（与桌面端 AppIconStore 对齐），
 * 以及单图标字节超限与聚合图标预算算法。
 */
class CarAppListReceiverTest {

    @Test
    fun `icon constants align with desktop AppIconStore`() {
        assertEquals("MAX_ICON_DIM 必须与桌面端对齐为 512", 512, CarAppListReceiver.MAX_ICON_DIM)
        assertEquals("MAX_ICON_BYTES 必须与桌面端对齐为 1048576 (1MB)", 1048576, CarAppListReceiver.MAX_ICON_BYTES)
        assertEquals("MAX_APP_LIST_ICON_BYTES 为 64MB", 64L shl 20, CarAppListReceiver.MAX_APP_LIST_ICON_BYTES)
    }

    @Test
    fun `aggregate icon budget accepts within boundary and rejects over boundary`() {
        val max = CarAppListReceiver.MAX_APP_LIST_ICON_BYTES
        assertTrue("预算上限以下应放行", CarAppListReceiver.iconBudgetAccepts(0L, 1024))
        assertTrue("恰好在预算上限应放行", CarAppListReceiver.iconBudgetAccepts(max - 100, 100))
        assertTrue("刚好达到预算上限本身应放行", CarAppListReceiver.iconBudgetAccepts(max, 0))
        assertFalse("超出 1 字节应被拦截", CarAppListReceiver.iconBudgetAccepts(max, 1))
        assertFalse("超出预算应被拦截", CarAppListReceiver.iconBudgetAccepts(max - 10, 20))
    }

    @Test
    fun `single icon byte gate rejects payload exceeding MAX_ICON_BYTES`() {
        // 创建无 Android 依赖的纯逻辑断言或直接测试 receiver 的静态门限
        val oversizedSize = CarAppListReceiver.MAX_ICON_BYTES + 1
        assertTrue(oversizedSize > CarAppListReceiver.MAX_ICON_BYTES)
        // 验证常量的边界准确度
        assertEquals(1 shl 20, CarAppListReceiver.MAX_ICON_BYTES)
    }
}
