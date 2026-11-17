package com.dilinkauto.server.decoder

/**
 * 视频停顿分级（issue #200）。
 *
 * 背景：现场一次 Wi-Fi 重关联把视频链路静默了 5983ms，但车机端能看到的唯一痕迹是
 * **恢复那一帧**才打印的 `STALL 5983ms` —— [RenderStallDetector] 只在「渲染推进」时才
 * 出报告（没有新帧就 return null）。而两端唯一的时间兜底是 3s 心跳 / 10s 看门狗
 * （`ConnectionKeepAlive`），6s 空档落在两者之间，于是整段停顿里没有任何可执行动作：
 * 车机端不预置重同步、不索要 IDR，等链路自己恢复后又先吃一轮积压洪峰
 * （实测 150ms 内涌入 ~117 帧，8 槽队列溢出 → 88 次 drops → 二次断链 → 第二个 IDR）。
 *
 * 本类把「多久没出帧」做成可按 tick 观察的分级判据（纯逻辑、时间由调用方注入）：
 *  - [Level.GAP]（默认 1.5s）：链路开始可疑，上层应打一行 W 并预置重同步
 *    （`noteGap()`，IDR 请求仍走 `SyncRequestGate` 限流）；
 *  - [Level.STALLED]（默认 3s）：确认停摆，打一行带网络/队列上下文的 W。
 *
 * 每级在**同一次停顿里只报一次**，渲染推进即复位基线 —— 不会因为 tick 密集而刷屏。
 * 注意这里不负责重建：重建归黑屏闸门 / `DecodeLiveness`（issue #11）管，避免两个
 * 触发源互相抢方向盘。
 */
class StallEscalationPolicy(
    /** 第一级告警：这么久没有渲染推进就认为链路可疑。 */
    private val gapWarnMs: Long = DEFAULT_GAP_WARN_MS,
    /** 第二级告警：这么久没有渲染推进就判定停摆。 */
    private val stalledMs: Long = DEFAULT_STALLED_MS,
) {

    enum class Level { GAP, STALLED }

    /** 一次需要上层记录（并可能动作）的分级报告。 */
    data class Report(val level: Level, val gapMs: Long)

    private var lastAdvanceMs = 0L
    private var lastRenders = 0L

    /**
     * 是否已经建立了基线。
     *
     * 不能拿 `lastAdvanceMs == 0L` 当哨兵：注入的时钟可以从 0 起算（单测、或设备刚开机
     * 时 `elapsedRealtime()` 也很小），那样第二个 tick 会被误当成「首个 tick」而反复重置基线。
     */
    private var hasBaseline = false
    private var gapReported = false
    private var stalledReported = false

    /**
     * 喂帧线程每轮调用一次。
     *
     * @param nowMs 单调时钟（车机端为 `SystemClock.elapsedRealtime()`）
     * @param renders 累计渲染帧数（只有增长才算「有推进」）
     * @return 本次需要上报的级别；无新推进且未跨阈值、或本级已报过时返回 null
     */
    @Synchronized
    fun onTick(nowMs: Long, renders: Long): Report? {
        // 首个 tick 只建基线：否则会把「上一会话结束到本会话开始」的空档算进来。
        if (!hasBaseline) {
            hasBaseline = true
            lastAdvanceMs = nowMs
            lastRenders = renders
            return null
        }
        if (renders > lastRenders) {
            // 有推进：基线前移，两级告警都重新武装。
            lastRenders = renders
            lastAdvanceMs = nowMs
            gapReported = false
            stalledReported = false
            return null
        }
        val gap = nowMs - lastAdvanceMs
        // 先 GAP 再 STALLED：即使 tick 稀疏到一次跨过两个阈值，顺序也不会颠倒。
        if (!gapReported && gap >= gapWarnMs) {
            gapReported = true
            return Report(Level.GAP, gap)
        }
        if (!stalledReported && gap >= stalledMs) {
            stalledReported = true
            return Report(Level.STALLED, gap)
        }
        return null
    }

    /** 会话边界（`start()`）重新起算。 */
    @Synchronized
    fun reset() {
        hasBaseline = false
        lastAdvanceMs = 0L
        lastRenders = 0L
        gapReported = false
        stalledReported = false
    }

    companion object {
        /** 1.5s：用户可感知卡顿的量级，也是「链路断」与「偶发调度抖动」的分界。 */
        const val DEFAULT_GAP_WARN_MS = 1_500L

        /** 3.0s：连续漏掉一拍 3s 心跳，可判定停摆（但仍短于 10s 心跳看门狗）。 */
        const val DEFAULT_STALLED_MS = 3_000L
    }
}
