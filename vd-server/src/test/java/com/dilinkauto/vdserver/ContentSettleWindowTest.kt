package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 C：`ensureContent` 的沉降窗口（注入假时钟，不真睡）。
 *
 * 现场 `vd-server.log:1109`：建屏后立刻 `has no visible window`，而几百毫秒后任务
 * 就能被拉回来 —— 这段"暂时没有可见窗口"是新建 display 的正常过渡态。
 */
class ContentSettleWindowTest {

    /** 假时钟：sleep 只推进时间，不真睡。 */
    private class FakeClock {
        var nowNanos = 0L
        val sleeps = mutableListOf<Long>()

        fun nanoTime(): Long = nowNanos
        fun sleep(ms: Long) {
            sleeps += ms
            nowNanos += ms * 1_000_000L
        }
    }

    @Test
    fun `content that appears inside the window needs no restore`() {
        val clock = FakeClock()
        var probes = 0
        val window = ContentSettleWindow(
            settleMs = 500,
            pollMs = 100,
            nanoTime = clock::nanoTime,
            sleep = clock::sleep
        )
        // 第一次探测（调用方已探过一次，这里窗口内再探）不可见，第 3 次可见。
        val visible = window.awaitContent {
            probes++
            probes >= 3
        }
        assertTrue(visible)
        assertEquals(2, clock.sleeps.size)
        assertEquals(200L, clock.nowNanos / 1_000_000L)
    }

    @Test
    fun `content that never appears exhausts exactly the settle window`() {
        val clock = FakeClock()
        var probes = 0
        val window = ContentSettleWindow(
            settleMs = 500,
            pollMs = 100,
            nanoTime = clock::nanoTime,
            sleep = clock::sleep
        )
        assertFalse(window.awaitContent { probes++; false })
        // 500ms / 100ms = 5 次 sleep，第 6 次探测前窗口已尽（命中 deadline 直接返回）。
        assertEquals(5, clock.sleeps.size)
        assertEquals(500L, clock.nowNanos / 1_000_000L)
        assertEquals(6, probes)
    }

    @Test
    fun `zero window keeps the old immediate behaviour`() {
        val clock = FakeClock()
        val window = ContentSettleWindow(
            settleMs = 0,
            pollMs = 100,
            nanoTime = clock::nanoTime,
            sleep = clock::sleep
        )
        assertFalse(window.awaitContent { true })
        assertTrue("窗口为 0 时不该探测/等待", clock.sleeps.isEmpty())
        assertEquals(0L, clock.nowNanos)
    }

    @Test
    fun `probe already visible returns immediately`() {
        val clock = FakeClock()
        val window = ContentSettleWindow(
            settleMs = 500,
            pollMs = 100,
            nanoTime = clock::nanoTime,
            sleep = clock::sleep
        )
        assertTrue(window.awaitContent { true })
        assertTrue(clock.sleeps.isEmpty())
    }
}
