package com.dilinkauto.client.service

/**
 * 「网络事件 → 是否重建投屏通道」的纯判据（issue #200）。
 *
 * 现场（`.tmp/run-1116/client.log`）：Wi-Fi 重关联时 `onAvailable` 在 `STREAMING` 状态
 * 下**没有任何分支**，视频链路于是静默了 5983ms 才自己恢复。
 *
 * 但「收到 available 就重建」同样是错的，两条硬证据：
 *  1. 全量重建实测 3.7~4.9s（`cleanupSession` → `VD server lifecycle connected`）；
 *  2. 断链期间连握手都完不成 —— 在 available 报到的瞬间拆会话，等于把 6s 冻屏
 *     拉长到「链路恢复 + 重建」的 8~10s。
 *
 * 所以只在「链路已经可用、控制通道却仍在静默」时才重建：心跳 3s 一拍
 * （`ConnectionKeepAlive.DEFAULT_HEARTBEAT_INTERVAL_MS`），静默 ≥6s 说明连续漏掉两拍，
 * 这个 socket 已经半死（换网段后源地址变了，TCP 再也收不到对端），只能重建。
 * 冷却窗口用来吸收重关联时连续的 `onAvailable` 回调。
 */
class NetworkRebuildPolicy(
    /** 控制通道静默达到该值才认为连接已半死。 */
    private val deadLinkSilenceMs: Long = DEAD_LINK_SILENCE_MS,
    /** 两次主动重建之间的最小间隔。 */
    private val cooldownMs: Long = REBUILD_COOLDOWN_MS,
) {

    enum class Action { NONE, REBUILD }

    private var lastRebuildMs = 0L

    /**
     * @param nowMs **单调时钟**（手机端 `SystemClock.elapsedRealtime()`，与车机侧一致）——
     *   策略只做两次调用之间的差值比较，用墙钟会在 NTP 跳变时把冷却窗口算错
     * @param streaming 当前是否处于 `CONNECTED/STREAMING`（`WAITING` 由监听循环自己重启）
     * @param controlSilenceMs 控制通道距上次收到帧的毫秒数；-1 表示「无法判断」
     *   （没有控制连接，或还没收到过任何帧）
     */
    @Synchronized
    fun onNetworkAvailable(nowMs: Long, streaming: Boolean, controlSilenceMs: Long): Action {
        if (!streaming) return Action.NONE
        // 没有连接可拆，或链路还在正常收帧（重关联抖动）——都不动。
        if (controlSilenceMs < 0 || controlSilenceMs < deadLinkSilenceMs) return Action.NONE
        if (lastRebuildMs != 0L && nowMs - lastRebuildMs < cooldownMs) return Action.NONE
        lastRebuildMs = nowMs
        return Action.REBUILD
    }

    /** 用户主动停止/重启服务时复位冷却（不算一次重建）。 */
    @Synchronized
    fun reset() {
        lastRebuildMs = 0L
    }

    companion object {
        /** 6s = 连续漏掉两拍 3s 心跳。 */
        const val DEAD_LINK_SILENCE_MS = 6_000L

        /** 30s：重关联时 `onAvailable` 可能连续回调，避免拆完再拆。 */
        const val REBUILD_COOLDOWN_MS = 30_000L
    }
}
