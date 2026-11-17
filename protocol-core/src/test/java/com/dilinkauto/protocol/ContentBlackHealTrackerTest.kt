package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContentBlackHealTracker] —— "解码存活但内容黑"的自愈请求节奏（issue #15）。
 *
 * 钉住三件事：首次请求有阈值、之后有限流、码流恢复后能重新起算。
 * 另有一条哨兵回归：时钟从一个很大的值起算时也必须正常工作（仓库里为 `0L`
 * 哨兵踩过坑，见 [BlackScreenDetector] 的注释）。
 */
class ContentBlackHealTrackerTest {

    @Test
    fun `first observation only starts the clock`() {
        val t = ContentBlackHealTracker(firstRequestAfterMs = 6_000, minIntervalMs = 15_000)
        assertFalse(t.onAliveBlackFrame(100_000))
        assertTrue(t.isTracking())
    }

    @Test
    fun `requests once when the black episode reaches the threshold`() {
        val t = ContentBlackHealTracker(firstRequestAfterMs = 6_000, minIntervalMs = 15_000)
        t.onAliveBlackFrame(1_000)
        assertFalse(t.onAliveBlackFrame(6_999))
        assertTrue(t.onAliveBlackFrame(7_000))
    }

    @Test
    fun `throttles repeated requests inside the interval`() {
        val t = ContentBlackHealTracker(firstRequestAfterMs = 6_000, minIntervalMs = 15_000)
        t.onAliveBlackFrame(0)
        assertTrue(t.onAliveBlackFrame(6_000))
        // 阈值已过，但距离上一次请求不足 15s → 一律不发
        assertFalse(t.onAliveBlackFrame(6_001))
        assertFalse(t.onAliveBlackFrame(20_999))
        assertTrue(t.onAliveBlackFrame(21_000))
    }

    @Test
    fun `reset re-arms both the threshold and the throttle`() {
        val t = ContentBlackHealTracker(firstRequestAfterMs = 6_000, minIntervalMs = 15_000)
        t.onAliveBlackFrame(0)
        assertTrue(t.onAliveBlackFrame(6_000))
        t.reset()
        assertFalse(t.isTracking())
        // 重新起算：reset 后的第一帧只记起点
        assertFalse(t.onAliveBlackFrame(6_100))
        assertFalse(t.onAliveBlackFrame(12_099))
        assertTrue(t.onAliveBlackFrame(12_100))
    }

    @Test
    fun `works when the clock starts far from zero`() {
        // elapsedRealtime 的量级是"开机后毫秒数"，动辄上亿；任何 0L 哨兵都会在这里炸。
        val base = 981_188_213L
        val t = ContentBlackHealTracker(firstRequestAfterMs = 6_000, minIntervalMs = 15_000)
        assertFalse(t.onAliveBlackFrame(base))
        assertTrue(t.onAliveBlackFrame(base + 6_000))
    }
}
