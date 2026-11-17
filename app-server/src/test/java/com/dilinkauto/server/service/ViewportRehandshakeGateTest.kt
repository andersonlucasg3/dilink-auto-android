package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视口变化重握手门的回归钉子（issue #22）。
 *
 * 缺陷签名有两条，恰好是旋转期间黑屏叠加的两种来源：
 * 1. 旋转动画连续上报多个尺寸时，现状会对每个尺寸各发一次重握手；
 * 2. 重握手进行中收到新尺寸时，现状会并发再发一次。
 *
 * 定时器用假的 [FakeScheduler] 驱动，不依赖协程/Android。
 */
class ViewportRehandshakeGateTest {

    /** 假调度器：只记录任务，由测试决定何时触发。 */
    private class FakeScheduler {
        class Task(val delayMs: Long, val action: () -> Unit) : ViewportRehandshakeGate.Cancelable {
            var cancelled = false
            var executed = false
            override fun cancel() {
                cancelled = true
            }
        }

        val tasks = mutableListOf<Task>()

        fun schedule(delayMs: Long, action: () -> Unit): ViewportRehandshakeGate.Cancelable =
            Task(delayMs, action).also { tasks += it }

        /** 运行最后一次未被取消的任务；没有可运行任务时返回 false。 */
        fun fireLatest(): Boolean {
            val task = tasks.lastOrNull { !it.cancelled && !it.executed } ?: return false
            task.executed = true
            task.action()
            return true
        }

        fun pendingCount(): Int = tasks.count { !it.cancelled && !it.executed }
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val requests = mutableListOf<ViewportRehandshakeGate.Target>()
        var inFlight = false
        val logs = mutableListOf<String>()

        val gate = ViewportRehandshakeGate(
            debounceMs = 700L,
            schedule = { ms, action -> scheduler.schedule(ms, action) },
            request = { requests += it },
            isInFlight = { inFlight },
            log = { logs += it },
        )

        fun changed(
            width: Int,
            height: Int,
            appliedWidth: Int = 1000,
            appliedHeight: Int = 500,
            ready: Boolean = true,
        ) {
            gate.onViewportChanged(
                candidate = ViewportRehandshakeGate.Target(width, height, 400),
                appliedWidth = appliedWidth,
                appliedHeight = appliedHeight,
                sessionReady = ready,
            )
        }
    }

    @Test
    fun `three successive sizes produce one re-handshake with the last size`() {
        val h = Harness()
        h.changed(1204, 660)
        h.changed(720, 1144)
        h.changed(1080, 660)
        assertTrue("尺寸变化必须排队", h.scheduler.pendingCount() > 0)
        h.scheduler.fireLatest()
        assertEquals("连续 3 个尺寸只能产生 1 次重握手", 1, h.requests.size)
        assertEquals(1080 to 660, h.requests.single().let { it.width to it.height })
    }

    @Test
    fun `size arriving during an in-flight re-handshake is replayed once after it finishes`() {
        val h = Harness()
        h.changed(1204, 660)
        h.scheduler.fireLatest()
        assertEquals(1, h.requests.size)

        // 第一次重握手已开始：服务在 rehandshakeOnExistingControl 入口置位。
        h.inFlight = true
        h.changed(720, 1144)
        h.scheduler.fireLatest()
        assertEquals("重握手进行中不得并发再发", 1, h.requests.size)

        h.inFlight = false
        h.gate.onRehandshakeFinished()
        assertEquals("结束后按最新尺寸补跑 1 次", 2, h.requests.size)
        assertEquals(720 to 1144, h.requests.last().let { it.width to it.height })
        assertTrue("补跑后不得残留排队", h.scheduler.pendingCount() == 0)
    }

    @Test
    fun `size equal to the applied viewport is ignored`() {
        val h = Harness()
        h.changed(1000, 500, appliedWidth = 1000, appliedHeight = 500)
        assertEquals("相同尺寸不得排队", 0, h.scheduler.pendingCount())
        assertEquals(0, h.requests.size)
    }

    @Test
    fun `viewport changes outside a live session are ignored`() {
        val h = Harness()
        h.changed(1204, 660, ready = false)
        h.scheduler.fireLatest()
        assertEquals(0, h.requests.size)
    }

    @Test
    fun `finishing a re-handshake without a queued size does not replay it`() {
        val h = Harness()
        h.changed(1204, 660)
        h.scheduler.fireLatest()
        h.gate.onRehandshakeFinished()
        assertEquals("已消费的目标不得被 finally 再跑一次", 1, h.requests.size)
    }

    @Test
    fun `reset drops the queued size from the previous session`() {
        val h = Harness()
        h.changed(1204, 660)
        h.gate.reset()
        h.scheduler.fireLatest()
        h.gate.onRehandshakeFinished()
        assertEquals("会话重建后旧尺寸不得补跑", 0, h.requests.size)
    }
}
