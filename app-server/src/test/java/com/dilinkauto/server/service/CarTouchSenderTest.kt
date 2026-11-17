package com.dilinkauto.server.service

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.TouchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarTouchSenderTest {

    private fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val client = SocketChannel.open(server.localAddress)
        val accepted = server.accept()
        server.close()
        return Pair(client, accepted)
    }

    @Test
    fun testDropCountIncrementsWhenConnectionNull() {
        val sender = CarTouchSender(
            inputConnectionProvider = { null },
            stateProvider = { CarConnectionService.State.CONNECTED },
            log = { _, _ -> }
        )

        assertEquals(0L, sender.getDropCount())
        sender.sendTouchEvent(TouchEvent(0, 0, 100f, 100f, 1f, 1000L))
        assertEquals(1L, sender.getDropCount())
        sender.sendTouchBatch(listOf(TouchEvent(2, 0, 100f, 100f, 1f, 1000L)))
        assertEquals(2L, sender.getDropCount())

        sender.resetCounters()
        assertEquals(0L, sender.getDropCount())
        sender.shutdown()
    }

    @Test
    fun testSendCountAndMultiBatchIncrements() {
        val (clientSock, serverSock) = createConnectedSockets()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val conn = Connection(clientSock, scope)
        val serverConn = Connection(serverSock, scope)
        conn.start(enableHeartbeat = false)
        serverConn.start(enableHeartbeat = false)

        val latch = CountDownLatch(2)
        serverConn.onFrames(Channel.INPUT) {
            latch.countDown()
        }

        val sender = CarTouchSender(
            inputConnectionProvider = { conn },
            stateProvider = { CarConnectionService.State.CONNECTED },
            log = { _, _ -> }
        )

        sender.sendTouchEvent(TouchEvent(0, 0, 100f, 100f, 1f, 1000L))
        sender.sendTouchBatch(listOf(
            TouchEvent(2, 0, 100f, 100f, 1f, 1000L),
            TouchEvent(2, 1, 200f, 200f, 1f, 1000L)
        ))

        assertTrue("Frames received within timeout", latch.await(3, TimeUnit.SECONDS))
        assertEquals(2L, sender.getSendCount())
        assertEquals(1L, sender.getMultiBatchCount())

        sender.resetCounters()
        assertEquals(0L, sender.getSendCount())
        assertEquals(0L, sender.getMultiBatchCount())
        sender.shutdown()
        conn.disconnect()
        serverConn.disconnect()
        scope.cancel()
    }
}
