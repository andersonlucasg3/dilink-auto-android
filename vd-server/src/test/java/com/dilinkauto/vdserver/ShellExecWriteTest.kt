package com.dilinkauto.vdserver

import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shell-write contract for S-L3.
 *
 * The persistent shell's stdin is shared by TouchInjector, DisplayPowerController and
 * CarCommandRouter from three different threads. A write longer than PIPE_BUF (4096)
 * is not atomic on a pipe, so two concurrent commands could interleave and the shell
 * would run a mash-up of them. `ShellExec.execShell` therefore performs write+flush
 * while holding the stream's own monitor — these tests assert the monitor really is
 * held (not just that some lock exists somewhere), and that a command still goes out
 * as exactly one newline-terminated write.
 */
class ShellExecWriteTest {

    /** A shell stdin that parks inside write() so the monitor state is observable. */
    private class BlockingShellInput : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger(0)
        val flushes = AtomicInteger(0)
        val payload = StringBuilder()

        override fun write(b: Int) {
            throw AssertionError("execShell must use the bulk write, not one byte at a time")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writes.incrementAndGet()
            payload.append(String(b, off, len))
            entered.countDown()
            // Bounded: a failing assertion elsewhere in the test must not leave this
            // thread parked forever and keep the forked test JVM from exiting.
            release.await(5, TimeUnit.SECONDS)
        }

        override fun flush() { flushes.incrementAndGet() }
    }

    @Test
    fun writeAndFlushHappenWhileHoldingTheStreamsMonitor() {
        val input = BlockingShellInput()
        val cmd = "input keyevent 224"

        Thread({ ShellExec.execShell(input, cmd) }, "writer").also { it.isDaemon = true }.start()
        assertTrue("writer never reached write()", input.entered.await(5, TimeUnit.SECONDS))

        // A second writer must not be able to enter the monitor while the first is
        // inside write(). This is the property that stops two commands from
        // interleaving on the pipe.
        val acquired = AtomicBoolean(false)
        Thread({ synchronized(input) { acquired.set(true) } }, "second-writer").also { it.isDaemon = true }.start()

        // Poll rather than sleep: as long as the first writer holds the monitor the
        // second one cannot possibly be inside it.
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline) {
            if (acquired.get()) break
            Thread.sleep(10)
        }
        assertFalse(
            "a concurrent command got into write() — writes are not serialized on the stream",
            acquired.get()
        )

        input.release.countDown()
        Thread.sleep(50)
        assertTrue("monitor must be free again once the write finished", acquired.get())

        assertEquals(1, input.writes.get())
        assertEquals(1, input.flushes.get())
        assertEquals("$cmd\n", input.payload.toString())
    }

    @Test
    fun concurrentWritesNeverInterleaveIntoASplitLine() {
        // Framing contract: every command must land as exactly one write containing a
        // single, newline-terminated line. That is what the lock guarantees at the pipe
        // boundary (a write longer than PIPE_BUF is not atomic there) — here it is pinned
        // from the caller side, while writeAndFlushHappenWhileHoldingTheStreamsMonitor
        // pins the lock itself.
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val input = object : OutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                observed += String(b, off, len)
            }
            override fun write(b: Int) {}
        }
        val threads = (0 until 8).map { i ->
            Thread({
                repeat(50) { ShellExec.execShell(input, "settings put system key_$i value_$it") }
            }, "writer-$i")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals(8 * 50, observed.size)
        observed.forEach { line ->
            assertTrue(
                "a command was split across writes: '$line'",
                line.endsWith("\n") && line.count { it == '\n' } == 1
            )
            assertTrue("unexpected command framing: '$line'", line.startsWith("settings put system key_"))
        }
    }

    @Test
    fun aFailureToWriteIsReportedNotSwallowed() {
        // A write error must reach the log together with the offending command — that is
        // what lets an operator tell "the shell died" from "the command was bogus".
        val input = object : OutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int): Unit = throw java.io.IOException("pipe closed")
            override fun write(b: Int): Unit = throw java.io.IOException("pipe closed")
        }
        val originalErr = System.err
        try {
            val capture = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(capture, true))
            // Must not propagate — the VD server must survive a dead shell.
            ShellExec.execShell(input, "am start --display 0 -a android.intent.action.MAIN")
            val logged = capture.toString()
            assertTrue(
                "write failure must be logged with the command, got: $logged",
                logged.contains("am start --display 0")
            )
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun aMissingShellIsANoOpAndDoesNotThrow() {
        // `PersistentShell.input` is null until start() succeeds; every caller tolerates
        // the silent no-op, including on the teardown path.
        ShellExec.execShell(null, "settings put system screen_off_timeout 60000")
    }
}
