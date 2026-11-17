package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 钉住音频 teardown 的两条不变量（S3-PB 修复，issue #73）：
 *
 * 1. 调用线程（Main）只做入队，不被 `AudioPlayer.stop()` 的有界 join 阻塞；
 * 2. 停播严格先于断开 9640，且上一会话的 stop 严格先于新会话的 start。
 *
 * 为什么能纯 JVM 单测：被测函数只用 JDK 并发工具，不碰 `AudioPlayer`
 * （`MediaCodec` / `AudioTrack` 在无 Robolectric 的纯 JVM 上不可用），也不碰 Android
 * API。`stop` / `disconnect` 由记录回调代替，`Thread.sleep` 模拟 join 的耗时。
 *
 * 无法覆盖的部分：`AudioPlayer.stop()` 真实的 2s join、`AudioTrack` 是否真的停止
 * 出声 —— 那需要真机音频输出（S6 / `needs-live-verify`）。但本缺陷的形态在编排层
 * 即可复现：只要 submit 不阻塞且顺序正确，Main 就不可能被 join 钉住。
 */
class AudioLifecycleTest {

    @Test
    fun `teardown stops playback before disconnecting and both run on the audio thread`() {
        val executor = newAudioExecutor()
        try {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val threads = Collections.synchronizedList(mutableListOf<String>())
            val done = CountDownLatch(1)

            submitAudioTeardown(
                executor,
                stop = {
                    events += "stop-start"
                    Thread.sleep(50)
                    events += "stop-end"
                    threads += Thread.currentThread().name
                },
                disconnect = {
                    events += "disconnect"
                    threads += Thread.currentThread().name
                    done.countDown()
                },
            )

            assertTrue("teardown 必须在预算内跑完", done.await(5, TimeUnit.SECONDS))
            assertEquals("停播必须先于断开 9640", listOf("stop-start", "stop-end", "disconnect"), events)
            assertEquals(
                "两半必须在同一条音频生命周期线程上串行",
                listOf("CarAudioLifecycle", "CarAudioLifecycle"),
                threads,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `teardown does not block the caller while stop is still joining`() {
        val executor = newAudioExecutor()
        try {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val stopEntered = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val done = CountDownLatch(1)

            // stop 卡在「join 还没返回」的状态，直到测试放行。
            submitAudioTeardown(
                executor,
                stop = {
                    stopEntered.countDown()
                    releaseStop.await(5, TimeUnit.SECONDS)
                    events += "stop"
                },
                disconnect = {
                    events += "disconnect"
                    done.countDown()
                },
            )

            // 能走到这里本身就证明 submit 没有等 stop：stop 此刻仍被 releaseStop 卡住。
            assertTrue("teardown 必须在音频线程上开跑", stopEntered.await(5, TimeUnit.SECONDS))
            assertEquals("stop 未放行前调用线程就已返回", 0, events.size)

            releaseStop.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("stop", "disconnect"), events)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a queued stop always runs before the next session's start`() {
        val executor = newAudioExecutor()
        try {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val started = CountDownLatch(1)

            // 旧会话的 teardown（慢）先入队，新会话的 start 紧随其后。
            submitAudioTeardown(
                executor,
                stop = {
                    Thread.sleep(150)
                    events += "stop"
                },
                disconnect = { events += "disconnect" },
            )
            submitAudioLifecycle(executor) {
                events += "start"
                started.countDown()
            }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(
                "迟到的 stop 若排到 start 之后，就会停掉新会话的播放",
                listOf("stop", "disconnect", "start"),
                events,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `submit falls back to inline execution once the executor is shut down`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()

        val ranOn = mutableListOf<String>()
        val async = submitAudioLifecycle(executor) { ranOn += Thread.currentThread().name }

        assertFalse("已 shutdown 的执行器必须返回 false", async)
        assertEquals("必须内联跑完，不得静默丢弃", 1, ranOn.size)
        assertEquals("内联即调用线程", Thread.currentThread().name, ranOn.single())
    }

    @Test
    fun `queued teardown still runs after the executor is shut down`() {
        // destroy 路径只调 shutdown() 不等待：已排队的任务必须照常跑完（JDK 语义），
        // 不能被静默丢弃。这里在测试侧等，被测代码侧不等。
        val executor = newAudioExecutor()
        val events = Collections.synchronizedList(mutableListOf<String>())

        submitAudioTeardown(
            executor,
            stop = {
                Thread.sleep(100)
                events += "stop"
            },
            disconnect = { events += "disconnect" },
        )
        executor.shutdown()

        assertTrue("已排队的 teardown 必须仍被执行", executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("stop", "disconnect"), events)
    }
}
