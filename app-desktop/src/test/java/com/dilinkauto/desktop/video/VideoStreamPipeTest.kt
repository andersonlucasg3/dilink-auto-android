package com.dilinkauto.desktop.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * VideoStreamPipe 单测：它承担"网络线程 ↔ FFmpeg 解码线程"的字节交接，
 * 阻塞语义、EOF、丢块策略错一个都会表现为画面卡死或花屏。
 */
class VideoStreamPipeTest {

    @Test
    fun `写入的字节按顺序完整读出`() {
        val pipe = VideoStreamPipe()
        pipe.write(byteArrayOf(1, 2, 3))
        pipe.write(byteArrayOf(4, 5))
        val out = ByteArray(5)
        assertEquals(5, readFully(pipe.inputStream, out))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), out)
        assertEquals(5L, pipe.bytesWritten.get())
    }

    @Test
    fun `读取在无数据时阻塞直到写入`() {
        val pipe = VideoStreamPipe()
        val got = AtomicReference<Int>()
        val started = CountDownLatch(1)
        val reader = Thread {
            started.countDown()
            got.set(pipe.inputStream.read())
        }
        reader.isDaemon = true
        reader.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(200) // 给读线程一点时间去阻塞在 poll 上
        assertNull(got.get()) // 还没有数据，读线程必须仍在阻塞
        pipe.write(byteArrayOf(0x2A))
        reader.join(2000)
        assertEquals(0x2A, got.get())
    }

    @Test
    fun `close 后阻塞中的读返回 EOF`() {
        val pipe = VideoStreamPipe()
        val got = AtomicInteger(Int.MIN_VALUE)
        val reader = Thread { got.set(pipe.inputStream.read()) }
        reader.isDaemon = true
        reader.start()
        Thread.sleep(150) // 让读线程进入阻塞
        pipe.close()
        reader.join(2000)
        assertFalse(reader.isAlive)
        assertEquals(-1, got.get())
    }

    @Test
    fun `close 后写入被忽略`() {
        val pipe = VideoStreamPipe()
        pipe.close()
        assertFalse(pipe.write(byteArrayOf(1)))
    }

    @Test
    fun `队列满时丢最旧的块并计数`() {
        val pipe = VideoStreamPipe(maxChunks = 2)
        pipe.write(byteArrayOf(1))
        pipe.write(byteArrayOf(2))
        pipe.write(byteArrayOf(3)) // 队列满：挤掉最旧的 1
        assertEquals(1L, pipe.chunksDropped.get())
        val out = ByteArray(2)
        assertEquals(2, readFully(pipe.inputStream, out))
        assertArrayEquals(byteArrayOf(2, 3), out)
    }

    @Test
    fun `单次读小于块大小时返回部分数据且不丢剩余`() {
        val pipe = VideoStreamPipe()
        pipe.write(byteArrayOf(1, 2, 3, 4, 5))
        val first = ByteArray(2)
        assertEquals(2, readFully(pipe.inputStream, first))
        assertArrayEquals(byteArrayOf(1, 2), first)
        val second = ByteArray(3)
        assertEquals(3, readFully(pipe.inputStream, second))
        assertArrayEquals(byteArrayOf(3, 4, 5), second)
    }

    @Test
    fun `available 报告未消费的字节数`() {
        val pipe = VideoStreamPipe()
        pipe.write(byteArrayOf(1, 2, 3))
        assertEquals(3, pipe.inputStream.available())
        pipe.inputStream.read()
        assertEquals(2, pipe.inputStream.available())
    }

    private fun readFully(stream: InputStream, out: ByteArray): Int {
        var read = 0
        while (read < out.size) {
            val n = stream.read(out, read, out.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }
}