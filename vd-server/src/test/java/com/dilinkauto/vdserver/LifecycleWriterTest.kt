package com.dilinkauto.vdserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LifecycleWriterTest {

    @Test
    fun enqueueFormatsMessageWithPayloadCorrectly() {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val latch = CountDownLatch(1)
        val writer = LifecycleWriter(
            running = { true },
            capacity = 4,
            writeBuffer = { buf ->
                val arr = ByteArray(buf.remaining())
                buf.get(arr)
                sent.add(arr)
                latch.countDown()
            }
        )
        writer.start()

        val payload = byteArrayOf(1, 2, 3, 4)
        writer.enqueue(0x42.toByte(), payload)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        writer.close()

        assertEquals(1, sent.size)
        val expected = ByteBuffer.allocate(9).apply {
            put(0x42.toByte())
            putInt(4)
            put(payload)
        }.array()
        assertArrayEquals(expected, sent[0])
    }

    @Test
    fun enqueueFormatsEmptyPayloadAsSingleByte() {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val latch = CountDownLatch(1)
        val writer = LifecycleWriter(
            running = { true },
            capacity = 4,
            writeBuffer = { buf ->
                val arr = ByteArray(buf.remaining())
                buf.get(arr)
                sent.add(arr)
                latch.countDown()
            }
        )
        writer.start()

        writer.enqueue(0x10.toByte(), ByteArray(0))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        writer.close()

        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(0x10.toByte()), sent[0])
    }

    @Test
    fun enqueueRejectsWhenNotRunning() {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val writer = LifecycleWriter(
            running = { false },
            capacity = 4,
            writeBuffer = { buf ->
                val arr = ByteArray(buf.remaining())
                buf.get(arr)
                sent.add(arr)
            }
        )
        writer.start()

        writer.enqueue(0x10.toByte(), ByteArray(0))
        writer.close()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun dropsWhenQueueIsFull() {
        val unblock = CountDownLatch(1)
        val received = CountDownLatch(1)
        val writer = LifecycleWriter(
            running = { true },
            capacity = 2,
            writeBuffer = {
                received.countDown()
                unblock.await(2, TimeUnit.SECONDS)
            }
        )
        writer.start()

        // First item blocks in writeBuffer
        writer.enqueue(1.toByte(), ByteArray(0))
        assertTrue(received.await(2, TimeUnit.SECONDS))

        // Fill capacity (2 items in queue)
        writer.enqueue(2.toByte(), ByteArray(0))
        writer.enqueue(3.toByte(), ByteArray(0))

        // This one should be dropped silently
        writer.enqueue(4.toByte(), ByteArray(0))

        unblock.countDown()
        writer.close()
    }
}
