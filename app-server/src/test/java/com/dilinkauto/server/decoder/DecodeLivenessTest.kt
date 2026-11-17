package com.dilinkauto.server.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #11：黑屏自愈的升级闸门必须能区分「内容黑但仍在解码」与「解码停摆」。
 */
class DecodeLivenessTest {

    @Test
    fun `首次采样按活着处理`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        // 会话刚开始、一帧都还没解出来：预热期不该判停摆。
        assertTrue(liveness.isAlive(count = 0, nowMs = 10_000))
    }

    @Test
    fun `出帧计数增长即活着`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        assertTrue(liveness.isAlive(count = 0, nowMs = 10_000))
        assertTrue(liveness.isAlive(count = 30, nowMs = 11_000))
        assertTrue(liveness.isAlive(count = 60, nowMs = 12_000))
    }

    @Test
    fun `窗口内没有新帧仍算活着`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        assertTrue(liveness.isAlive(count = 100, nowMs = 10_000))
        // 1.9s 没有新帧：帧率抖动，不是停摆。
        assertTrue(liveness.isAlive(count = 100, nowMs = 11_900))
    }

    @Test
    fun `超过窗口没有新帧即停摆`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        assertTrue(liveness.isAlive(count = 100, nowMs = 10_000))
        assertFalse(liveness.isAlive(count = 100, nowMs = 12_001))
    }

    @Test
    fun `停摆后恢复出帧重新判活`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        assertTrue(liveness.isAlive(count = 100, nowMs = 10_000))
        assertFalse(liveness.isAlive(count = 100, nowMs = 12_500))
        assertTrue(liveness.isAlive(count = 101, nowMs = 12_600))
        // 新的基线从这里起算
        assertTrue(liveness.isAlive(count = 101, nowMs = 14_000))
    }

    @Test
    fun `reset 后重新建立基线`() {
        val liveness = DecodeLiveness(windowMs = 2_000)
        assertTrue(liveness.isAlive(count = 100, nowMs = 10_000))
        assertFalse(liveness.isAlive(count = 100, nowMs = 13_000))
        liveness.reset()
        // 新会话：旧的「停摆」结论不能带过来。
        assertTrue(liveness.isAlive(count = 0, nowMs = 20_000))
        assertTrue(liveness.isAlive(count = 0, nowMs = 21_000))
        assertFalse(liveness.isAlive(count = 0, nowMs = 23_001))
    }
}
