package com.dilinkauto.desktop.video

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接"网络收帧线程"与"FFmpeg 解码线程"的字节管道。
 *
 * - 网络线程调用 [write] 写入一块 Annex B 字节（非阻塞）；
 * - 解码线程通过 [inputStream] 以流的方式消费：`read` 阻塞等待新数据——这正是
 *   `FFmpegFrameGrabber` 以 InputStream 模式喂实时码流所需的语义；
 * - 队列满时丢最旧的块：直播优先低延迟，宁可丢帧也不积压；丢块后 FFmpeg 会在下一个
 *   起始码处重新同步，配合上层"重建后等 IDR"策略恢复画面。
 *
 * 一个管道实例只服务一个解码器实例（InputStream 是单次消费的），解码器重建时新建管道。
 */
class VideoStreamPipe(private val maxChunks: Int = DEFAULT_MAX_CHUNKS) {

    private val queue = LinkedBlockingQueue<ByteArray>(maxChunks)

    @Volatile
    private var closed = false

    val bytesWritten = AtomicLong()
    val chunksDropped = AtomicLong()

    /** 写入一块字节；返回 false 表示管道已关闭（本次写入被忽略）。 */
    fun write(data: ByteArray): Boolean {
        if (closed) return false
        if (data.isEmpty()) return true
        while (!queue.offer(data)) {
            // 队列满：丢最旧的块给新数据腾位置（直播优先低延迟）
            if (queue.poll() == null) break
            chunksDropped.incrementAndGet()
        }
        bytesWritten.addAndGet(data.size.toLong())
        return true
    }

    /** 关闭管道：阻塞中的读端会立即收到 EOF（`read` 返回 -1）。 */
    fun close() {
        closed = true
        queue.clear()
    }

    /** 交给 `FFmpegFrameGrabber` 的输入流（阻塞读、EOF 友好）。 */
    val inputStream: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            if (!ensureCurrent()) return -1
            return current!![offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off + len <= b.size) { "非法读取区间 off=$off len=$len size=${b.size}" }
            if (len == 0) return 0
            if (!ensureCurrent()) return -1
            val n = minOf(len, current!!.size - offset)
            System.arraycopy(current!!, offset, b, off, n)
            offset += n
            return n
        }

        override fun available(): Int {
            val cur = current
            val buffered = cur?.let { it.size - offset } ?: 0
            return buffered + queue.sumOf { it.size }
        }

        override fun close() {
            this@VideoStreamPipe.close()
        }

        /** 取下一块数据；管道已关闭且无数据时返回 false（EOF）。 */
        private fun ensureCurrent(): Boolean {
            while (offset >= (current?.size ?: 0)) {
                if (closed) return false
                val next = try {
                    queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return false
                } ?: continue // 超时：回到循环顶部重新检查 closed
                current = next
                offset = 0
            }
            return true
        }
    }

    companion object {
        /** 队列上限（块数）。1080p24 单帧约 10~50KB，256 块 ≈ 数秒缓冲余量。 */
        const val DEFAULT_MAX_CHUNKS = 256
        private const val POLL_TIMEOUT_MS = 100L
    }
}