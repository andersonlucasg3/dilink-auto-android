package com.dilinkauto.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBackpressureTest {

    @Test
    fun sendFrameAppliesBackpressureWhenQueueFull() {
        val (clientSock, serverSock) = TestSockets.createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            clientSock.socket().sendBufferSize = 16 * 1024
            serverSock.socket().receiveBufferSize = 16 * 1024

            val clientConn = Connection(clientSock, clientScope)
            clientConn.start(enableHeartbeat = false)

            var acceptedCount = 0
            var rejectedCount = 0
            val dummyPayload = ByteArray(1024) { 1 }

            for (i in 0 until 200) {
                val accepted = clientConn.sendVideo(VideoMsg.FRAME, dummyPayload)
                if (accepted) {
                    acceptedCount++
                } else {
                    rejectedCount++
                }
            }

            assertTrue("At least some video frames must be rejected by backpressure", rejectedCount > 0)
            assertTrue("Accepted count must be positive", acceptedCount > 0)
            assertTrue("pendingSends must be bounded by QUEUE_CAPACITY", clientConn.pendingSends <= Connection.QUEUE_CAPACITY)
        } finally {
            clientScope.cancel()
            serverScope.cancel()
            runCatching { clientSock.close() }
            runCatching { serverSock.close() }
        }
    }
}
