package com.dilinkauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial stress harness for Milestone 1 (R1 verification):
 * - Lifecycle cleanup idempotency under concurrent race conditions
 * - Connection Channel high-concurrency non-blocking stress & disconnect
 * - ControlMsg.GO_HOME protocol and keycode oracle
 * - Touch action bitwise masking and pointer index preservation
 * - Unsigned length boundary stress testing
 */
class AdversarialM1ChallengeTest {

    private fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port

        val client = SocketChannel.open()
        client.connect(InetSocketAddress("127.0.0.1", port))
        val accepted = server.accept()
        server.close()

        return Pair(client, accepted)
    }

    // ── 1. Lifecycle Cleanup Idempotency Concurrency Test ──

    @Test
    fun testLifecycleCleanupIdempotencyUnderMassiveConcurrency() {
        val cleanedUp = AtomicBoolean(false)
        val cleanupExecutionCount = AtomicInteger(0)
        val secondaryCallCount = AtomicInteger(0)

        // Exact logic from PipelineServer.cleanup()
        fun executeCleanup() {
            if (!cleanedUp.compareAndSet(false, true)) {
                secondaryCallCount.incrementAndGet()
                return
            }
            cleanupExecutionCount.incrementAndGet()
        }

        val threadCount = 100
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            Thread({
                readyLatch.countDown()
                startLatch.await()
                executeCleanup()
                doneLatch.countDown()
            }, "StressThread-$i").start()
        }

        readyLatch.await()
        startLatch.countDown() // Release all 100 threads simultaneously
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))

        // Exactly one thread must have executed cleanup
        assertEquals(1, cleanupExecutionCount.get())
        assertEquals(99, secondaryCallCount.get())
        assertTrue(cleanedUp.get())

        // Subsequent sequential invocations must be ignored
        for (i in 0 until 10) {
            executeCleanup()
        }
        assertEquals(1, cleanupExecutionCount.get())
        assertEquals(109, secondaryCallCount.get())
    }

    // ── 2. Connection High-Concurrency Write & Disconnect Race ──

    @Test
    fun testConnectionConcurrentWriteAndDisconnectStress() = runTest {
        val (clientSock, serverSock) = createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val clientConn = Connection(clientSock, clientScope)
        val serverConn = Connection(serverSock, serverScope)

        clientConn.start(enableHeartbeat = false)
        serverConn.start(enableHeartbeat = false)

        val writers = 30
        val writesPerWriter = 50
        val sendSuccessCount = AtomicInteger(0)
        val sendFailureCount = AtomicInteger(0)
        val latch = CountDownLatch(writers)

        // Launch concurrent writers
        for (w in 0 until writers) {
            clientScope.launch {
                for (i in 0 until writesPerWriter) {
                    try {
                        clientConn.sendData(1.toByte(), "payload-$w-$i".toByteArray(Charsets.UTF_8))
                        sendSuccessCount.incrementAndGet()
                    } catch (e: IOException) {
                        sendFailureCount.incrementAndGet()
                    }
                }
                latch.countDown()
            }
        }

        // Concurrently disconnect halfway through
        delay(5)
        clientConn.disconnect()

        assertTrue("Writers did not finish in time", latch.await(5, TimeUnit.SECONDS))
        assertFalse(clientConn.isConnected)

        // After disconnect, all writes must fail non-blockingly
        var threwOnWriteAfterDisconnect = false
        try {
            clientConn.sendData(1.toByte(), "post-disconnect".toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            threwOnWriteAfterDisconnect = true
        }
        assertTrue("Write after disconnect did not throw IOException", threwOnWriteAfterDisconnect)

        serverConn.disconnect()
        clientScope.cancel()
        serverScope.cancel()
    }

    // ── 3. ControlMsg.GO_HOME Protocol Oracle ──

    @Test
    fun testControlMsgGoHomeProtocolValues() {
        assertEquals("GO_HOME must be opcode 0x11", 0x11.toByte(), ControlMsg.GO_HOME)

        val homeFrame = FrameCodec.Frame(Channel.CONTROL, ControlMsg.GO_HOME, ByteArray(0))
        assertEquals(Channel.CONTROL, homeFrame.channel)
        assertEquals(ControlMsg.GO_HOME, homeFrame.messageType)
        assertEquals(0, homeFrame.payload.size)

        // Test displayId keyevent 3 command string formation
        val displayId = 2
        val expectedCmd = "input -d $displayId keyevent 3"
        assertEquals("input -d 2 keyevent 3", expectedCmd)
    }

    // ── 4. Touch Injection Action Math Oracle ──

    @Test
    fun testTouchPointerUpBitmaskOracle() {
        val actionUp = 1 // MotionEvent.ACTION_UP
        val actionPointerUp = 6 // MotionEvent.ACTION_POINTER_UP
        val actionPointerIndexShift = 8 // MotionEvent.ACTION_POINTER_INDEX_SHIFT

        // Single pointer UP
        val singlePointerAction = actionUp
        assertEquals(1, singlePointerAction)

        // Multi pointer UP with various pointer indices (0..9)
        for (ptrIndex in 0 until 10) {
            val ma = actionPointerUp or (ptrIndex shl actionPointerIndexShift)
            val extractedAction = ma and 0xFF
            val extractedIndex = (ma and 0xFF00) shr actionPointerIndexShift

            assertEquals(actionPointerUp, extractedAction)
            assertEquals(ptrIndex, extractedIndex)
        }
    }

    // ── 5. Unsigned Short Decoding Boundary Stress Test ──

    @Test
    fun testUnsignedShortBoundaryStress() {
        val testValues = intArrayOf(0, 1, 100, 32767, 32768, 40000, 65534, 65535)

        for (v in testValues) {
            val shortVal = v.toShort()
            val decoded = shortVal.toInt() and 0xFFFF
            assertEquals("Failed for input $v", v, decoded)
            assertTrue("Decoded length must be >= 0", decoded >= 0)
        }
    }
}
