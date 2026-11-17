package com.dilinkauto.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.LockSupport

/**
 * NIO 阻塞/自旋写工具类。
 *
 * 从 [FrameCodec] 抽离底层 SocketChannel 写入循环与超时控制，
 * 提供标准化的非阻塞 SocketChannel 完全写入支持。
 */
object NioWriter {
    /**
     * 写侧「零进展」判死的等待上限。
     *
     * issue #70：原来 5s。这个超时的语义是「链路彻底死了」——一次误判的代价是整条
     * 会话重建（重启引擎 + 重建 VD + 重新部署），远大于多等几秒；而 2.5h 导航日志里
     * 4 次 `Write timed out` 全部落在关键帧突发叠加 WiFi 抖动的窗口上，5s 过于激进。
     * 只要有一次 `write > 0` 就重置 deadline，所以放宽它不会让「真死」的链路永远挂着。
     */
    const val WRITE_TIMEOUT_NS = 10_000_000_000L // 10 秒
    const val WRITE_BACKOFF_NS = 100_000L // 100us — prevents tight spin on full buffer

    /**
     * Writes all remaining bytes from [buf] to [channel].
     * 连续 [WRITE_TIMEOUT_NS] 没有任何进展（send buffer 满 / 对端不读）才抛 IOException。
     */
    @JvmStatic
    fun writeAll(channel: SocketChannel, buf: ByteBuffer) = writeAll(channel, buf, WRITE_TIMEOUT_NS)

    /**
     * [writeAll] 的可注入 deadline 版本：行为单测用它把 10s 缩到几十毫秒，
     * 从而在不拖慢测试的前提下钉住「有进展就重置、零进展才超时」这条语义。
     */
    @JvmStatic
    fun writeAll(channel: SocketChannel, buf: ByteBuffer, timeoutNs: Long) {
        var deadline = System.nanoTime() + timeoutNs
        while (buf.hasRemaining()) {
            val n = channel.write(buf)
            if (n > 0) {
                deadline = System.nanoTime() + timeoutNs // reset on progress
            } else {
                if (System.nanoTime() > deadline) {
                    throw IOException(
                        "Write timed out: ${buf.remaining()} bytes remaining, " +
                            "send buffer full for ${timeoutNs / 1_000_000}ms"
                    )
                }
                LockSupport.parkNanos(WRITE_BACKOFF_NS)
            }
        }
    }
}
