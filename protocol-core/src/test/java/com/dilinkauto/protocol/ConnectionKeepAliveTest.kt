package com.dilinkauto.protocol

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConnectionKeepAliveTest {

    @Test
    fun `keepalive sends periodic heartbeats`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val heartbeatCount = AtomicInteger(0)
        val latch = CountDownLatch(3)
        val isConnected = AtomicBoolean(true)

        val keepAlive = ConnectionKeepAlive(
            scope = scope,
            isConnected = { isConnected.get() },
            getLastFrameReceivedAt = { System.currentTimeMillis() },
            sendHeartbeat = {
                heartbeatCount.incrementAndGet()
                latch.countDown()
            },
            onDisconnect = {},
            log = {}
        )

        try {
            keepAlive.start(heartbeatIntervalMs = 20L, heartbeatTimeoutMs = 500L)
            assertTrue("Should send at least 3 heartbeats", latch.await(2, TimeUnit.SECONDS))
            assertTrue(heartbeatCount.get() >= 3)
        } finally {
            keepAlive.stop()
            scope.cancel()
        }
    }

    @Test
    fun `watchdog triggers disconnect when frame timeout exceeded`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val isConnected = AtomicBoolean(true)
        val timeoutLatch = CountDownLatch(1)
        var disconnectReason = ""

        val keepAlive = ConnectionKeepAlive(
            scope = scope,
            isConnected = { isConnected.get() },
            getLastFrameReceivedAt = { System.currentTimeMillis() - 200L },
            sendHeartbeat = {},
            onDisconnect = { reason ->
                disconnectReason = reason
                isConnected.set(false)
                timeoutLatch.countDown()
            },
            log = {}
        )

        try {
            keepAlive.start(heartbeatIntervalMs = 20L, heartbeatTimeoutMs = 50L)
            assertTrue("Watchdog should trigger timeout", timeoutLatch.await(2, TimeUnit.SECONDS))
            assertTrue(disconnectReason.startsWith("watchdog: no frame received"))
        } finally {
            keepAlive.stop()
            scope.cancel()
        }
    }

    @Test
    fun `heartbeat error triggers disconnect`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val isConnected = AtomicBoolean(true)
        val errorLatch = CountDownLatch(1)
        var disconnectReason = ""

        val keepAlive = ConnectionKeepAlive(
            scope = scope,
            isConnected = { isConnected.get() },
            getLastFrameReceivedAt = { System.currentTimeMillis() },
            sendHeartbeat = {
                throw IllegalStateException("Network unreachable")
            },
            onDisconnect = { reason ->
                disconnectReason = reason
                isConnected.set(false)
                errorLatch.countDown()
            },
            log = {}
        )

        try {
            keepAlive.start(heartbeatIntervalMs = 20L, heartbeatTimeoutMs = 1000L)
            assertTrue("Heartbeat failure should notify onDisconnect", errorLatch.await(2, TimeUnit.SECONDS))
            assertTrue(disconnectReason.contains("IllegalStateException: Network unreachable"))
        } finally {
            keepAlive.stop()
            scope.cancel()
        }
    }
}
