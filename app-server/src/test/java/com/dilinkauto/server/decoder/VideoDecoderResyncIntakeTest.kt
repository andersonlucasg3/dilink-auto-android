package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重同步期「接收入口丢 P 帧」的行为级用例（issue #200 评审）。
 *
 * 背景：现场链路恢复后 150ms 内涌入 ~117 帧积压，8 槽队列被注定作废的 P 帧冲垮
 * （`drops=88` → 第二次「参考链断裂」→ 第二个 IDR）。修复在 `onFrameReceived` 的
 * 入口丢弃这些帧，本测试直接驱动真实的 [VideoDecoder]（不碰 MediaCodec：
 * `running` 手动置位、`logSink` 吞掉日志），断言「丢」与「不丢」两侧。
 *
 * 只钉两件事，不复制实现细节：
 *  1. `awaitingIdr` 期间到达的非 config / 非 key 帧不得入队（队列长度不增长）；
 *  2. IDR 不会被误丢，且它被喂入后（`ResyncPolicy.shouldFeed` 清除状态）紧接着的
 *     P 帧必须正常入队 —— 不能把恢复后的首帧也丢了。
 */
class VideoDecoderResyncIntakeTest {

    /** 4 字节起始码 + NAL type 1（非 IDR 的编码条带）。 */
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x0A, 0x10, 0x20)

    /** 4 字节起始码 + NAL type 5（IDR）。 */
    private val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x0A, 0x10, 0x20)

    private fun decoder(): VideoDecoder = VideoDecoder().apply {
        logSink = {}          // 不碰 android.util.Log
        running.set(true)     // 走「解码器已启动」分支，而不是停播缓存分支
    }

    @Test
    fun `重同步期间到达的 P 帧不入队且队列不增长`() {
        val d = decoder()
        d.noteGap()
        assertTrue("noteGap 必须让解码器进入等待 IDR 状态", d.resync.awaitingIdr)
        assertEquals(0, d.queuedFrames)

        repeat(5) { d.onFrameReceived(isConfig = false, data = pFrame) }

        assertEquals("重同步期的 P 帧一条都不该占队列", 0, d.queuedFrames)
        assertEquals("被丢的 P 帧要单独计数（不与 drops 混算）", 5L, d.resyncSkippedFrames)
    }

    @Test
    fun `IDR 不被丢弃且喂入后的 P 帧正常入队`() {
        val d = decoder()
        d.noteGap()

        // IDR 必须照常入队（关键帧优先级分支），否则重同步永远等不到参考帧。
        d.onFrameReceived(isConfig = false, data = idrFrame)
        assertEquals(1, d.queuedFrames)
        assertEquals(0L, d.resyncSkippedFrames)

        // 模拟喂帧线程把这个 IDR 喂进解码器：ResyncPolicy 会在这里清除 awaiting。
        assertTrue(d.resync.shouldFeed(isKeyFrame = true))
        assertFalse(d.resync.awaitingIdr)

        // 恢复后的首帧 P 帧必须能入队。
        d.onFrameReceived(isConfig = false, data = pFrame)
        assertEquals("恢复后的首帧不能被丢", 2, d.queuedFrames)
        assertEquals(0L, d.resyncSkippedFrames)
    }

    @Test
    fun `正常态下的 P 帧照常入队`() {
        val d = decoder()
        d.onFrameReceived(isConfig = false, data = pFrame)
        d.onFrameReceived(isConfig = false, data = pFrame)
        assertEquals(2, d.queuedFrames)
        assertEquals(0L, d.resyncSkippedFrames)
    }

    @Test
    fun `等待 IDR 时 CONFIG 仍被接收`() {
        val d = decoder()
        d.noteGap()
        d.onFrameReceived(isConfig = true, data = byteArrayOf(0, 0, 0, 1, 0x67, 0x42))
        assertEquals("CONFIG 不得被接收入口丢掉", 1, d.queuedFrames)
        assertEquals(0L, d.resyncSkippedFrames)
    }
}
