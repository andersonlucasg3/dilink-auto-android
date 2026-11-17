package com.dilinkauto.vdserver

import com.dilinkauto.protocol.AudioMsg
import com.dilinkauto.protocol.AudioPayload
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 钉住 S3-PA 的音频落链契约（issue #73）：
 * - Sink 的 CONFIG/FRAME → 协议帧字节流（`channel=AUDIO` / `AudioMsg.CONFIG|FRAME`，
 *   payload 走 S1 冻结的 `AudioPayload.encode*`）；
 * - 队列满时**丢最旧**（与视频「丢 P 帧」不同），但队头 CONFIG 不被丢；
 * - 写失败只让写线程退出（`failed`），不穿透到采集线程；
 * - `close()` 有界且幂等。
 *
 * 期望值全部是**字面量**，不由被测函数反推。
 */
class SocketAudioSinkTest {

    private fun config(csd: ByteArray = byteArrayOf(0x11, 0x90.toByte())) = AudioPayload.Config(
        sampleRate = 48_000, channelCount = 2, bitrate = 128_000, csd = csd
    )

    /** 写动作在闸门打开前一直阻塞，用来把队列填满。 */
    private class GateWrite {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<FrameCodec.Frame>()
        val lock = Any()

        fun write(f: FrameCodec.Frame) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            synchronized(lock) { written.add(f) }
        }

