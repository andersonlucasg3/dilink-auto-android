package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AsyncSocketSenderTest {

    @Test
    fun writesItemsInOrderOnSingleThread() {
        val written = mutableListOf<Int>()
        val done = CountDownLatch(3)
        val sender = AsyncSocketSender<Int>(
            capacity = 10,
            threadName = "TestSender",
            writeItem = {
                synchronized(written) { written.add(it) }
                done.countDown()
            }
        )
        sender.start()
        try {
            assertTrue(sender.enqueue(1) { false })
            assertTrue(sender.enqueue(2) { false })
            assertTrue(sender.enqueue(3) { false })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            synchronized(written) {
                assertEquals(listOf(1, 2, 3), written)
            }
        } finally {
            sender.close()
        }
    }

    @Test
    fun dropsNewItemWhenEvictReturnsFalse() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val sender = AsyncSocketSender<Int>(
            capacity = 2,
            threadName = "TestSender",
            writeItem = {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
        )
        sender.start()
        try {
            assertTrue(sender.enqueue(1) { false })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.enqueue(2) { false })
            assertTrue(sender.enqueue(3) { false })

            // Queue full (size=2), evictor returns false -> reject new item
            assertFalse(sender.enqueue(4) { false })
            assertEquals(2, sender.queued)
        } finally {
            gate.countDown()
            sender.close()
        }
    }

    @Test
    fun evictsOldItemWhenEvictReturnsTrue() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val sender = AsyncSocketSender<Int>(
            capacity = 2,
            threadName = "TestSender",
            writeItem = {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
                synchronized(written) { written.add(it) }
            }
        )
        sender.start()
        try {
            assertTrue(sender.enqueue(1) { false })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.enqueue(2) { false })
            assertTrue(sender.enqueue(3) { false })

            // Queue full, evict the oldest in queue (#2)
            val enqueued = sender.enqueue(4) { q ->
                q.removeFirst()
                true
            }
            assertTrue(enqueued)
        } finally {
            gate.countDown()
            sender.close()
        }
    }

    @Test
    fun writeFailureMarksFailedAndRejectsFurtherOffers() {
        val failedLatch = CountDownLatch(1)
        val writeFailed = AtomicBoolean(false)
        val sender = AsyncSocketSender<Int>(
            capacity = 5,
            threadName = "TestSender",
            writeItem = {
                failedLatch.countDown()
                throw IOException("Simulated network failure")
            },
            onWriteFailed = {
                writeFailed.set(true)
            }
        )
        sender.start()
        try {
            assertTrue(sender.enqueue(1) { false })
            assertTrue(failedLatch.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline && !sender.failed) {
                Thread.sleep(10)
            }
            assertTrue(sender.failed)
            assertTrue(writeFailed.get())
            assertFalse("写失败后拒绝新入队", sender.enqueue(2) { false })
            assertEquals(0, sender.queued)
        } finally {
            sender.close()
        }
    }

    @Test
    fun closeIsBoundedAndIdempotent() {
        val sender = AsyncSocketSender<Int>(
            capacity = 5,
            threadName = "TestSender",
            writeItem = { Thread.sleep(5) }
        )
        sender.start()
        sender.enqueue(1) { false }
        val start = System.currentTimeMillis()
        sender.close()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("close 耗时应小于 2000ms: $elapsed", elapsed < 2000)
        assertFalse(sender.enqueue(2) { false })
        // 幂等测试
        sender.close()
    }
}
