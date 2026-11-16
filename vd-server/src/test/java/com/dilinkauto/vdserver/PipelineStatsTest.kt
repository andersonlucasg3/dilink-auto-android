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
                "keepAlive=61 winMs=4000 winFrames=15 winTicks=120 winIdle=112 " +
                "winKeepAlive=8 winTexUpdates=0 winCb=0",
            pipelineStatLine(totals, win, 2560, 1402),
        )
    }

    @Test
    fun `idle skips and frames share the same window denominator`() {
        // 一个窗口内 120 拍：112 拍静止跳过 + 8 拍保活合成 = 120，帧数 15。
        // 「跳过比例」只能拿 winIdle/winTicks 算，不能拿 winIdle/winFrames（那不是比例）。
        val win = PipelineWindow(
            ticks = 120, frames = 15, idleSkips = 112, keepAlives = 8,
            texUpdates = 0, callbacks = 0, windowMs = 4000,
        )
        assertEquals(win.ticks, win.idleSkips + win.keepAlives)
        assertEquals(15L, win.frames)
    }
}
