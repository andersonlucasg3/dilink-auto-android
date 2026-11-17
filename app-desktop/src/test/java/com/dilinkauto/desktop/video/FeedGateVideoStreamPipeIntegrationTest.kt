package com.dilinkauto.desktop.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedGate ↔ VideoStreamPipe 跨文件接线测试。
 *
 * 关键不变量（此前无任何测试覆盖）：VideoStreamPipe 队列满时"丢最旧块"，
 * 而 FeedGate 把 write()==true 当作"已交付"。管道被打满时，FeedGate 刚写入的
 * CONFIG(SPS/PPS) 可能被逐出队列，而 FeedGate.configSent 仍为 true —— 解码器
 * 因缺 SPS/PPS 永不初始化（唯一症状是 rebuilds 增长），是本模块唯一的静默损坏路径。
 */
class FeedGateVideoStreamPipeIntegrationTest {

    private val config = ByteArray(8) { 0x67.toByte() } // SPS/PPS 形
    private val idr = ByteArray(8) { 0x65.toByte() }    // IDR 帧形

    private lateinit var pipe: VideoStreamPipe

    private fun readExactly(total: Int, timeoutMs: Long = 2000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < total && System.currentTimeMillis() < deadline) {
            val want = minOf(buf.size, total - out.size())
            val n = pipe.inputStream.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `config survives the pipe when there is ample capacity`() {
        // 容量充足：CONFIG 必须在流的最前面（FFmpeg 靠它初始化）
        pipe = VideoStreamPipe(maxChunks = 256)
        val gate = FeedGate(pipe::write)

        assertTrue(gate.onConfig(config))
        assertTrue(gate.onFrame(idr, isKeyFrame = true))

        val total = pipe.inputStream.available()
        assertEquals(config.size + idr.size, total)
        val stream = readExactly(total)
        assertArrayEquals("CONFIG 必须是流首段", config, stream.copyOfRange(0, config.size))
        assertArrayEquals("IDR 紧随其后", idr, stream.copyOfRange(config.size, total))
    }

    @Test
    fun `config is evicted under capacity pressure — documented silent corruption`() {
        // 容量=1：onConfig 写入 CONFIG 占满队列，随后 onFrame 触发"丢最旧"→ CONFIG 被逐出。
        // FeedGate.configSent 仍为 true（它以为已交付），但可读流里已无 SPS/PPS。
        // 本测试锁定这个危害，任何"修复"都必须显式改动，不会被静默吞掉。
        pipe = VideoStreamPipe(maxChunks = 1)
        val gate = FeedGate(pipe::write)

        assertTrue(gate.onConfig(config))
        assertTrue("FeedGate 认为帧已写入", gate.onFrame(idr, isKeyFrame = true))
        assertEquals("闸门并未把它当 pre-config 丢弃（它以为已交付）", 0L, gate.droppedPreConfig)
        assertEquals(1L, pipe.chunksDropped.get())

        val total = pipe.inputStream.available()
        assertEquals(idr.size, total) // 队列里只剩下 IDR，CONFIG 已被逐出
        val stream = readExactly(total)
        assertFalse("可读流不再包含 SPS/PPS", stream.contentEquals(config))
        assertArrayEquals(idr, stream)
    }

    @Test
    fun `beginStream replays config into a fresh pipe so a rebuild recovers`() {
        // 真正的恢复路径：解码器重建时【新建管道】，新 FeedGate 带 initialConfig 重放 SPS/PPS，
        // 之后帧与 CONFIG 都能容下。覆盖此前未测的 FeedGate(initialConfig) 构造参数。
        pipe = VideoStreamPipe(maxChunks = 4)
        val gate = FeedGate(pipe::write, initialConfig = config)

        gate.beginStream() // 重建：把缓存的 CONFIG 重放进新管道
        assertEquals(0L, gate.droppedPreConfig)
        assertTrue(gate.onFrame(idr, isKeyFrame = true))

        val total = pipe.inputStream.available()
        val stream = readExactly(total)
        assertArrayEquals("重建后 CONFIG 被重放到流首", config, stream.copyOfRange(0, config.size))
        assertArrayEquals("随后是 IDR", idr, stream.copyOfRange(config.size, total))
    }
}
