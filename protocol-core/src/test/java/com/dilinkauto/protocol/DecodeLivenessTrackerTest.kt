package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeLivenessTrackerTest {

    @Test
    fun initialSamplePassesWarmup() {
        val tracker = DecodeLivenessTracker(windowMs = 2000L)
        // 首次采样一帧未出（基线建立阶段）放行
        assertTrue("首次无帧基线应视为活着（预热期）", tracker.isAlive(0L, 1000L))
        // 在窗口内继续无帧依然放行
        assertTrue("窗口内保持无帧依然活着", tracker.isAlive(0L, 2500L))
        // 超过窗口无新帧判死
        assertFalse("超过窗口无帧应判定停摆", tracker.isAlive(0L, 3001L))
    }

    @Test
    fun advancingCountKeepsLiveness() {
        val tracker = DecodeLivenessTracker(windowMs = 2000L)
        assertTrue(tracker.isAlive(1L, 1000L))
        assertTrue(tracker.isAlive(2L, 2000L))
        assertTrue(tracker.isAlive(3L, 3500L))
        assertTrue(tracker.isAlive(4L, 5000L))
    }

    @Test
    fun stalledCountFailsAfterWindowExpires() {
        val tracker = DecodeLivenessTracker(windowMs = 2000L)
        assertTrue(tracker.isAlive(10L, 1000L))
        assertTrue("停顿 1500ms < 2000ms 仍为活着", tracker.isAlive(10L, 2500L))
        assertFalse("停顿 2001ms > 2000ms 判死", tracker.isAlive(10L, 3001L))

        // 重新有帧输出时立即恢复存活
        assertTrue("计数增加立即恢复存活", tracker.isAlive(11L, 3500L))
    }

    @Test
    fun resetClearsBaseline() {
        val tracker = DecodeLivenessTracker(windowMs = 2000L)
        assertTrue(tracker.isAlive(10L, 1000L))
        assertFalse(tracker.isAlive(10L, 3500L))

        tracker.reset()
        // 复位后首帧重新建立基线
        assertTrue("reset 后首帧重新建立基线", tracker.isAlive(10L, 4000L))
        assertTrue("reset 建立基线后窗口内仍活着", tracker.isAlive(10L, 5500L))
        assertFalse("reset 后超过窗口依然停摆则判死", tracker.isAlive(10L, 6001L))
    }
}
