package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内账目快照（issue #205）——需要 `intake` 快照，属修复后的新增能力。
 *
 * 现场问题是「差 5 无处可去」，这里直接把「无处可去」变成可断言的事实：
 * 一个会话结束时 `received == fed + dropped + pending`，且 `pending == 0`。
 */
class VideoDecoderIntakeSnapshotTest {

    private val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x0A, 0x10, 0x20)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x0A, 0x10, 0x20)

    private fun decoder(): VideoDecoder = VideoDecoder().apply { logSink = { } }

    @Test
    fun `会话结束时 recv 等于 fed 加 drop 加 pending 且 pending 归零`() {
        val d = decoder()
        d.running.set(true)

        d.onFrameReceived(isConfig = false, data = pFrame)
        d.onFrameReceived(isConfig = false, data = idrFrame)
        d.onFrameReceived(isConfig = false, data = pFrame)

        val before = d.intake.snapshot()
        assertEquals(3L, before.received)
        assertEquals("还没有喂入，三帧都该挂在 pending 上", 3L, before.pending)
        assertNull("收帧阶段就必须平账", d.intake.violationText())

        d.stop()

        val after = d.intake.snapshot()
        assertEquals("stop 不得改动已接收数", 3L, after.received)
        assertEquals(0L, after.fed)
        assertEquals("队列里没喂的三帧必须记丢", 3L, after.dropped)
        assertEquals("会话结束 pending 必须归零", 0L, after.pending)
        assertTrue("账不平：$after", after.balanced)
    }

    @Test
    fun `关键帧分项不随 P 帧串账`() {
        val d = decoder()
        d.running.set(true)

        d.onFrameReceived(isConfig = false, data = pFrame)
        d.onFrameReceived(isConfig = false, data = idrFrame)
        d.stop()

        val s = d.intake.snapshot()
        assertEquals("只有 1 个 IDR", 1L, s.keyReceived)
        assertEquals("关键帧必须和 P 帧一起销账", 1L, s.keyDropped)
        assertEquals(0L, s.keyPending)
        assertEquals(2L, s.dropped)
        assertTrue("账不平：$s", s.balanced)
    }

    @Test
    fun `CONFIG 帧不进账以免污染帧率与丢帧口径`() {
        val d = decoder()
        d.running.set(true)

        d.onFrameReceived(isConfig = true, data = byteArrayOf(0, 0, 0, 1, 0x67, 0x42))

        val s = d.intake.snapshot()
        assertEquals("CONFIG 不是视频帧，不得计入 recv", 0L, s.received)
        assertEquals(1, d.queuedFrames)
    }
}
