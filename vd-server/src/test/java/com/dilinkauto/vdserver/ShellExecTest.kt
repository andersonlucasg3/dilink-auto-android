package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShellExecTest {

    private class MockProcess(
        private val outputData: String = "",
        private val delayMs: Long = 0,
        private val exitCode: Int = 0
    ) : Process() {
        val destroyedForcibly = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(outputData.toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            if (delayMs > 0) Thread.sleep(delayMs)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val timeoutMillis = unit.toMillis(timeout)
            if (delayMs > timeoutMillis) {
                Thread.sleep(timeoutMillis)
                return false
            }
            if (delayMs > 0) Thread.sleep(delayMs)
            return true
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyedForcibly.set(true)
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly.set(true)
            return this
        }
    }

    @Test
    fun execShellOutputCapturesOutputSuccessfully() {
        val proc = MockProcess(outputData = "test output\n")
        val result = ShellExec.execShellOutput(
            cmd = "echo test",
            timeoutMs = 1000,
            processStarter = { proc }
        )
        assertEquals("test output\n", result)
        assertFalse(proc.destroyedForcibly.get())
    }

    @Test
    fun execShellOutputTimesOutAndDestroysForcibly() {
        val proc = MockProcess(outputData = "partial output", delayMs = 1000)
        val result = ShellExec.execShellOutput(
            cmd = "sleep 1",
            timeoutMs = 50,
            processStarter = { proc }
        )
        assertNull("超时应返回 null", result)
        assertTrue("超时后必须强制销毁挂起进程", proc.destroyedForcibly.get())
    }

    @Test
    fun execShellOutputHandlesProcessStartException() {
        val result = ShellExec.execShellOutput(
            cmd = "bad cmd",
            timeoutMs = 1000,
            processStarter = { throw java.io.IOException("Cannot run program") }
        )
        assertNull("启动异常应捕获并安全返回 null", result)
    }
}
