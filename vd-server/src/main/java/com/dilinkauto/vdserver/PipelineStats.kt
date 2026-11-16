package com.dilinkauto.vdserver

import java.util.Locale

/**
 * 管线周期日志的指标口径（issue #206）。
 *
 * 背景：实车 `vd-server.log:187` 与 `:1643` 出自同一轮测试的两个会话 ——
 * ```
 * [Pipeline] Pipeline: 966 frames ... ticks=1440 mpixPerSec=15.9 idleSkip=512   (20fps)
 * [Pipeline] Pipeline: 131 frames ... ticks=960  mpixPerSec=107.7 idleSkip=883  (30fps)
 * ```
 * 帧数掉到 13%，吞吐反而涨 6.8 倍。原因有三：
 * 1. `mpixPerSec` 在循环外按**目标 fps** 算常量（旧 `GlPipeline.kt:185-188` 调
 *    `VdDimensions.mpixPerSec`），与实际发送帧无关；
 * 2. `idleSkip` 与 `frames` 都是会话累计值，但分母不同（拍数 vs 编码帧数），
 *    两行相减会得到「跳过 371 拍却只少 835 帧」这种无法解释的组合；
 * 3. `tex=` / `cb=` 是累计增量，静止时正常冻结（`:1643` 的 `ts=` 同样冻结），
 *    但日志里看不出「本窗口 0 增量」还是「计数器坏了」。
 *
 * 这里把「本窗口」的增量单独算出来并随行打印，`mpixPerSec` 改成
 * `winFrames × VD 面积 ÷ 窗口秒数`。累计字段原样保留 —— e2e 脚本按
 * `Pipeline: N frames` 与 `tex=` 取值（`scripts/lib/asserts.sh`、S13/S25），
 * 前缀与字段名不能漂移。
 */
internal data class PipelineWindow(
    /** 本窗口的帧循环拍数。 */
    val ticks: Long,
    /** 本窗口真正交给发送器的编码帧数（与累计 `frames=` 同口径）。 */
    val frames: Long,
    /** 本窗口因静止短路整段跳过的拍数。 */
    val idleSkips: Long,
    /** 本窗口的保活合成次数（无新帧时用旧纹理重画）。 */
    val keepAlives: Long,
    /** 本窗口 `updateTexImage()` 次数（= 消费到的新纹理数）。 */
    val texUpdates: Long,
    /** 本窗口 SurfaceTexture 的 onFrameAvailable 回调次数。 */
    val callbacks: Long,
    /** 窗口长度（毫秒，墙钟差）。 */
    val windowMs: Long,
)

/** 一条 `Pipeline:` 统计行的累计字段（与窗口增量分开，便于单测逐字段钉住）。 */
internal data class PipelineSnapshot(
    val frames: Long,
    val bitrateMbps: Int,
    val keys: Long,
    val texUpdates: Long,
    val callbacks: Long,
    val swapFails: Long,
    val queued: Int,
    val sendDrops: Long,
    val maxWriteMs: Long,
    val timestamp: Long,
    val glError: Int,
    val ticks: Long,
    val idleSkips: Long,
    val keepAlives: Long,
)

/**
 * 本窗口实际合成吞吐（MPix/s）= 实际发送帧数 × 实际合成分辨率 ÷ 窗口秒数。
 *
 * 分母用 VD 尺寸（`displayWidth × displayHeight`）而不是编码尺寸：面积成本发生在
 * SurfaceFlinger 合成那一步。窗口长度由墙钟差给出，**不假设「120 拍 = 4s」** ——
 * 20fps / 30fps / 60fps 下同样 120 拍对应不同秒数。
 *
 * @param frames 本窗口实际交给发送器的帧数（[PipelineWindow.frames]）。
 * @param windowMs 本窗口墙钟长度；<= 0 时返回 0，避免日志里出现 `Infinity`。
 */
internal fun pipelineMpixPerSec(frames: Long, width: Int, height: Int, windowMs: Long): Double {
    if (windowMs <= 0L) return 0.0
    return frames.toDouble() * width.toLong() * height * 1000.0 / windowMs / 1_000_000.0
}

/** 组装 `Pipeline:` 周期行；新增字段一律带 `win` 前缀，避免与累计字段混淆。 */
internal fun pipelineStatLine(
    totals: PipelineSnapshot,
    win: PipelineWindow,
    width: Int,
    height: Int,
): String {
    val mpix = String.format(
        Locale.US, "%.1f",
        pipelineMpixPerSec(win.frames, width, height, win.windowMs)
    )
    return "Pipeline: ${totals.frames} frames ${totals.bitrateMbps}Mbps keys=${totals.keys} " +
        "tex=${totals.texUpdates} cb=${totals.callbacks} swapFails=${totals.swapFails} " +
        "q=${totals.queued} sendDrops=${totals.sendDrops} maxW=${totals.maxWriteMs}ms " +
        "ts=${totals.timestamp} glErr=${totals.glError} ticks=${totals.ticks} " +
        "mpixPerSec=$mpix idleSkip=${totals.idleSkips} keepAlive=${totals.keepAlives} " +
        "winMs=${win.windowMs} winFrames=${win.frames} winTicks=${win.ticks} " +
        "winIdle=${win.idleSkips} winKeepAlive=${win.keepAlives} " +
        "winTexUpdates=${win.texUpdates} winCb=${win.callbacks}"
}
