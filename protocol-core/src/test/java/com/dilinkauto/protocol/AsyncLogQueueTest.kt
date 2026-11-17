package com.dilinkauto.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AsyncLogQueue 单测：手机 FileLog 与车机 CarLogWriter 共用的"非阻塞入队 → 单消费者"骨架。
 * 溢出策略（满则丢最新）、FIFO 顺序、挂起-唤醒、close 后语义都是两个调用点依赖的契约，此前零覆盖。
 */
class AsyncLogQueueTest {

    @Test
    fun `bounded capacity drops the newest`() = runTest {
        val q = AsyncLogQueue<String>(capacity = 2)
        q.offer("a"); q.offer("b"); q.offer("c") // c 撞上容量上限，被丢弃
        q.close()
        val got = mutableListOf<String>()
        q.consume { got.add(it) }
        assertEquals(listOf("a", "b"), got)
    }

    @Test
    fun `unlimited default drops nothing and keeps fifo`() = runTest {
        val q = AsyncLogQueue<String>()
        repeat(1000) { q.offer("l$it") }
        q.close()
        val got = mutableListOf<String>()
        q.consume { got.add(it) }
        assertEquals(1000, got.size)
        assertEquals("l0", got.first())
        assertEquals("l999", got.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `consume suspends while empty then delivers on offer`() = runTest {
        val q = AsyncLogQueue<String>()
        val got = mutableListOf<String>()
        val job = launch { q.consume { got.add(it) } }
        runCurrent()
        assertTrue("consume 必须在空队列上挂起", got.isEmpty())
        q.offer("x")
        runCurrent()
        assertEquals(listOf("x"), got)
        q.close()
        job.join()
    }

    @Test
    fun `consume returns when closed after draining`() = runTest {
        val q = AsyncLogQueue<String>()
        q.offer("a"); q.offer("b")
        q.close()
        val got = mutableListOf<String>()
        q.consume { got.add(it) }
        assertEquals(listOf("a", "b"), got)
    }

    @Test
    fun `clear discards everything queued`() = runTest {
        val q = AsyncLogQueue<String>()
        repeat(5) { q.offer("l$it") }
        q.clear()
        q.close()
        val got = mutableListOf<String>()
        q.consume { got.add(it) }
        assertTrue(got.isEmpty())
    }

    @Test
    fun `offer after close is ignored and consume returns immediately`() = runTest {
        val q = AsyncLogQueue<String>()
        q.close()
        q.offer("late") // 不得抛异常
        val got = mutableListOf<String>()
        q.consume { got.add(it) } // 已关闭且无元素，应立即返回
        assertTrue(got.isEmpty())
    }
}
