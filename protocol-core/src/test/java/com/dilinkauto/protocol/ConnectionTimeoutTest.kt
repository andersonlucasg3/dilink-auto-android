package com.dilinkauto.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionTimeoutTest {

    @Test
    fun writeZeroProgressTimesOutWhenHeartbeatDisabled() {
        val (clientSock, serverSock) = TestSockets.createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            clientSock.socket().sendBufferSize = 16 * 1024
            serverSock.socket().receiveBufferSize = 16 * 1024

            val disconnectLatch = CountDownLatch(1)
            val clientConn = Connection(clientSock, clientScope, writeTimeoutMs = 300L)
            clientConn.onDisconnect {
                disconnectLatch.countDown()
            }

            clientConn.start(enableHeartbeat = false)

            val bigPayload = ByteArray(2 * 1024 * 1024) { 0x5A }
            for (i in 0 until 10) {
                clientConn.sendData(1.toByte(), bigPayload)
            }

            val disconnected = disconnectLatch.await(2, TimeUnit.SECONDS)
            assertTrue("Connection must disconnect on write stall timeout", disconnected)
            assertFalse("Client connection should no longer be connected", clientConn.isConnected)
        } finally {
            clientScope.cancel()
            serverScope.cancel()
            runCatching { clientSock.close() }
            runCatching { serverSock.close() }
        }
    }
}
