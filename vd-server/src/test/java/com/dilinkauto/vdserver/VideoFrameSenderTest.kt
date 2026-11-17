package com.dilinkauto.vdserver

import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.VideoMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 钉住 issue #3 的发送队列契约：
 * - 入队顺序 = 写出顺序（单写线程，`AGENTS.md` §0 的硬约束）；
 * - 队列满时丢 P 帧、CONFIG/关键帧优先；
 * - 写失败（FrameCodec 的 5s 无进展）只让写线程退出，`failed` 交给管线收尾；
 * - 采样能把「慢写」与「丢帧」交给自适应码率 / IDR 限流。
 *
 * 测试用一个可控的写动作（闸门 + 闩锁）代替真实 socket，避免时序抖动。
 */
class VideoFrameSenderTest {

    private fun frame(type: Byte = VideoMsg.FRAME, size: Int = 4) = ByteArray(size) { type }

    /** 写动作在闸门打开前一直阻塞，用来把队列填满。 */
    private class GateWrite {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<ByteArray>()
        val lock = Any()

        fun write(f: FrameCodec.Frame) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            synchronized(lock) { written.add(f.payload) }
        }

        fun writtenPayloads(): List<ByteArray> = synchronized(lock) { written.toList() }
    }

    @Test
    fun writesInOfferOrderOnASingleThread() {
        val order = mutableListOf<Int>()
        val done = CountDownLatch(3)
        val sender = VideoFrameSender(writeFrame = { f -> synchronized(order) { order.add(f.payload[0].toInt()) }; done.countDown() })
        sender.start()
        try {
            sender.offer(VideoMsg.FRAME, byteArrayOf(1), false)
            sender.offer(VideoMsg.FRAME, byteArrayOf(2), false)
            sender.offer(VideoMsg.FRAME, byteArrayOf(3), false)
            assertTrue("三帧应在超时前全部写出", done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), order)
        } finally {
            sender.close()
        }
    }

    @Test
    fun dropsNewPFrameWhenQueueIsFull() {
        val gate = GateWrite()
        val sender = VideoFrameSender(capacity = 2, writeFrame = gate::write)
        sender.start()
        try {
            // 第 1 帧被写线程取走并卡在闸门里；队列容量 2 留给后面的帧。
            assertTrue(sender.offer(VideoMsg.FRAME, frame(), false))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.offer(VideoMsg.CONFIG, byteArrayOf(9), false))
            assertTrue(sender.offer(VideoMsg.FRAME, frame(), false))

            // 队列已满（2 帧），新来的 P 帧必须被丢掉。
            assertFalse("满队列下 P 帧应被丢", sender.offer(VideoMsg.FRAME, frame(), false))
            assertEquals(1L, sender.sampleStats().dropped)
        } finally {
            gate.release.countDown()
            sender.close()
        }
    }

    @Test
    fun keyFrameEvictsTheOldestPFrame() {
        val gate = GateWrite()
        val sender = VideoFrameSender(capacity = 2, writeFrame = gate::write)
        sender.start()
        try {
            // 写线程卡在首帧上；随后两帧把队列填满。
            assertTrue(sender.offer(VideoMsg.FRAME, frame(1), false))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.offer(VideoMsg.FRAME, frame(2), false))
            assertTrue(sender.offer(VideoMsg.FRAME, frame(3), false))

            // 满队列下关键帧必须进来，代价是驱逐最老的 P 帧。
            assertTrue("关键帧不能被丢", sender.offer(VideoMsg.FRAME, frame(4), true))
            assertEquals(1L, sender.sampleStats().dropped)

            // 放行后应看到：首帧、被保留的 #3、替换进来的关键帧 #4；#2 被驱逐。
            gate.release.countDown()
            waitUntil { gate.writtenPayloads().size == 3 }
            assertEquals(listOf(1, 3, 4), gate.writtenPayloads().map { it[0].toInt() })
        } finally {
            gate.release.countDown()
            sender.close()
        }
    }

    @Test
    fun configFrameIsProtectedLikeAKeyFrame() {
        val gate = GateWrite()
        val sender = VideoFrameSender(capacity = 2, writeFrame = gate::write)
        sender.start()
        try {
            assertTrue(sender.offer(VideoMsg.FRAME, frame(1), false))
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.offer(VideoMsg.FRAME, frame(2), false))
            assertTrue(sender.offer(VideoMsg.FRAME, frame(3), false))
            // CONFIG 丢失会让车机解码器一直花屏，必须与关键帧同等保护。
            assertTrue(sender.offer(VideoMsg.CONFIG, frame(7), false))
            assertEquals(1L, sender.sampleStats().dropped)
        } finally {
            gate.release.countDown()
            sender.close()
        }
    }

    @Test
    fun writeFailureSetsFailedAndRejectsFurtherOffers() {
        val failedWrite = CountDownLatch(1)
        val sender = VideoFrameSender(writeFrame = { _ ->
            failedWrite.countDown()
            throw IOException("Write timed out: 1023 bytes remaining, send buffer full for 5s")
        })
        sender.start()
        try {
            sender.offer(VideoMsg.FRAME, frame(), false)
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            waitUntil { sender.failed }
            assertFalse("写线程退出后不再接受新帧", sender.offer(VideoMsg.FRAME, frame(), false))
        } finally {
            sender.close()
        }
    }

    @Test
    fun sampleStatsReportsSlowWritesAndResetsThem() {
        val done = CountDownLatch(1)
        val sender = VideoFrameSender(writeFrame = { _ ->
            Thread.sleep(30)
            done.countDown()
        })
        sender.start()
        try {
            sender.offer(VideoMsg.FRAME, frame(), false)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            // lastWriteNanos 不随采样归零：等写线程把 30ms 的耗时记下来再断言。
            waitUntil { sender.lastWriteNanos >= 20_000_000L }
            assertTrue(sender.sampleStats().maxWriteNanos >= 20_000_000L)
        } finally {
            sender.close()
        }
    }

    @Test
    fun closeIsBoundedAndRejectsFurtherOffers() {
        val sender = VideoFrameSender(writeFrame = { _ -> Thread.sleep(5) })
        sender.start()
        assertTrue(sender.offer(VideoMsg.FRAME, frame(), false))
        val startedAt = System.nanoTime()
        sender.close()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("close() 必须有界返回（实测 ${elapsedMs}ms）", elapsedMs < 2000)
        assertFalse(sender.offer(VideoMsg.FRAME, frame(), false))
        // 幂等：二次 close 不抛异常。
        sender.close()
    }

    @Test
    fun capacityMustBePositive() {
        val threw = AtomicBoolean(false)
        try {
            VideoFrameSender(capacity = 0, writeFrame = { _ -> })
        } catch (_: IllegalArgumentException) {
            threw.set(true)
        }
        assertTrue(threw.get())
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("条件在 ${timeoutMs}ms 内未成立", condition())
    }
}
