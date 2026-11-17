package com.dilinkauto.server.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #9：参考链断裂必须进入「只放行 IDR」的重同步状态，否则坏参考上的 P 帧
 * 会一路解出马赛克，直到下一个自然 GOP 边界。
 */
class ResyncPolicyTest {

    @Test
    fun `正常态放行所有帧`() {
        val policy = ResyncPolicy()
        assertTrue(policy.shouldFeed(isKeyFrame = false))
        assertTrue(policy.shouldFeed(isKeyFrame = true))
        assertFalse(policy.awaitingIdr)
    }

    @Test
    fun `首次缺口返回 true 且进入重同步`() {
        val policy = ResyncPolicy()
        assertTrue(policy.onGap())
        assertTrue(policy.awaitingIdr)
    }

    @Test
    fun `同一次缺口内重复调用不重复索要 IDR`() {
        val policy = ResyncPolicy()
        assertTrue(policy.onGap())
        // 一次缺口里会连续发生多次丢弃：只有第一次需要向上游索要 IDR。
        assertFalse(policy.onGap())
        assertFalse(policy.onGap())
        assertTrue(policy.awaitingIdr)
    }

    @Test
    fun `重同步期间丢弃 P 帧`() {
        val policy = ResyncPolicy()
        policy.onGap()
        assertFalse(policy.shouldFeed(isKeyFrame = false))
        assertFalse(policy.shouldFeed(isKeyFrame = false))
        // 丢弃不会自行解除重同步
        assertTrue(policy.awaitingIdr)
    }

    @Test
    fun `关键帧放行并解除重同步`() {
        val policy = ResyncPolicy()
        policy.onGap()
        assertFalse(policy.shouldFeed(isKeyFrame = false))
        assertTrue(policy.shouldFeed(isKeyFrame = true))
        assertFalse(policy.awaitingIdr)
        // 恢复后 P 帧重新放行
        assertTrue(policy.shouldFeed(isKeyFrame = false))
    }

    @Test
    fun `解除后可再次进入重同步并再次索要`() {
        val policy = ResyncPolicy()
        assertTrue(policy.onGap())
        policy.shouldFeed(isKeyFrame = true)
        assertTrue(policy.onGap())
        assertTrue(policy.awaitingIdr)
    }

    @Test
    fun `reset 清除重同步`() {
        val policy = ResyncPolicy()
        policy.onGap()
        policy.reset()
        assertFalse(policy.awaitingIdr)
        assertTrue(policy.shouldFeed(isKeyFrame = false))
        // reset 之后的新缺口必须重新索要
        assertTrue(policy.onGap())
    }
}
