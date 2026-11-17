package com.dilinkauto.desktop.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HardwareDecodeWatchdog 单测。
 *
 * 判据是"开始了但一帧都不出"，真实触发需要一台硬解不可用的机器，
 * 所以这里注入可变时钟，把"超时"这件事变成确定性的断言。
 */
class HardwareDecodeWatchdogTest {

    private var now = 1_000L

    private fun watchdog(timeoutMs: Long = 3_000L) = HardwareDecodeWatchdog(timeoutMs) { now }

    @Test
    fun `未开始码流时喂帧不判定`() {
        val w = watchdog()
        now += 10_000
        assertFalse(w.onFrame(0))
    }

    @Test
    fun `超时前零帧不回退`() {
        val w = watchdog()
        w.onStreamStarted()
        now += 2_999
        assertFalse(w.onFrame(0))
    }

    @Test
    fun `超时且零帧触发回退且只触发一次`() {
        val w = watchdog()
        w.onStreamStarted()
        now += 3_000
        assertTrue(w.onFrame(0))
        assertFalse(w.onFrame(0)) // 同一次判定只报一次
        assertFalse(w.onFrame(0))
    }

    @Test
    fun `出过画面后判定为可用，之后超时也不再回退`() {
        val w = watchdog()
        w.onStreamStarted()
        assertFalse(w.onFrame(1))
        now += 100_000
        assertFalse(w.onFrame(0))
    }

    @Test
    fun `重建会重新计时`() {
        val w = watchdog()
        w.onStreamStarted()
        now += 2_500
        w.onStreamStarted() // 解码器重建：时间窗口重来
        now += 2_500         // 距上次开始 2.5s，未超时
        assertFalse(w.onFrame(0))
        now += 500           // 累计 3s
        assertTrue(w.onFrame(0))
    }

    @Test
    fun `reset 后可对新的硬解尝试重新判定`() {
        val w = watchdog()
        w.onStreamStarted()
        now += 3_000
        assertTrue(w.onFrame(0))

        w.reset()
        assertFalse(w.onFrame(0)) // 还没 onStreamStarted，不判定

        w.onStreamStarted()
        now += 3_000
        assertTrue(w.onFrame(0))
    }

    @Test
    fun `已判定为可用后 onStreamStarted 不会解除锁定`() {
        val w = watchdog()
        w.onStreamStarted()
        assertFalse(w.onFrame(3))
        now += 100_000
        w.onStreamStarted() // 已 resolved，忽略
        now += 100_000
        assertFalse(w.onFrame(0))
    }
}
