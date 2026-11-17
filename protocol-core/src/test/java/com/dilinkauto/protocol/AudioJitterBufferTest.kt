package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 S3-PB 的抖动缓冲状态机（issue #73）——目标水位换算、起播裁水位、
 * 队列上限丢最旧、补静音判定。
 *
 * 期望值一律写**字面量**，不用被测函数反推：否则断言与被测逻辑同错就测不出东西。
 * 水位表来自 #13 §2.5 的 15 组实测（探针 `AudioProbe.java:729` 的
 * `ceil(bufferMs/21.333)`）：60→3、100→5、150→8、200→10。
 */
class AudioJitterBufferTest {

    // ── 目标水位换算 ──

    @Test
    fun `target frames matches the measured 21_333ms table`() {
        // 1024 samples @ 48 kHz = 21.333 ms；ceil(ms * 1000 / 21333)
        assertEquals(3, audioJitterTargetFrames(60))
        assertEquals(5, audioJitterTargetFrames(100))
        assertEquals(8, audioJitterTargetFrames(150))
        assertEquals(10, audioJitterTargetFrames(200))
    }

    @Test
    fun `target frames rounds up rather than down`() {
        // 21.333 ms 恰好一帧 → 1；22 ms 需要 2 帧才够（不能被截断成 1）
        assertEquals(1, audioJitterTargetFrames(21))
        assertEquals(2, audioJitterTargetFrames(22))
        assertEquals(2, audioJitterTargetFrames(42))
        // 43 ms > 2 帧（42.666 ms）→ 必须进位到 3，截断会算成 2
        assertEquals(3, audioJitterTargetFrames(43))
    }

    @Test
    fun `frame constants are the AAC-LC 48k values not 20ms`() {
        assertEquals(1024, AUDIO_SAMPLES_PER_FRAME)
        assertEquals(48_000, AUDIO_SAMPLE_RATE)
        // 硬约束：不是 20_000_000（20 ms 会每秒多发 7~8 帧，R13）
        assertEquals(21_333_333L, AUDIO_FRAME_DURATION_NANOS)
        assertEquals(21_333L, AUDIO_FRAME_DURATION_US)
        assertEquals(46.875, AUDIO_FRAMES_PER_SECOND, 0.0)
    }

    @Test
    fun `default buffer is 150ms within the 60 to 200 range`() {
        assertEquals(150, AUDIO_JITTER_BUFFER_DEFAULT_MS)
        assertEquals(60, AUDIO_JITTER_BUFFER_MIN_MS)
        assertEquals(200, AUDIO_JITTER_BUFFER_MAX_MS)
    }

    // ── 缓冲毫秒值钳位 ──

    @Test
    fun `coerce clamps into 60 to 200`() {
        assertEquals(60, coerceAudioJitterBufferMs(0))
        assertEquals(60, coerceAudioJitterBufferMs(59))
        assertEquals(60, coerceAudioJitterBufferMs(-100))
        assertEquals(150, coerceAudioJitterBufferMs(150))
        assertEquals(200, coerceAudioJitterBufferMs(201))
        assertEquals(200, coerceAudioJitterBufferMs(100_000))
    }

    // ── 队列上限 ──

    @Test
    fun `queue capacity is twice the target`() {
        assertEquals(6, audioJitterQueueCapacity(3))    // 60 ms → 3 → 6
        assertEquals(10, audioJitterQueueCapacity(5))   // 100 ms → 5 → 10
        assertEquals(16, audioJitterQueueCapacity(8))   // 150 ms → 8 → 16
        assertEquals(20, audioJitterQueueCapacity(10))  // 200 ms → 10 → 20
    }

    @Test
    fun `queue capacity factor is two`() {
        assertEquals(2, AUDIO_JITTER_QUEUE_CAPACITY_FACTOR)
    }

    // ── 起播裁水位 ──

    @Test
    fun `startup drop trims down to the target depth`() {
        assertEquals(0, audioJitterStartupDropCount(queued = 0, targetFrames = 8))
        assertEquals(0, audioJitterStartupDropCount(queued = 5, targetFrames = 8))
        assertEquals(0, audioJitterStartupDropCount(queued = 8, targetFrames = 8))
        assertEquals(2, audioJitterStartupDropCount(queued = 10, targetFrames = 8))
        assertEquals(92, audioJitterStartupDropCount(queued = 100, targetFrames = 8))
    }

