package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

class NioWriterTest {

    private class ChannelPair(val ch: SocketChannel, val peer: Socket)

    private fun stuckPair(): ChannelPair {
        ServerSocket(0).use { server ->
            val ch = SocketChannel.open(InetSocketAddress("127.0.0.1", server.localPort))
            val peer = server.accept()
            ch.configureBlocking(false)
            ch.socket().sendBufferSize = CHUNK_BYTES
            peer.receiveBufferSize = CHUNK_BYTES
            return ChannelPair(ch, peer)
        }
    }

    @Test
    fun writeAllSuccessOnAvailableBuffer() {
        val p = stuckPair()
        try {
            val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            NioWriter.writeAll(p.ch, ByteBuffer.wrap(data), DEADLINE_NS)
            val readBuf = ByteArray(data.size)
            var totalRead = 0
            while (totalRead < data.size) {
                val n = p.peer.getInputStream().read(readBuf, totalRead, data.size - totalRead)
                if (n < 0) break
                totalRead += n
            }
            assertEquals(data.size, totalRead)
            for (i in data.indices) {
                assertEquals(data[i], readBuf[i])
            }
        } finally {
            runCatching { p.ch.close() }
            runCatching { p.peer.close() }
        }
    }

    @Test
    fun noProgressBeyondDeadlineThrows() {
        val p = stuckPair()
        try {
            var threw = false
            try {
                NioWriter.writeAll(p.ch, ByteBuffer.wrap(ByteArray(PAYLOAD_BYTES)), DEADLINE_NS)
            } catch (e: IOException) {
                threw = true
                assertTrue("异常应包含超时说明: ${e.message}", e.message.orEmpty().contains("Write timed out"))
            }
            assertTrue("零进展时 writeAll 必须抛 Write timed out", threw)
        } finally {
            runCatching { p.ch.close() }
            runCatching { p.peer.close() }
        }
    }

    private companion object {
        const val PAYLOAD_BYTES = 2 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val DEADLINE_NS = 100_000_000L
    }
}
