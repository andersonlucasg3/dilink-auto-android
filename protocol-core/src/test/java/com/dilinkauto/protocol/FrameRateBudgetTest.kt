package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #203 的纯 JVM 契约测试：帧率「兑现」判定与 decodeMs 档位推导。
 *
 * 现场依据（`.tmp/run-1116`）：请求 30fps 的会话循环精确按 30 拍/秒走，但 VD
 * 只交付 17.2 个新帧/秒；车机 `decodeMs` 中位数 22–26ms、峰值 54.8ms，而 30fps
 * 的预算是 33.3ms。
 *
 * 注意：这里**不**断言「24fps 是实车上界」之类的结论 —— 30fps 是否可达要由实车
 * 回签判定，把它写死会让刷新率真正生效后也没有机会验证。
 */
class FrameRateBudgetTest {

    @Test
    fun `budget is 1000 over fps`() {
        assertEquals(50.0, FrameRateBudget.budgetMs(20), 1e-9)
        assertEquals(33.33, FrameRateBudget.budgetMs(30), 0.01)
        // 非法 fps 按 UI 范围钳制，不能除零。
        assertTrue(FrameRateBudget.budgetMs(0) > 0.0)
    }

    @Test
    fun `no measurement means no cap`() {
        // 没有实测 decodeMs → 不设上限（设置页不得据此禁用 30/60）。
        assertNull(FrameRateBudget.capFor(null))
        assertNull(FrameRateBudget.capFor(0.0))
        assertNull(FrameRateBudget.capFor(-5.0))
    }

    @Test
    fun `measured decode time well inside the budget allows the highest preset`() {
        // 5ms 的实测值任何档位都装得下 → 建议值给到最高预设档，而不是写死 24。
        val cap = FrameRateBudget.capFor(decodeMs = 5.0)
        assertEquals(SettingsFormat.FPS_PRESETS.max(), cap?.fps)
        assertTrue(cap!!.reason.contains("5.0"))
    }

    @Test
    fun `measured decode time narrows the suggestion through the per frame budget`() {
        // 24ms：60fps(16.7ms) 装不下，30fps(33.3ms×0.9=30ms) 装得下 → 建议 30。
        assertEquals(30, FrameRateBudget.capFor(decodeMs = 24.0)?.fps)
        // 40ms：30fps 的余量线 30ms 也装不下 → 退到 24fps（41.7×0.9=37.5 仍不够）→ 20。
        assertEquals(20, FrameRateBudget.capFor(decodeMs = 40.0)?.fps)
        val worst = FrameRateBudget.capFor(decodeMs = 54.8)
        assertEquals(20, worst?.fps)
        assertTrue("超出预算必须写进理由：${worst?.reason}", worst!!.reason.contains("54.8"))
    }

    @Test
    fun `target reached only above the tolerance band`() {
        assertTrue(FrameRateBudget.isTargetReached(30, 30.0))
        assertTrue(FrameRateBudget.isTargetReached(30, 27.0))   // 正好 90%
        assertFalse(FrameRateBudget.isTargetReached(30, 17.2))  // 实车 30fps 会话的实测值
        assertTrue(FrameRateBudget.isTargetReached(20, 20.0))
        assertFalse("0 帧率永远不算达成", FrameRateBudget.isTargetReached(0, 0.0))
    }
}
