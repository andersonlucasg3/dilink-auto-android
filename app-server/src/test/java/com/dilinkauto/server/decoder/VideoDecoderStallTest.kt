package com.dilinkauto.server.decoder

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验解码器输入缓冲防抖重试与缺口判定策略（issue #125）。
 */
class VideoDecoderStallTest {

    @Test
    fun stallPolicy_maxAttemptsIsThree() {
        assertEquals("最大尝试次数应为 3 次（约 30ms 弹性窗口）", 3, VideoDecoderStallPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun stallPolicy_singleTimeoutDoesNotTriggerGap() {
        assertFalse(
            "单次偶发 10ms 超时不应触发 noteGap，避免瞬间冻结 1~2 秒",
            VideoDecoderStallPolicy.shouldTriggerGap(flags = 0, consecutiveDrops = 1)
        )
    }

    @Test
    fun stallPolicy_consecutiveTimeoutsReachingThresholdTriggersGap() {
        assertTrue(
            "连续超时达到阈值（>=2）时必须触发 noteGap 保证参考链一致性",
            VideoDecoderStallPolicy.shouldTriggerGap(flags = 0, consecutiveDrops = 2)
        )
        assertTrue(
            VideoDecoderStallPolicy.shouldTriggerGap(flags = 0, consecutiveDrops = 3)
        )
    }

    @Test
    fun stallPolicy_configFramesNeverTriggerGap() {
        assertFalse(
            "配置帧超时不应触发普通视频帧的参考链缺口重同步",
            VideoDecoderStallPolicy.shouldTriggerGap(
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                consecutiveDrops = 5
            )
        )
    }

    @Test
    fun stallPolicy_flushThresholdTriggersWhenExceedingTen() {
        assertFalse("10 次以内不执行 flush", VideoDecoderStallPolicy.shouldFlush(10))
        assertTrue("超过 10 次严重卡死必须执行 flush 尝试自愈", VideoDecoderStallPolicy.shouldFlush(11))
    }

    @Test
    fun videoDecoder_resyncAwaitingIdrGatedByPolicy() {
        val resync = ResyncPolicy()
        assertFalse("初始状态不应处于等待 IDR 态", resync.awaitingIdr)
        assertTrue("初始状态正常放行 P 帧", resync.shouldFeed(isKeyFrame = false))

        // 单次抖动：策略判断不触发 gap，resync 状态保持正常
        if (VideoDecoderStallPolicy.shouldTriggerGap(flags = 0, consecutiveDrops = 1)) {
            resync.onGap()
        }
        assertFalse("单次抖动后不应进入 awaitingIdr 状态", resync.awaitingIdr)
        assertTrue("单次抖动后依然放行后续 P 帧，杜绝冻屏", resync.shouldFeed(isKeyFrame = false))

        // 严重连续抖动：达到阈值触发 gap
        if (VideoDecoderStallPolicy.shouldTriggerGap(flags = 0, consecutiveDrops = 2)) {
            resync.onGap()
        }
        assertTrue("连续严重丢帧后应进入 awaitingIdr 状态", resync.awaitingIdr)
        assertFalse("进入重同步后跳过 P 帧", resync.shouldFeed(isKeyFrame = false))
        assertTrue("IDR 关键帧依然放行并退出重同步", resync.shouldFeed(isKeyFrame = true))
        assertFalse("放行关键帧后重同步状态清除", resync.awaitingIdr)
    }
}