    // ── 队列上限丢最旧 ──

    @Test
    fun `overflow drops the oldest until there is room for the new frame`() {
        // 未满：不丢
        assertEquals(0, audioJitterOverflowDropCount(queued = 15, capacity = 16))
        // 恰好满：丢 1 帧腾位给新帧
        assertEquals(1, audioJitterOverflowDropCount(queued = 16, capacity = 16))
        // 超上限（异常路径）：丢够
        assertEquals(3, audioJitterOverflowDropCount(queued = 18, capacity = 16))
    }

    // ── 补静音判定 ──

    @Test
    fun `silence is inserted only when the queue is empty`() {
        assertTrue(audioJitterShouldInsertSilence(0))
        assertFalse(audioJitterShouldInsertSilence(1))
        assertFalse(audioJitterShouldInsertSilence(8))
    }

    // ── 有界队列行为 ──

    @Test
    fun `buffer derives its geometry from the buffer ms`() {
        val b = AudioJitterBuffer<ByteArray>(150)
        assertEquals(150, b.bufferMs)
        assertEquals(8, b.targetFrames)
        assertEquals(16, b.capacity)
        assertEquals(0, b.size)
    }

    @Test
    fun `buffer clamps an out of range buffer ms`() {
        assertEquals(60, AudioJitterBuffer<ByteArray>(10).bufferMs)
        assertEquals(200, AudioJitterBuffer<ByteArray>(5000).bufferMs)
        assertEquals(3, AudioJitterBuffer<ByteArray>(10).targetFrames)
        assertEquals(10, AudioJitterBuffer<ByteArray>(5000).targetFrames)
    }

    @Test
    fun `buffer drops the oldest when it exceeds capacity`() {
        val b = AudioJitterBuffer<ByteArray>(150) // capacity 16
        for (i in 0 until 16) assertEquals(0, b.enqueue(byteArrayOf(i.toByte())))
        assertEquals(16, b.size)
        // 第 17 帧挤掉最旧的（值 0）
        assertEquals(1, b.enqueue(byteArrayOf(16)))
        assertEquals(16, b.size)
        assertEquals(1L, b.droppedOldest)
        assertEquals(1.toByte(), b.poll()!![0]) // 队首已是 1
    }

    @Test
    fun `poll returns frames in fifo order then null`() {
        val b = AudioJitterBuffer<ByteArray>(60) // capacity 6
        b.enqueue(byteArrayOf(1))
        b.enqueue(byteArrayOf(2))
        assertEquals(1.toByte(), b.poll()!![0])
        assertEquals(2.toByte(), b.poll()!![0])
        assertEquals(null, b.poll())
    }

    @Test
    fun `hasReachedTarget gates the startup primed state`() {
        val b = AudioJitterBuffer<ByteArray>(150) // target 8
        for (i in 0 until 7) b.enqueue(byteArrayOf(i.toByte()))
        assertFalse(b.hasReachedTarget())
        b.enqueue(byteArrayOf(7))
        assertTrue(b.hasReachedTarget())
    }

    @Test
    fun `primeTrim trims back to target and counts the drop`() {
        val b = AudioJitterBuffer<ByteArray>(150) // target 8, capacity 16
        for (i in 0 until 12) b.enqueue(byteArrayOf(i.toByte()))
        assertEquals(4, b.primeTrim())
        assertEquals(8, b.size)
        assertEquals(4L, b.droppedOldest)
        assertEquals(4.toByte(), b.poll()!![0]) // 前 4 帧（0..3）被裁掉
    }

    @Test
    fun `clear empties the queue but keeps the drop counter`() {
        val b = AudioJitterBuffer<ByteArray>(60) // capacity 6
        for (i in 0 until 6) b.enqueue(byteArrayOf(i.toByte()))
        b.enqueue(byteArrayOf(9)) // 丢 1
        assertEquals(1L, b.droppedOldest)
        b.clear()
        assertEquals(0, b.size)
        assertEquals(1L, b.droppedOldest)
    }
}
