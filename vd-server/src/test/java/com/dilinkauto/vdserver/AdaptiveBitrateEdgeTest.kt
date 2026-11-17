package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdaptiveBitrate 边界补充测试（现存的 AdaptiveBitrateTest 已"深"覆盖主路径）。
 * 聚焦阈值边界、整数截断、sync-frame latch、以及"已在顶不再恢复"这些回归高发点。
 */
class AdaptiveBitrateEdgeTest {

    @Test
    fun `aWriteExactlyAtTheThresholdIsNotBackPressure`() {
        val ab = AdaptiveBitrate(ceiling = 8_000_000, slowWriteMillis = 15L)
        // 恰好 15ms 不满足 > 15ms 的背压判据
        assertFalse(ab.onFrameWritten(15_000_000L))
        assertEquals(8_000_000, ab.currentBitrate)
        assertFalse(ab.needsSyncFrame)
    }

    @Test
    fun `oneMillisecondOverTheThresholdDrops`() {
        val ab = AdaptiveBitrate(ceiling = 8_000_000, slowWriteMillis = 15L)
        assertTrue(ab.onFrameWritten(16_000_000L))
        assertEquals(6_000_000, ab.currentBitrate) // 75% of 8M
        assertTrue("掉帧应立即请求 sync frame", ab.needsSyncFrame)
    }

    @Test
    fun `dropTargetIsIntegerTruncated`() {
        val ab = AdaptiveBitrate(ceiling = 8_000_003)
        assertTrue(ab.onFrameWritten(16_000_000L))
        // (8_000_003 * 0.75f).toInt() == 6_000_002（截断，不是四舍五入）
        assertEquals(6_000_002, ab.currentBitrate)
    }

    @Test
    fun `syncFrameRequestStaysSetAcrossFastFramesUntilApplied`() {
        val ab = AdaptiveBitrate(ceiling = 8_000_000)
        // 先制造一次掉帧 → needsSyncFrame = true
        ab.onFrameWritten(16_000_000L)
        assertTrue(ab.needsSyncFrame)
        // 之后连续快写不应清除该一次性请求（直到调用方 apply）
        ab.onFrameWritten(1_000_000L)
        assertTrue(ab.needsSyncFrame)
        ab.onBitrateApplied()
        assertFalse("apply 后应清除", ab.needsSyncFrame)
    }

    @Test
    fun `recoveryDoesNotFireWhenAlreadyAtTheCeiling`() {
        // cleanInterval=0 让恢复判定立即到点；但已在顶 → 永不上移
        val ab = AdaptiveBitrate(ceiling = 4_000_000, cleanIntervalMillis = 0L)
        repeat(5) { assertFalse(ab.onFrameWritten(1_000_000L)) }
        assertEquals(4_000_000, ab.currentBitrate)
    }

    @Test
    fun `dropNeverGoesBelowTheAdaptiveFloor`() {
        val ab = AdaptiveBitrate(ceiling = 1_600_000)
        // 第一次掉到底 1.5M；继续慢写时 target=1.5M 不再低于 floor
        assertTrue(ab.onFrameWritten(99_000_000L))
        assertEquals(1_500_000, ab.currentBitrate)
        assertFalse(ab.onFrameWritten(99_000_000L))
        assertEquals(1_500_000, ab.currentBitrate)
    }
}
