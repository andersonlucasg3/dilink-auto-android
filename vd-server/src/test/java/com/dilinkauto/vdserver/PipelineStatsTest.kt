package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住 issue #206 的管线周期指标口径。
 *
 * 期望值全部来自实车日志 `.tmp/run-1116/vd-server.log`（2026-11-16）：
 * - `:187`  `966 frames ... ticks=1440 mpixPerSec=15.9 idleSkip=512`（20fps 会话）
 * - `:1643` `131 frames ... ticks=960 mpixPerSec=107.7 idleSkip=883 keepAlive=61`（30fps 会话）
 * 后者帧数只有前者 13%，`mpixPerSec` 却是 6.8 倍 —— 旧口径是循环外按**目标 fps** 算的
 * 常量（`GlPipeline.kt:185-188`），与本次窗口实际发了多少帧无关。
 *
 * 修法：`mpixPerSec` 改为「本窗口实际发送帧数 × 实际合成分辨率 ÷ 窗口秒数」，
 * 并把窗口长度 `winMs=` 与各项窗口增量一起打出来。期望串逐字写出，不由被测函数反推。
 */
class PipelineStatsTest {

    @Test
    fun `window throughput uses actual frames over the measured window`() {
        // 实车 :1643 的最后一个窗口：4s 只发 15 帧，VD 2560x1402
        // → 15 × 3.58912MPix / 4s = 13.4592 MPix/s（旧口径给 107.7）
        assertEquals(13.4592, pipelineMpixPerSec(15, 2560, 1402, 4000), 0.000001)
    }

    @Test
    fun `window throughput scales with the real frame rate`() {
        // 满速 30fps 4s 窗口 = 120 帧 → 107.6736（旧理论值只在满速时碰巧对）
        assertEquals(107.6736, pipelineMpixPerSec(120, 2560, 1402, 4000), 0.000001)
        // 半速 60 帧 → 一半
        assertEquals(53.8368, pipelineMpixPerSec(60, 2560, 1402, 4000), 0.000001)
    }

    @Test
    fun `window throughput is zero when nothing was sent`() {
        assertEquals(0.0, pipelineMpixPerSec(0, 2560, 1402, 4000), 0.000001)
    }

    @Test
    fun `stat line reports window deltas next to cumulative totals`() {
        // 累计值取实车 :1643；窗口增量是同一会话最后一个 4s 窗口（120 拍 / 15 帧 / 112 跳过）。
        val totals = PipelineSnapshot(
            frames = 131, bitrateMbps = 2, keys = 2, texUpdates = 16, callbacks = 16,
            swapFails = 0, queued = 0, sendDrops = 0, maxWriteMs = 2,
            timestamp = 593149264826592, glError = 0, ticks = 960,
            idleSkips = 883, keepAlives = 61,
        )
        val win = PipelineWindow(
            ticks = 120, frames = 15, idleSkips = 112, keepAlives = 8,
            texUpdates = 0, callbacks = 0, windowMs = 4000,
        )
        // tex/cb 冻结（winTexUpdates=0 / winCb=0）必须显式可见：累计值停在 16 并不等于
        // 「纹理计数坏了」，而是这个窗口确实没有新帧到达（同窗口 ts= 也不变）。
        assertEquals(
            "Pipeline: 131 frames 2Mbps keys=2 tex=16 cb=16 swapFails=0 q=0 sendDrops=0 " +
                "maxW=2ms ts=593149264826592 glErr=0 ticks=960 mpixPerSec=13.5 idleSkip=883 " +
                "keepAlive=61 effFps=3.8 newFps=0.0 winMs=4000 winFrames=15 winTicks=120 winIdle=112 " +
                "winKeepAlive=8 winTexUpdates=0 winCb=0",
            pipelineStatLine(totals, win, 2560, 1402),
        )
    }

    // ── issue #203：实际生效帧率（墙钟口径，与 #206 的 mpixPerSec 同一套） ──

    @Test
    fun `fps is frames over the measured wall clock window`() {
        // 实车 30fps 会话最后的窗口：4s 只发 15 帧 → 3.8fps（旧口径按拍数反推会给 3.75，
        // 两者接近；但慢机 tick 变短时按拍反推会高估，墙钟口径不会）。
        assertEquals(3.75, pipelineFps(15, 4000), 1e-9)
        // 满速 30fps：4s 120 帧 → 30.0；20fps：6s 120 帧 → 20.0
        assertEquals(30.0, pipelineFps(120, 4000), 1e-9)
        assertEquals(20.0, pipelineFps(120, 6000), 1e-9)
    }

    @Test
    fun `fps of an empty or zero-length window is zero`() {
        assertEquals(0.0, pipelineFps(0, 4000), 0.0)
        assertEquals(0.0, pipelineFps(15, 0), 0.0)
        assertEquals(0.0, pipelineFps(15, -1), 0.0)
    }

    @Test
    fun `fps text is locale independent`() {
        assertEquals("17.2", pipelineFpsText(17.249))
        assertEquals("30.0", pipelineFpsText(30.0))
    }

    @Test
    fun `window tracker advances every baseline including the wall clock`() {
        var nowNanos = 0L
        val tracker = PipelineWindowTracker({ nowNanos })

        nowNanos = 4_000_000_000L
        val first = tracker.snapshot(PipelineCounters(120, 15, 112, 8, 3, 20))
        assertEquals(4000L, first.windowMs)
        assertEquals(120L, first.ticks)

        nowNanos = 10_000_000_000L
        val second = tracker.snapshot(PipelineCounters(240, 30, 224, 16, 6, 40))
        // 若复位漏掉 `lastNanos = nowNanos`，第二个窗口会变成 10000ms（跨两个窗口）；
        // 漏掉任一计数基线同理会让对应增量翻倍。
        assertEquals(6000L, second.windowMs)
        assertEquals(120L, second.ticks)
        assertEquals(15L, second.frames)
        assertEquals(112L, second.idleSkips)
        assertEquals(8L, second.keepAlives)
        assertEquals(3L, second.texUpdates)
        assertEquals(20L, second.callbacks)
    }

    @Test
    fun `window tracker keeps the pre-loop callback baseline`() {
        // 监听器在 pipelineLoop 之前就注册了，第一窗口不应把之前的回调算成自己的增量。
        var nowNanos = 0L
        val tracker = PipelineWindowTracker({ nowNanos }, PipelineCounters(0, 0, 0, 0, 0, 16))
        nowNanos = 4_000_000_000L
        val win = tracker.snapshot(PipelineCounters(120, 15, 112, 8, 0, 16))
        assertEquals(0L, win.callbacks)
        assertEquals(0L, win.texUpdates)
    }
}
