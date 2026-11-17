package com.dilinkauto.vdserver

/**
 * 静音期补帧节拍（纯逻辑，单测锁定 —— 见 `AudioPipelineTest` / `AudioPacingTest`）。
 *
 * ## 为什么需要它（#13 R10）
 *
 * `AudioRecord(REMOTE_SUBMIX)` 在**没有任何 active player** 时 `read()` 会永久阻塞
 * 且不产全零帧（E10）。播放端于是持续欠载：实测 3 s 内一次 800 ms 断流就打出
 * `starved=88` / 恢复耗时 706 ms；按节拍补静音帧后 `starved=0` / `underrun=0`。
 *
 * 边界很重要：**只要有 active 的 submix 播放流，即使 `AudioTrack.stop()` 停写，
 * AudioFlinger 仍会持续产零帧** —— 补帧只在「所有 player 消失」时兜底，不是常态路径。
 *
 * ## 为什么要有封顶（#13 R13）
 *
 * 读阻塞很久后（熄屏瞬间、ROM 调度抖动）不该把积压一次性灌进编码器：那会让播放端
 * 队列瞬间膨胀。所以最多连补 [maxCatchUpFrames] 帧，落后更多就直接对齐到当前时刻的
 * 下一拍 —— 宁可让时间轴出现一小段空隙，也不制造延迟尖峰。
 */
internal class AudioPacing(
    private val frameDurationNanos: Long,
    private val maxCatchUpFrames: Int = DEFAULT_MAX_CATCH_UP_FRAMES
) {
    init {
        require(frameDurationNanos > 0) { "frameDurationNanos must be > 0: $frameDurationNanos" }
        require(maxCatchUpFrames > 0) { "maxCatchUpFrames must be > 0: $maxCatchUpFrames" }
    }

    /** 下一拍的到期时刻；[NOT_STARTED] 表示还没有基准（首帧永远由真实数据触发）。 */
    private var nextDueNanos = NOT_STARTED

    /**
     * 真实 PCM 帧产出：把节拍基准推到「现在 + 一个帧间隔」。
     *
     * 采集正常流时每 21.333 ms 调一次，因此 [silenceFramesDue] 始终返回 0 —— 补帧不会
     * 在正常路径上触发。
     */
    fun onRealData(nowNanos: Long) {
        nextDueNanos = nowNanos + frameDurationNanos
    }

    /**
     * 无数据时本轮应当补发几个静音帧（`0..maxCatchUpFrames`）。
     *
     * 首次调用只建立基准并返回 0：不能让管线在还没收到任何真实数据时就抢先补帧。
     */
    fun silenceFramesDue(nowNanos: Long): Int {
        if (nextDueNanos == NOT_STARTED) {
            nextDueNanos = nowNanos + frameDurationNanos
            return 0
        }
        if (nowNanos < nextDueNanos) return 0
        // 已到期的拍数（含当前这一拍）。
        val behind = (nowNanos - nextDueNanos) / frameDurationNanos + 1
        val due = if (behind > maxCatchUpFrames) maxCatchUpFrames else behind.toInt()
        nextDueNanos += due.toLong() * frameDurationNanos
        // 落后超过封顶：不再逐帧追赶，直接对齐到当前时刻之后的第一拍。
        if (nextDueNanos <= nowNanos) nextDueNanos = nowNanos + frameDurationNanos
        return due
    }

    companion object {
        private const val NOT_STARTED = -1L

        /**
         * 最多连补 2 帧（约 43 ms）。超过这个量说明读阻塞了很久，逐帧追赶只会把延迟
         * 堆到播放端队列里（R13），不如对齐。
         */
        const val DEFAULT_MAX_CATCH_UP_FRAMES = 2
    }
}
