package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码输入队列容量守护（issue #163）。
 *
 * 现场：队列只有 4 槽，而喂帧线程单轮最多连喂 [FeedBurst.MAX_FRAMES_PER_ROUND] = 8 帧。
 * 一次关键帧突发就必然 `offer` 失败 → `noteGap()` → 断链重同步，8 分钟出现 9 次，
 * 最长冻结 2894ms（跳过 87 个 P 帧）。容量扩到 8 后，缓冲深度与消费能力对齐。
 *
 * 这里同时钉住「不得回退到 4」与 issue 要求的 `>= MAX_FRAMES_PER_ROUND / 2` 底线。
 */
class VideoDecoderQueueCapacityTest {

    @Test
    fun `frame queue capacity is eight slots`() {
        assertEquals(
            "解码输入队列必须是 8 槽（issue #163）；回退到 4 会让突发必然丢帧",
            8,
            VideoDecoder.FRAME_QUEUE_CAPACITY,
        )
    }

    @Test
    fun `frame queue capacity covers one full feed burst`() {
        assertTrue(
            "队列容量必须 >= 单轮最大喂帧数 ${FeedBurst.MAX_FRAMES_PER_ROUND}，否则突发必然丢帧",
            VideoDecoder.FRAME_QUEUE_CAPACITY >= FeedBurst.MAX_FRAMES_PER_ROUND,
        )
        // issue #163 要求的守护线，防止容量又被改回 4。
        assertTrue(
            "队列容量必须 >= FeedBurst.MAX_FRAMES_PER_ROUND / 2",
            VideoDecoder.FRAME_QUEUE_CAPACITY >= FeedBurst.MAX_FRAMES_PER_ROUND / 2,
        )
    }
}
