package com.dilinkauto.vdserver

/**
 * 触控链路自动取证（issue #64）。
 *
 * 实车上「多指偶尔失效 / 某页面顶部点不动」时，旧日志只有在补 DOWN 或注入异常时
 * 才有痕迹，分不清是车机没发、链路丢了、还是手机注入了但坐标落在窗口外。这里把
 * 成功注入也纳入统计，并每 [statsIntervalMs] 汇总一行 `[TouchStats]`：
 *
 *  - `recv` 收到的帧数 vs `down/up/moveBatches` 落地事件数 → 链路是否丢帧
 *  - `multiBatches` 多指批次数量 → 多指手势是否真的到达手机
 *  - `synth` 补发的 DOWN 数量 → 是否发生「缺 DOWN」的链路故障
 *  - `oob` 映射后越界的坐标数 → 是否点击落在 VD 之外（窗口 letterbox 的典型症状）
 *  - `fails` / `rejected` 注入失败与被拒的 pointerId 数量
 *
 * 逐条日志只覆盖低频事件（DOWN/UP、多指批次、越界），其余高频 MOVE 只计数，
 * 避免 20 分钟会话把 vd-server.log 刷爆。
 *
 * 纯逻辑（时间由调用方注入），JVM 单测见 `TouchDiagnosticsTest`。
 */
internal class TouchDiagnostics(
    private val log: (String) -> Unit,
    private val statsIntervalMs: Long = DEFAULT_STATS_INTERVAL_MS,
) {
    var frames = 0L; private set
    var downs = 0L; private set
    var ups = 0L; private set
    var moveBatches = 0L; private set
    var movePointers = 0L; private set
    var multiBatches = 0L; private set
    var synthDowns = 0L; private set
    var outOfBounds = 0L; private set
    var injectFails = 0L; private set
    var rejectedIds = 0L; private set

    private var started = false
    private var lastStatsMs = 0L

    fun noteFrame() { frames++ }

    /** DOWN / UP 逐条记录（低频，直接给坐标）。 */
    fun noteDown(nowMs: Long, ptr: Int, x: Int, y: Int, displayId: Int) {
        downs++
        log("[Touch] DOWN ptr=$ptr ($x,$y) display=$displayId")
        maybeStats(nowMs)
    }

    fun noteUp(nowMs: Long, ptr: Int, x: Int, y: Int, displayId: Int) {
        ups++
        log("[Touch] UP ptr=$ptr ($x,$y) display=$displayId")
        maybeStats(nowMs)
    }

    /**
     * 批量 MOVE 只统计；多指批次按「前 [MULTI_LOG_FIRST] 条 + 每 [MULTI_LOG_EVERY] 条」落日志。
     *
     * @param points 已映射到 VD 像素的 (pointerId, x, y)，用于定位「点在哪、进了没」。
     */
    fun noteMoveBatch(nowMs: Long, points: List<Triple<Int, Int, Int>>) {
        moveBatches++
        movePointers += points.size
        if (points.size >= 2) {
            multiBatches++
            if (multiBatches <= MULTI_LOG_FIRST || multiBatches % MULTI_LOG_EVERY == 0L) {
                val detail = points.joinToString(prefix = "[", postfix = "]") { (id, x, y) -> "$id:($x,$y)" }
                log("[Touch] MOVE n=${points.size} multi#$multiBatches pts=$detail")
            }
        }
        maybeStats(nowMs)
    }

    /** 补发 DOWN（链路丢了按下事件）。 */
    fun noteSynthDown(nowMs: Long, ptr: Int, x: Int, y: Int) {
        synthDowns++
        log("[Touch] SYNTH_DOWN ptr=$ptr ($x,$y)")
        maybeStats(nowMs)
    }

    /** 映射后越界：前 [OOB_LOG_FIRST] 条逐条记，之后每 [OOB_LOG_EVERY] 条记一次。 */
    fun noteOutOfBounds(nowMs: Long, ptr: Int, x: Int, y: Int, width: Int, height: Int) {
        outOfBounds++
        if (outOfBounds <= OOB_LOG_FIRST || outOfBounds % OOB_LOG_EVERY == 0L) {
            log("[Touch] OUT_OF_BOUNDS ptr=$ptr ($x,$y) display=${width}x$height count=$outOfBounds")
        }
        maybeStats(nowMs)
    }

    fun noteInjectFail() { injectFails++ }

    fun noteRejectedId() { rejectedIds++ }

    /** 汇总行；字段名与实车排查命令里的说明保持一致。 */
    fun summary(): String =
        "[TouchStats] recv=$frames down=$downs up=$ups moveBatches=$moveBatches movePts=$movePointers " +
            "multi=$multiBatches synth=$synthDowns oob=$outOfBounds fails=$injectFails rejected=$rejectedIds"

    /**
     * 每 [statsIntervalMs] 落一次汇总。首次调用只建立时间基线，不立刻输出，
     * 避免会话一开始就打一行全 0 的统计。
     */
    fun maybeStats(nowMs: Long) {
        if (!started) {
            started = true
            lastStatsMs = nowMs
            return
        }
        if (nowMs - lastStatsMs < statsIntervalMs) return
        lastStatsMs = nowMs
        log(summary())
    }

    internal companion object {
        /** 10s 一次汇总：与窗口采样同节奏，方便横向对齐。 */
        const val DEFAULT_STATS_INTERVAL_MS = 10_000L

        /** 多指批次前 10 条全记，之后每 50 条记 1 条。 */
        const val MULTI_LOG_FIRST = 10L
        const val MULTI_LOG_EVERY = 50L

        /** 越界坐标前 10 条全记，之后每 50 条记 1 条。 */
        const val OOB_LOG_FIRST = 10L
        const val OOB_LOG_EVERY = 50L
    }
}