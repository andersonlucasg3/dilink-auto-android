package com.dilinkauto.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel

class NioReaderTest {

    private class ChannelPair(val ch: SocketChannel, val peer: Socket) : AutoCloseable {
        override fun close() {
            runCatching { ch.close() }
            runCatching { peer.close() }
        }
    }

    private fun testPair(): ChannelPair {
        ServerSocket(0).use { server ->
            val ch = SocketChannel.open(InetSocketAddress("127.0.0.1", server.localPort))
            val peer = server.accept()
            ch.configureBlocking(false)
            return ChannelPair(ch, peer)
        }
    }

    @Test
    fun shrinkIfOversizedFlipsBufferForSubsequentReadBlocking() {
        testPair().use { p ->
            val reader = NioReader(p.ch)
            val largeSize = NioReader.DEFAULT_CAPACITY * 4 + 8192 // > 512KB triggers shrink
            val largeData = ByteArray(largeSize) { (it % 127).toByte() }

            // Write large payload from peer in background thread to avoid socket buffer deadlock
            val writerThread = Thread {
                p.peer.getOutputStream().write(largeData)
                p.peer.getOutputStream().flush()
            }
            writerThread.start()

            val readBuf = ByteArray(largeSize)
            reader.readFullyBlocking(readBuf)
            writerThread.join()

            // Trigger shrink after large frame is fully consumed
            reader.maybeShrink()

            // Write a subsequent int from peer
            val expectedInt = 0x12345678
            val intBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            p.peer.getOutputStream().write(intBytes)
            p.peer.getOutputStream().flush()

            // If shrink didn't flip, remaining() is 128KB of zeros, so readIntBlocking returns 0 instead of reading channel
            val actualInt = reader.readIntBlocking()
            assertEquals("Subsequent int must be read from channel after shrink", expectedInt, actualInt)
        }
    }

    @Test
    fun shrinkIfOversizedFlipsBufferForSubsequentReadCoroutine() = runBlocking {
        testPair().use { p ->
            val reader = NioReader(p.ch)
            val largeSize = NioReader.DEFAULT_CAPACITY * 4 + 8192
            val largeData = ByteArray(largeSize) { (it % 127).toByte() }

            val writerThread = Thread {
                p.peer.getOutputStream().write(largeData)
                p.peer.getOutputStream().flush()
            }
            writerThread.start()

            val readBuf = ByteArray(largeSize)
            reader.readFully(readBuf)
            writerThread.join()

            reader.maybeShrink()

            val expectedInt = 0x12345678
            val intBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            p.peer.getOutputStream().write(intBytes)
            p.peer.getOutputStream().flush()

            val actualInt = reader.readInt()
            assertEquals("Subsequent int must be read from channel after shrink in coroutine", expectedInt, actualInt)
        }
    }
}
