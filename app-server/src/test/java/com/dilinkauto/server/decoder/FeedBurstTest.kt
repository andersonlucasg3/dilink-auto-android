package com.dilinkauto.server.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #55：喂帧线程必须能一轮排空突发，不能再退化成「每帧间隔只喂 1 帧」。
 */
class FeedBurstTest {

    @Test
    fun `队列有帧且未达上限时继续排空`() {
        assertTrue(FeedBurst.shouldContinue(fedThisRound = 0, hasQueuedFrame = true))
        assertTrue(
            FeedBurst.shouldContinue(
                fedThisRound = FeedBurst.MAX_FRAMES_PER_ROUND - 1,
                hasQueuedFrame = true
            )
        )
    }

    @Test
    fun `队列为空立即停止本轮`() {
        assertFalse(FeedBurst.shouldContinue(fedThisRound = 1, hasQueuedFrame = false))
    }

    @Test
    fun `达到单轮上限后停止本轮`() {
        assertFalse(
            FeedBurst.shouldContinue(
                fedThisRound = FeedBurst.MAX_FRAMES_PER_ROUND,
                hasQueuedFrame = true
            )
        )
        assertFalse(
            FeedBurst.shouldContinue(
                fedThisRound = FeedBurst.MAX_FRAMES_PER_ROUND + 1,
                hasQueuedFrame = true
            )
        )
    }

    @Test
    fun `单轮上限必须大于 1`() {
        // 若退化成 1，就回到了 issue #55 里 30fps 硬限速的老问题。
        assertTrue(FeedBurst.MAX_FRAMES_PER_ROUND > 1)
    }
}
