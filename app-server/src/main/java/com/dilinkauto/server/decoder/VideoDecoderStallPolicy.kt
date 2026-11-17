package com.dilinkauto.server.decoder

import android.media.MediaCodec

/**
 * 解码器输入缓冲等待与防抖策略（issue #125）。
 *
 * 背景：车机硬件解码器偶发调度抖动，`dequeueInputBuffer(10ms)` 偶发超时。
 * 旧实现单次 10ms 超时便立刻丢弃该帧并调用 `noteGap()`，导致后续所有 P 帧全部被
 * 判定为参考链断裂而跳过，画面瞬间冻结 1~2 秒直到下一个 IDR 到达。
 *
 * 本策略定义重试次数与丢弃判定：
 *  - 允许在放弃前进行最多 [MAX_ATTEMPTS] 次 `dequeueInputBuffer` 尝试（每次 10ms，共 30ms 弹性窗口）；
 *  - 每次重试前应先触发 `drainOutput`，给解码器归还已渲染输出缓冲的机会；
 *  - 只有在重试耗尽且确实无法喂入时，才确认发生丢帧；连续丢帧达到 [GAP_THRESHOLD] 时
 *    才触发 `noteGap()` 重同步，避免偶发 10ms 抖动误判参考链断裂。
 */
internal object VideoDecoderStallPolicy {

    /** 单帧输入缓冲的最大尝试次数（每次 10ms，共 30ms 弹性窗口）。 */
    const val MAX_ATTEMPTS = 3

    /** 触发 noteGap 重同步所需的最小连续丢帧数。 */
    const val GAP_THRESHOLD = 2

    /**
     * 判断在当前连续丢帧计数下，是否应当调用 noteGap 触发参考链重同步。
     *
     * @param flags 帧标记（0 为普通视频帧，[MediaCodec.BUFFER_FLAG_CODEC_CONFIG] 为配置帧）
     * @param consecutiveDrops 当前连续丢帧数（已累加本次）
     */
    fun shouldTriggerGap(flags: Int, consecutiveDrops: Int): Boolean =
        flags == 0 && consecutiveDrops >= GAP_THRESHOLD

    /**
     * 判断是否需要因为严重卡死执行 flush 恢复。
     */
    fun shouldFlush(consecutiveDrops: Int): Boolean =
        consecutiveDrops > 10
}
