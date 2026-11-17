package com.dilinkauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionStressTest {

    private fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> =
        TestSockets.createConnectedSockets()

    @Test
    fun testHighConcurrencySends() = runTest {
        val (clientSock, serverSock) = createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val clientConn = Connection(clientSock, clientScope)
        val serverConn = Connection(serverSock, serverScope)

        val totalSenders = 20
        val framesPerSender = 50
        val totalFrames = totalSenders * framesPerSender

        val receivedCount = AtomicInteger(0)
        val receivedIds = ConcurrentHashMap.newKeySet<Int>()
        val latch = CountDownLatch(totalFrames)

        serverConn.onFrames(Channel.DATA) { frame ->
            val id = java.nio.ByteBuffer.wrap(frame.payload).int
            receivedIds.add(id)
            receivedCount.incrementAndGet()
            latch.countDown()
        }

        clientConn.start(enableHeartbeat = false)
        serverConn.start(enableHeartbeat = false)

        val jobs = (0 until totalSenders).map { senderId ->
            clientScope.launch(Dispatchers.Default) {
                for (i in 0 until framesPerSender) {
                    val frameId = senderId * 10000 + i
                    val payload = java.nio.ByteBuffer.allocate(4).putInt(frameId).array()
                    clientConn.sendData(1.toByte(), payload)
                }
            }
        }

        jobs.joinAll()

        val allReceived = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Expected $totalFrames frames, but only received ${receivedCount.get()}", allReceived)
        assertEquals(totalFrames, receivedIds.size)

        clientConn.disconnect()
        serverConn.disconnect()
        clientScope.cancel()
        serverScope.cancel()
    }

    @Test
    fun testConcurrentSendsAfterDisconnect() = runTest {
        val (clientSock, serverSock) = createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val clientConn = Connection(clientSock, clientScope)

        clientConn.start(enableHeartbeat = false)
        assertTrue(clientConn.isConnected)

        // Disconnect immediately
        clientConn.disconnect()
        assertFalse(clientConn.isConnected)

        val failedSends = AtomicInteger(0)
        val concurrency = 20
        val sendsPerWorker = 20

        val jobs = (0 until concurrency).map {
            clientScope.launch(Dispatchers.Default) {
                for (i in 0 until sendsPerWorker) {
                    try {
                        clientConn.sendData(1.toByte(), "payload".toByteArray())
                    } catch (e: IOException) {
                        failedSends.incrementAndGet()
                    }
                }
            }
        }

        jobs.joinAll()
        assertEquals(concurrency * sendsPerWorker, failedSends.get())

        serverSock.close()
        clientScope.cancel()
    }

    @Test
    fun testRapidConnectDisconnectCycles() = runTest {
        val cycles = 30
        for (i in 0 until cycles) {
            val (clientSock, serverSock) = createConnectedSockets()
            val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            val clientConn = Connection(clientSock, clientScope)
            val serverConn = Connection(serverSock, serverScope)

            val latch = CountDownLatch(2)
            serverConn.onFrames(Channel.DATA) {
                latch.countDown()
            }

            clientConn.start(enableHeartbeat = false)
            serverConn.start(enableHeartbeat = false)

            clientConn.sendData(1.toByte(), "hello $i".toByteArray())
            clientConn.sendData(2.toByte(), "world $i".toByteArray())

            latch.await(2, TimeUnit.SECONDS)

            clientConn.disconnect()
            serverConn.disconnect()
            clientScope.cancel()
            serverScope.cancel()

            assertFalse(clientConn.isConnected)
            assertFalse(serverConn.isConnected)
        }
    }
}