        fun writtenFrames(): List<FrameCodec.Frame> = synchronized(lock) { written.toList() }
    }

    // ── 纯函数：Sink 回调 → 协议帧 ──

    @Test
    fun configFrameUsesAudioChannelAndConfigType() {
        // csd = [0x11,0x90]；CONFIG payload = codec(1) rate(4) ch(1) br(4) len(1) csd(2) = 13 B
        val f = audioConfigFrame(config())
        assertEquals(Channel.AUDIO, f.channel)
        assertEquals(2.toByte(), f.channel)
        assertEquals(AudioMsg.CONFIG, f.messageType)
        assertEquals(0x01.toByte(), f.messageType)
        assertEquals(13, f.payload.size)
        assertEquals(1.toByte(), f.payload[0]) // codec = AAC-LC
        // sampleRate 48000 = 0x0000BB80
        assertEquals(0x00.toByte(), f.payload[1])
        assertEquals(0x00.toByte(), f.payload[2])
        assertEquals(0xBB.toByte(), f.payload[3])
        assertEquals(0x80.toByte(), f.payload[4])
        assertEquals(2.toByte(), f.payload[5]) // channels
        // bitrate 128000 = 0x0001F400
        assertEquals(0x00.toByte(), f.payload[6])
        assertEquals(0x01.toByte(), f.payload[7])
        assertEquals(0xF4.toByte(), f.payload[8])
        assertEquals(0x00.toByte(), f.payload[9])
        assertEquals(2.toByte(), f.payload[10]) // csdLen
        assertEquals(0x11.toByte(), f.payload[11])
        assertEquals(0x90.toByte(), f.payload[12])
    }

    @Test
    fun dataFrameUsesAudioChannelAndFrameTypeWithBigEndianPts() {
        val f = audioDataFrame(AudioPayload.Frame(ptsMs = 100, data = byteArrayOf(0x0A, 0x0B, 0x0C)))
        assertEquals(2.toByte(), f.channel)
        assertEquals(0x02.toByte(), f.messageType)
        assertEquals(7, f.payload.size) // PTS(4) + 3 B
        // PTS 100 = 0x00000064（大端）
        assertEquals(0x00.toByte(), f.payload[0])
        assertEquals(0x00.toByte(), f.payload[1])
        assertEquals(0x00.toByte(), f.payload[2])
        assertEquals(0x64.toByte(), f.payload[3])
        assertEquals(0x0A.toByte(), f.payload[4])
        assertEquals(0x0B.toByte(), f.payload[5])
        assertEquals(0x0C.toByte(), f.payload[6])
    }

    // ── 纯函数：队列满时的驱逐下标 ──

    @Test
    fun evictIndexDropsTheOldestByDefault() {
        assertNull(audioEvictIndex(0, headIsConfig = false))
        assertEquals(0, audioEvictIndex(1, headIsConfig = false))
        assertEquals(0, audioEvictIndex(2, headIsConfig = false))
        assertEquals(0, audioEvictIndex(48, headIsConfig = false))
    }

    @Test
    fun evictIndexSkipsAHeadConfig() {
        // 队头是 CONFIG：丢它后面最老的那一帧。
        assertEquals(1, audioEvictIndex(2, headIsConfig = true))
        assertEquals(1, audioEvictIndex(48, headIsConfig = true))
        // 队列里只剩一个 CONFIG：没有可丢的旧帧。
        assertNull(audioEvictIndex(1, headIsConfig = true))
    }

    // ── 有界队列行为 ──

    @Test
    fun dropsTheOldestFrameWhenQueueIsFull() {
        val gate = GateWrite()
        val sink = SocketAudioSink(capacity = 2, writeFrame = gate::write, closeChannel = {})
        sink.start()
        try {
            // 第 1 帧被写线程取走并卡在闸门里；队列容量 2 留给后面的帧。
            sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2)))
            sink.onFrame(AudioPayload.Frame(3, byteArrayOf(3)))

            // 队列已满，新帧挤掉**最旧**的 #2。
            sink.onFrame(AudioPayload.Frame(4, byteArrayOf(4)))
            assertEquals(1L, sink.droppedFrames)

            gate.release.countDown()
            waitUntil { gate.writtenFrames().size == 3 }
            assertEquals(
                listOf(1, 3, 4),
                gate.writtenFrames().map { AudioPayload.decodeFrame(it.payload).data[0].toInt() }
            )
        } finally {
            gate.release.countDown()
            sink.close()
        }
    }

    @Test
    fun keepsAHeadConfigWhenAQueueOverflows() {
        val gate = GateWrite()
        val sink = SocketAudioSink(capacity = 2, writeFrame = gate::write, closeChannel = {})
        sink.start()
        try {
            sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            sink.onConfig(config()) // 队列 [CONFIG]
            sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2))) // 队列 [CONFIG, #2] 满

            // 队头是 CONFIG：丢它后面最老的 #2，CONFIG 保留。
            sink.onFrame(AudioPayload.Frame(3, byteArrayOf(3)))
            assertEquals(1L, sink.droppedFrames)

            gate.release.countDown()
            waitUntil { gate.writtenFrames().size == 3 }
            val types = gate.writtenFrames().map { it.messageType }
            assertEquals(listOf(AudioMsg.FRAME, AudioMsg.CONFIG, AudioMsg.FRAME), types)
        } finally {
            gate.release.countDown()
            sink.close()
        }
    }

    @Test
    fun dropsTheNewFrameWhenOnlyAConfigIsQueued() {
        val gate = GateWrite()
        val sink = SocketAudioSink(capacity = 1, writeFrame = gate::write, closeChannel = {})
        sink.start()
        try {
            sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            sink.onConfig(config()) // 队列 [CONFIG] 满
            // 队列里只剩 CONFIG：没有旧帧可丢，新帧只能被丢，csd 必须保住。
            sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2)))
            assertEquals(1L, sink.droppedFrames)
            assertEquals(1, sink.queued)

            gate.release.countDown()
            // 唯一写出的两帧是「先前被取走的 #1(FRAME)」与「必须保住的 CONFIG」。
            waitUntil { gate.writtenFrames().size == 2 }
            assertEquals(
                listOf(AudioMsg.FRAME, AudioMsg.CONFIG),
                gate.writtenFrames().map { it.messageType }
            )
        } finally {
            gate.release.countDown()
            sink.close()
        }
    }

    @Test
    fun countsSentFramesAndConfigs() {
        val done = CountDownLatch(3)
        val sink = SocketAudioSink(
            capacity = 8,
            writeFrame = { done.countDown() },
            closeChannel = {}
        )
        sink.start()
        try {
            sink.onConfig(config())
            sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
            sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2)))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            waitUntil { sink.sentFrames == 2L && sink.sentConfigs == 1L }
            assertEquals(2L, sink.sentFrames)
            assertEquals(1L, sink.sentConfigs)
        } finally {
            sink.close()
        }
    }

    @Test
    fun writeFailureSetsFailedAndRejectsFurtherFrames() {
        val failedWrite = CountDownLatch(1)
        val sink = SocketAudioSink(
            capacity = 8,
            writeFrame = { _ ->
                failedWrite.countDown()
                throw IOException("Write timed out: 341 bytes remaining, send buffer full for 10s")
            },
            closeChannel = {}
        )
        sink.start()
        try {
            sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            waitUntil { sink.failed }
            sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2)))
            assertEquals(0, sink.queued)
        } finally {
            sink.close()
        }
    }

    @Test
    fun closeIsBoundedAndIdempotent() {
        val sink = SocketAudioSink(capacity = 8, writeFrame = { Thread.sleep(5) }, closeChannel = {})
        sink.start()
        sink.onFrame(AudioPayload.Frame(1, byteArrayOf(1)))
        val startedAt = System.nanoTime()
        sink.close()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("close() 必须有界返回（实测 ${elapsedMs}ms）", elapsedMs < 2000)
        sink.onFrame(AudioPayload.Frame(2, byteArrayOf(2)))
        assertEquals(0, sink.queued)
        sink.close()
    }

    @Test
    fun capacityMustBePositive() {
        val threw = AtomicBoolean(false)
        try {
            SocketAudioSink(capacity = 0, writeFrame = { }, closeChannel = {})
        } catch (_: IllegalArgumentException) {
            threw.set(true)
        }
        assertTrue(threw.get())
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("条件在 ${timeoutMs}ms 内未成立", condition())
    }
}
