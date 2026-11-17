package com.dilinkauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionTest {

    private fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> =
        TestSockets.createConnectedSockets()

    @Test
    fun testNonBlockingChannelQueuingAndDelivery() = runTest {
        val (clientSock, serverSock) = createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val clientConn = Connection(clientSock, clientScope)
        val serverConn = Connection(serverSock, serverScope)

        val receivedFrames = mutableListOf<FrameCodec.Frame>()
        val latch = CountDownLatch(3)

        serverConn.onFrames(Channel.DATA) { frame ->
            synchronized(receivedFrames) {
                receivedFrames.add(frame)
            }
            latch.countDown()
        }

        clientConn.start(enableHeartbeat = false)
        serverConn.start(enableHeartbeat = false)

        // Enqueue 3 frames non-blockingly
        clientConn.sendData(1.toByte(), "Frame 1".toByteArray(Charsets.UTF_8))
        clientConn.sendData(2.toByte(), "Frame 2".toByteArray(Charsets.UTF_8))
        clientConn.sendData(3.toByte(), "Frame 3".toByteArray(Charsets.UTF_8))

        val receivedInTime = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Frames were not received within timeout", receivedInTime)

        val receivedPayloads = synchronized(receivedFrames) {
            receivedFrames.map { String(it.payload, Charsets.UTF_8) }
        }
        assertEquals(3, receivedPayloads.size)
        assertTrue(receivedPayloads.contains("Frame 1"))
        assertTrue(receivedPayloads.contains("Frame 2"))
        assertTrue(receivedPayloads.contains("Frame 3"))

        clientConn.disconnect()
        serverConn.disconnect()
        clientScope.cancel()
        serverScope.cancel()
    }

    @Test
    fun testDisconnectClosesWriteQueueAndRejectsEnqueuing() = runTest {
        val (clientSock, serverSock) = createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val clientConn = Connection(clientSock, clientScope)

        clientConn.start(enableHeartbeat = false)
        assertTrue(clientConn.isConnected)

        clientConn.disconnect()
        assertFalse(clientConn.isConnected)

        try {
            clientConn.sendData(1.toByte(), "After disconnect".toByteArray(Charsets.UTF_8))
            fail("sendData after disconnect should throw IOException")
        } catch (e: IOException) {
            // Expected: not connected or channel closed
        }

        serverSock.close()
        clientScope.cancel()
    }
}
