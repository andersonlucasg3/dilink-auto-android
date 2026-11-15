package com.dilinkauto.vdserver

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理屏 shell 兜底的判定回归（issue #23）。
 *
 * 现场证据（`vd-server.log:52-60`）：Flyme 的 `cmd display` 没有 `power-reset`，
 * 清理阶段 4 次 `Unknown command: power-reset`，引擎却没有任何失败判定 ——
 * 兜底命令是 fire-and-forget，结果只被排水线程原样打印。
 *
 * 本测试锁住三点：
 *  1. `cmd display help` 没有 `power-reset` 时不再执行该命令，并明确报错；
 *  2. 能力探测只做一次（缓存）；
 *  3. `power-reset` 返回 `Unknown command` 时不得打印成功，rc=0 时才打印。
 */
class DisplayPowerControllerFallbackTest {

    private class CapturingShell : OutputStream() {
        val sb = StringBuilder()
        override fun write(b: ByteArray, off: Int, len: Int) { sb.append(String(b, off, len)) }
        override fun write(b: Int) { sb.append(b.toChar()) }
        fun lines(): List<String> = sb.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private class Harness {
        val shell = CapturingShell()
        val errors = mutableListOf<String>()
        val outputQueries = mutableListOf<String>()
        var help = ""
        var powerResetResult = ""

        private val snapshot = File(
            Files.createTempDirectory("dpc-fallback").toFile(),
            "screen-timeout.snapshot"
        ).absolutePath

        fun dpc() = DisplayPowerController(
            shellProvider = { shell },
            execShellOutput = { cmd ->
                outputQueries += cmd
                when {
                    cmd.startsWith("cmd display help") -> help
                    cmd.contains("power-reset") -> powerResetResult
                    else -> null
                }
            },
            logErr = { errors += it },
            snapshotFile = TimeoutSnapshotFile(snapshot),
        )
    }

    /** PipeLog.log 走 stdout；捕获后用于断言「失败不得打成功」。 */
    private fun <T> captureStdout(block: () -> T): Pair<T, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        return try {
            block() to buffer.toString("UTF-8")
        } finally {
            System.setOut(original)
        }
    }

    /**
     * 走一次「可恢复」的熄屏（help 含 power-reset），让点亮链具备执行条件。
     *
     * issue #66 起，`setPhysicalDisplayPower(true)` 在本次会话没有尝试过硬熄屏时
     * 会直接短路（见 `panel restore is skipped when no deep power-off was attempted`），
     * 所以点亮链的用例必须先熄一次。
     */
    private fun Harness.deepOffFirst(): DisplayPowerController {
        val dpc = dpc()
        dpc.setPhysicalDisplayPower(false)
        return dpc
    }

    @Test
    fun `rom without power-reset never deep-offs`() {
        // 2026-11-15 魅族 20 Inf 的 `cmd display help` 真实形态：既没有 power-off，
        // 也没有 power-reset（`disable-display` 属于显示器移除，不是电源开关）。
        val h = Harness().apply {
            help = "  enable-display DISPLAY_ID\n  disable-display DISPLAY_ID\n"
        }
        h.dpc().setPhysicalDisplayPower(false)

        assertTrue(
            "必须说明为什么拒绝深熄屏：${h.errors}",
            h.errors.any { it.contains("skip deep power-off") },
        )
        assertFalse(
            "不可恢复的 ROM 不得下发熄屏命令：${h.shell.lines()}",
            h.shell.lines().any { it.contains("power-off") },
        )
    }

    @Test
    fun `unknown cmd display help is treated as unrecoverable`() {
        val h = Harness().apply { help = "" }
        h.dpc().setPhysicalDisplayPower(false)

        assertTrue("help 拿不到时也要拒绝深熄屏：${h.errors}", h.errors.any { it.contains("skip deep power-off") })
        assertFalse(
            "help 拿不到时不得下发熄屏命令：${h.shell.lines()}",
            h.shell.lines().any { it.contains("power-off") },
        )
    }

    @Test
    fun `rom with power-reset still deep-offs`() {
        val h = Harness().apply { help = "  power-reset DISPLAY_ID\n" }
        h.dpc().setPhysicalDisplayPower(false)

        assertTrue(
            "可恢复的 ROM 仍应熄屏：${h.shell.lines()}",
            h.shell.lines().any { it == "cmd display power-off 0" },
        )
    }

    @Test
    fun `panel restore is skipped when no deep power-off was attempted`() {
        val h = Harness().apply { help = "  power-reset DISPLAY_ID\n" }
        val (_, stdout) = captureStdout { h.dpc().setPhysicalDisplayPower(true) }

        assertTrue("必须说明跳过原因：$stdout", stdout.contains("skip panel restore"))
        assertFalse(
            "没熄过就不该下发点亮命令：${h.outputQueries}",
            h.outputQueries.any { it.contains("power-reset") },
        )
    }

    @Test
    fun `capability probe is cached across calls`() {
        // 熄屏方向的能力探测（issue #66 新增的闸门）同样只做一次。
        val h = Harness().apply {
            help = "  power-reset DISPLAY_ID\n"
        }
        val dpc = h.dpc()
        dpc.setPhysicalDisplayPower(false)
        dpc.setPhysicalDisplayPower(false)

        val probes = h.outputQueries.count { it.startsWith("cmd display help") }
        assertTrue("cmd display help 只应探测一次，实际 $probes 次", probes == 1)
    }

    @Test
    fun `power reset unknown command is reported as a failure`() {
        val h = Harness().apply {
            help = "  power-reset DISPLAY_ID\n  power-off DISPLAY_ID\n"
            powerResetResult = "Unknown command: power-reset\n__DILINK_RC=255"
        }
        val dpc = h.deepOffFirst()
        val (_, stdout) = captureStdout { dpc.setPhysicalDisplayPower(true) }

        assertTrue(
            "必须把 Unknown command 记为失败并带原文：${h.errors}",
            h.errors.any { it.contains("power-reset did not take effect") && it.contains("Unknown command") },
        )
        assertFalse("失败不得打印成功日志：$stdout", stdout.contains("Physical display restored"))
    }

    @Test
    fun `power reset with rc zero is logged as success`() {
        val h = Harness().apply {
            help = "  power-reset DISPLAY_ID\n"
            powerResetResult = "__DILINK_RC=0"
        }
        val dpc = h.deepOffFirst()
        val (_, stdout) = captureStdout { dpc.setPhysicalDisplayPower(true) }

        assertTrue("rc=0 必须打印成功：$stdout", stdout.contains("Physical display restored via power-reset"))
        assertFalse(
            "rc=0 不得报失败：${h.errors}",
            h.errors.any { it.contains("power-reset did not take effect") },
        )
    }

    @Test
    fun `power off still uses the persistent shell`() {
        val h = Harness().apply { help = "  power-reset DISPLAY_ID\n  power-off DISPLAY_ID\n" }
        val dpc = h.dpc()
        dpc.setPhysicalDisplayPower(false)

        assertTrue(
            "熄屏路径保持 fire-and-forget：${h.shell.lines()}",
            h.shell.lines().any { it == "cmd display power-off 0" },
        )
    }
}
