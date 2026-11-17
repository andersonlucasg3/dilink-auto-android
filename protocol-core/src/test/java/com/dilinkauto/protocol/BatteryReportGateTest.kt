package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量上报节流（issue #151，[BatteryReportGate]）。
 *
 * 覆盖四条用户可见的时序规则：
 * - 首次采样必定上报；
 * - 1% 变化过了防抖窗口就发；
 * - 1% 在窗口内来回抖动不发（不刷屏）；
 * - 电量不变时 60s 兜底发一次；
 * - 2% 跳变、charging 翻转不受防抖限制。
 */
class BatteryReportGateTest {

    private val gate = BatteryReportGate()

    @Test
    fun `首次采样必定发送`() {
        assertTrue(gate.shouldSend(level = 86, charging = false, nowMs = 0))
    }

    @Test
    fun `1 百分比变化过了防抖窗口立即发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 86, charging = false, nowMs = BatteryReportGate.CHANGE_DEBOUNCE_MS))
    }

    @Test
    fun `连续 1 百分比抖动不刷屏`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        // 1s 后掉到 84：变化只有 1% 且在 5s 防抖窗口内 → 不发
        assertFalse(gate.shouldSend(level = 84, charging = false, nowMs = 1_000))
        // 2s 后回到 85：仍被防抖挡住
        assertFalse(gate.shouldSend(level = 85, charging = false, nowMs = 2_000))
        // 5s 窗口过后同一变化才放行
        assertTrue(gate.shouldSend(level = 84, charging = false, nowMs = 5_000))
    }

    @Test
    fun `2 百分比跳变不受防抖限制`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 83, charging = false, nowMs = 500))
    }

    @Test
    fun `charging 翻转立即发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 85, charging = true, nowMs = 100))
        gate.markSent(level = 85, charging = true, nowMs = 100)
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 200))
    }

    @Test
    fun `电量不变时 60 秒兜底发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        // 59.9s：没变化，不发
        assertFalse(gate.shouldSend(level = 85, charging = false, nowMs = 59_900))
        // 60s：兜底心跳
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 60_000))
    }

    @Test
    fun `markSent 之后同样的值不再重复发送`() {
        gate.markSent(level = 85, charging = true, nowMs = 0)
        assertFalse(gate.shouldSend(level = 85, charging = true, nowMs = 1_000))
        gate.markSent(level = 85, charging = true, nowMs = 1_000)
        assertFalse(gate.shouldSend(level = 85, charging = true, nowMs = 2_000))
    }

    @Test
    fun `reset 后重新回到首次采样语义`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        gate.reset()
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 1))
    }
}
