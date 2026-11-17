package com.dilinkauto.protocol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * 钉住 issue #70 的写侧语义：`FrameCodec.writeAll` 只在「连续 timeoutNs 零进展」时才判死，
 * 只要还在推进就不抛。这条判据直接决定引擎会不会被误判成断链而整条会话重建
 * （2.5h 导航日志里的 4 次 `Write timed out` 都是整链重建的起点）。
 *
 * 用真实 loopback socket + 明确指定 64KB 收发缓冲把管道堵住，再把 deadline 缩到 100ms，
 * 避免测试真的等 10 秒。
 *
 * 注意：**payload 必须远大于管道容量**。Windows loopback 的默认缓冲/窗口比 Linux 大得多，
 * 早期版本的 128KB payload 会被内核整个吞下，`write` 全程不阻塞，测试因而假失败。
 */
class FrameCodecWriteTimeoutTest {

    private class ChannelPair(val ch: SocketChannel, val peer: Socket)

    private fun stuckPair(): ChannelPair {
        ServerSocket(0).use { server ->
            val ch = SocketChannel.open(InetSocketAddress("127.0.0.1", server.localPort))
            val peer = server.accept()
            ch.configureBlocking(false)
            // 收发缓冲固定 64KB：对端不读时，飞行中的数据最多约 128KB，2MB payload 一定堵住。
            ch.socket().sendBufferSize = CHUNK_BYTES
            peer.receiveBufferSize = CHUNK_BYTES
            return ChannelPair(ch, peer)
        }
    }

    /** 对端完全不读：payload 远大于管道容量 → 零进展 → 超过 deadline 抛 IOException。 */
    @Test
    fun noProgressBeyondDeadlineThrows() {
        val p = stuckPair()
        try {
            val startedAt = System.nanoTime()
            var threw = false
            try {
                FrameCodec.writeAll(p.ch, ByteBuffer.wrap(ByteArray(PAYLOAD_BYTES)), DEADLINE_NS)
            } catch (e: IOException) {
                threw = true
                assertTrue("异常应说明是写超时: ${e.message}", e.message.orEmpty().contains("Write timed out"))
            }
            assertTrue("零进展时 writeAll 必须抛 Write timed out", threw)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            // 前半段证明 payload 确实把管道堵住了（没有整块被内核吞掉）。
            assertTrue("应在 deadline 附近抛出，实测 ${elapsedMs}ms", elapsedMs >= DEADLINE_NS / 1_000_000 / 2)
            assertTrue("不应远超 deadline，实测 ${elapsedMs}ms", elapsedMs < 3000)
        } finally {
            runCatching { p.ch.close() }
            runCatching { p.peer.close() }
        }
    }

    /** 对端慢但持续在读：单次停顿远小于 deadline，总时长超过 deadline → 不抛。 */
    @Test
    fun slowButSteadyProgressDoesNotThrow() {
        val p = stuckPair()
        val drained = AtomicLong()
        val reader = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            try {
                while (true) {
                    val n = p.peer.getInputStream().read(buf)
                    if (n < 0) break
                    drained.addAndGet(n.toLong())
                    // 每次只收一小块就睡 20ms：写侧一直有进展，但总量走得很慢
                    // （2MB / 64KB * 20ms ≈ 640ms，远大于 100ms deadline）。
                    Thread.sleep(PACE_MS)
                }
            } catch (_: Exception) {
                // 测试收尾关闭 socket 时的正常退出
            }
        }, "slow-reader").apply { isDaemon = true; start() }
        try {
            val startedAt = System.nanoTime()
            FrameCodec.writeAll(p.ch, ByteBuffer.wrap(ByteArray(PAYLOAD_BYTES)), DEADLINE_NS)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            // 总耗时超过 deadline 却成功返回 = deadline 确实被抓到进展就重置了。
            assertTrue("总耗时 ${elapsedMs}ms 应超过 ${DEADLINE_NS / 1_000_000}ms deadline", elapsedMs >= DEADLINE_NS / 1_000_000)
            // 写侧返回只代表入了管道；等读线程把尾巴收完再断言，避免竞态。
            val waitDeadline = System.nanoTime() + 5_000_000_000L
            while (drained.get() < PAYLOAD_BYTES.toLong() && System.nanoTime() < waitDeadline) {
                Thread.sleep(5)
            }
            assertTrue("对端应已收完 payload，实际 ${drained.get()} 字节", drained.get() >= PAYLOAD_BYTES.toLong())
        } finally {
            reader.interrupt()
            runCatching { p.ch.close() }
            runCatching { p.peer.close() }
        }
    }

    private companion object {
        const val PAYLOAD_BYTES = 2 * 1024 * 1024 // 2MB，远大于 64KB+64KB 的管道容量
        const val CHUNK_BYTES = 64 * 1024
        const val PACE_MS = 20L
        const val DEADLINE_NS = 100_000_000L // 100ms
    }
}
