package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记账口径的行为级回归（issue #205）。
 *
 * 只驱动真实 [VideoDecoder]（不碰 MediaCodec：`running` 手动置位、`logSink` 收日志），
 * 断言现场那两类「收进来了，却既不算喂入也不算丢弃」的分支：
 *
 *  1. `stop()` 清空接收队列 —— 现场 `client.log` 的 `drops` 从不包含这批帧；
 *  2. 停播期缓存的 IDR 被更新的 IDR 覆盖 —— 旧缓存永远不会被喂，`keys_drop` 却恒 0。
 *
 * 这三条用例在修复前就是红的（基线 `Stopping decoder` 行 `drops=0`、无覆盖留痕、
 * 汇总行没有 `codec generation=`），修复后转绿。
 */
class VideoDecoderIntakeAccountingTest {

    /** 4 字节起始码 + NAL type 5（IDR）。 */
    private val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x0A, 0x10, 0x20)

    /** 4 字节起始码 + NAL type 1（非 IDR 的编码条带）。 */
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x0A, 0x10, 0x20)

    private fun decoder(logs: MutableList<String>): VideoDecoder = VideoDecoder().apply {
        logSink = { logs += it }
    }

    @Test
    fun `stop 清空队列时未喂的帧必须记入 drops`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(true)
        d.onFrameReceived(isConfig = false, data = idrFrame)
        assertEquals("关键帧必须入队", 1, d.queuedFrames)

        d.stop()

        val line = logs.last { it.contains("Stopping decoder") }
        assertTrue("stop 丢弃的队列帧必须计入 drops，实际：$line", line.contains("drops=1"))
    }

    @Test
    fun `缓存 IDR 被新 IDR 覆盖时必须留痕`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        // 停播期（start 之前）收到两个 IDR：缓存只留得下最后一个，前一个永远喂不进去。
        d.onFrameReceived(isConfig = false, data = idrFrame)
        d.onFrameReceived(isConfig = false, data = idrFrame)

        assertTrue(
            "被覆盖的缓存 IDR 必须留下可 grep 的痕迹，实际日志：$logs",
            logs.any { it.contains("Cached KEYFRAME replaced") },
        )
    }

    @Test
    fun `Stopping decoder 汇总行必须带 codec generation 便于配对`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(true)

        d.stop()

        val line = logs.last { it.contains("Stopping decoder") }
        assertTrue("配对锚点缺失，实际：$line", line.contains("codec generation="))
    }

    @Test
    fun `停播期到达的 P 帧不会占用队列`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(false)

        d.onFrameReceived(isConfig = false, data = pFrame)

        assertEquals("停播期没有解码器可解，P 帧不得入队", 0, d.queuedFrames)
        assertFalse("停播期缓存只对 IDR 生效", logs.any { it.contains("Cached KEYFRAME") })
    }
}
