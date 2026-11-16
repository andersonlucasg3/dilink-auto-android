package com.dilinkauto.vdserver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProcessWatchdogTest {

    private val dir: File = Files.createTempDirectory("watchdog-test").toFile()
    private val stopPath: String = File(dir, "stop-file").absolutePath

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun stopRequestTriggersOnStopRequested() {
        File(stopPath).writeText("")
        val running = AtomicBoolean(true)
        val cleanedUp = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val forceCleanupCalled = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {
                stopRequested.set(true)
                running.set(false)
                cleanedUp.set(true)
            },
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 50,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            sleep = { Thread.sleep(it) }
        )

        watchdog.runLoop()

        assertTrue(stopRequested.get())
        assertFalse(forceCleanupCalled.get())
    }

    @Test
    fun normalTeardownDoesNotTriggerForceCleanup() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(true)
        val forceCleanupCalled = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 50,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            sleep = { Thread.sleep(it) }
        )

        watchdog.runLoop()

        assertFalse(forceCleanupCalled.get())
    }

    @Test
    fun timeoutTriggersForceCleanupAndExit() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(false)
        val forceCleanupCalled = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 30,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            exitProcess = { exitCode.set(it) },
            sleep = { /* no-op in test for speed */ }
        )

        watchdog.runLoop()

        assertTrue(forceCleanupCalled.get())
        assertEquals(1, exitCode.get())
    }

    @Test
    fun forceCleanupExceptionIsCaughtAndExitStillProceeds() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)
        val errLogged = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { throw RuntimeException("forced cleanup failed") },
            stopRequestPath = stopPath,
            gracePeriodMs = 20,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            err = { errLogged.set(true) },
            exitProcess = { exitCode.set(it) },
            sleep = { /* no-op in test */ }
        )

        watchdog.runLoop()

        assertTrue(errLogged.get())
        assertEquals(1, exitCode.get())
    }

    @Test
    fun timeoutMessageCarriesTheTeardownProgress() {
        // issue #199：原来只有一句 "cleanup did not complete within 3000ms"，现场无法判断
        // 卡在哪一步、还剩哪些 task。超时消息必须把编排器的进度一起带出来。
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()

        val watchdog = ProcessWatchdog(
            isRunning = { false },
            isCleanedUp = { false },
            onStopRequested = {},
            onForceCleanup = {},
            stopRequestPath = stopPath,
            gracePeriodMs = 20,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            progress = { "at 05/18 apps-moved, remainingTasks=[717]" },
            err = { messages += it },
            exitProcess = {},
            sleep = { /* no-op in test */ }
        )

        watchdog.runLoop()

        assertTrue(
            "超时消息要带当前步号与剩余 task：$messages",
            messages.any { it.contains("05/18 apps-moved") && it.contains("remainingTasks=[717]") }
        )
    }

    @Test
    fun cleanupStartedButHangsTriggersForceCleanupAndExit() {
        val running = AtomicBoolean(false)
        val cleanupFinished = AtomicBoolean(false)
        val forceCleanupCalled = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanupFinished.get() },
            onStopRequested = {},
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 30,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            exitProcess = { exitCode.set(it) },
            sleep = { /* no-op in test for speed */ }
        )

        watchdog.runLoop()

        assertTrue("清理挂起未完成超时后必须强制救护屏幕", forceCleanupCalled.get())
        assertEquals("超时后退出码应为 1", 1, exitCode.get())
    }
}
