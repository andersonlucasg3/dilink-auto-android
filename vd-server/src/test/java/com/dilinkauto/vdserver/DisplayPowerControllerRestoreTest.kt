package com.dilinkauto.vdserver

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DisplayPowerController 的 IME/屏幕设置恢复单测（S-M10 哨兵回落）。
 *
 * saveCurrentIme/restoreIme 走两个注入的 lambda（execShellOutput 读快照、execShell 写回），
 * 不需要 Android —— 恢复路径是"上个会话被 SIGKILL 后别把永不息屏哨兵写回去、别把读取失败
 * 标记写回去"的最后防线，此前零覆盖。
 */
class DisplayPowerControllerRestoreTest {

    /** 捕获持久 sh 的写入行（ShellExec.execShell 写的是 "cmd\n"）。 */
    private class CapturingShell : OutputStream() {
        val sb = StringBuilder()
        override fun write(b: ByteArray, off: Int, len: Int) { sb.append(String(b, off, len)) }
        override fun write(b: Int) { sb.append(b.toChar()) }
        fun lines(): List<String> = sb.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 每个测试实例独立的临时存档：默认路径在 Windows JVM 上会落到 C:\sdcard\...（跨测试污染）。 */
    private fun tempSnapshotFile(): TimeoutSnapshotFile {
        val dir = Files.createTempDirectory("dpc-snapshot").toFile()
        return TimeoutSnapshotFile(File(dir, "screen-timeout.snapshot").absolutePath)
    }

    private fun dpc(
        shell: CapturingShell,
        snap: TimeoutSnapshotFile = tempSnapshotFile(),
        dpi: (String) -> String?,
    ) = DisplayPowerController(
        shellProvider = { shell },
        execShellOutput = dpi,
        logErr = { },
        snapshotFile = snap,
    )

    @Test
    fun `sentinel screen off timeout is restored to sixty seconds not the sentinel`() {
        val shell = CapturingShell()
        val dpc = dpc(shell) { cmd ->
            when {
                cmd.contains("default_input_method") -> "com.google.android.inputmethod.latin/.LatinIME"
                cmd.contains("screen_off_timeout") -> "2147483647" // 上个会话被 kill 留下的哨兵
                cmd.contains("lift_wakeup_enabled") -> "1"
                cmd.contains("proximity_wakeup_enabled") -> "1"
                else -> null
            }
        }
        dpc.saveCurrentIme()
        dpc.restoreIme()
        val lines = shell.lines()
        assertTrue("必须回落 60s", lines.any { it == "settings put system screen_off_timeout 60000" })
        assertFalse("绝不能把哨兵写回去", lines.any { it.contains("2147483647") })
    }

    @Test
    fun `healthy ime is restored via the shared command list`() {
        val shell = CapturingShell()
        val dpc = dpc(shell) { cmd ->
            when {
                cmd.contains("default_input_method") -> "com.google.android.inputmethod.latin/.LatinIME"
                else -> "300000"
            }
        }
        dpc.saveCurrentIme()
        dpc.restoreIme()
        val lines = shell.lines()
        assertTrue(lines.any { it == "ime enable 'com.google.android.inputmethod.latin/.LatinIME'" })
        assertTrue(lines.any { it == "ime set 'com.google.android.inputmethod.latin/.LatinIME'" })
        assertTrue(lines.any { it == "settings put secure default_input_method 'com.google.android.inputmethod.latin/.LatinIME'" })
    }

    @Test
    fun `linkpc ime snapshot is not restored`() {
        val shell = CapturingShell()
        val dpc = dpc(shell) { cmd ->
            when {
                cmd.contains("default_input_method") -> "com.foo/.linkpc.IME" // 自家 linkpc 输入法
                else -> "300000"
            }
        }
        dpc.saveCurrentIme()
        dpc.restoreIme()
        val lines = shell.lines()
        assertFalse("linkpc IME 绝不能被设回默认", lines.any { it.startsWith("ime enable") || it.contains("default_input_method") })
    }

    @Test
    fun `reading failure markers are never written back`() {
        val shell = CapturingShell()
        val dpc = dpc(shell) { cmd ->
            when {
                cmd.contains("default_input_method") -> "com.baidu.input/.ImeService"
                cmd.contains("screen_off_timeout") -> "300000" // 健康的，应该写回
                cmd.contains("lift_wakeup_enabled") -> "null"       // 读取失败标记
                cmd.contains("proximity_wakeup_enabled") -> "undefined"
                else -> null
            }
        }
        dpc.saveCurrentIme()
        dpc.restoreIme()
        val lines = shell.lines()
        assertFalse("null 标记不得写回", lines.any { it.contains("lift_wakeup_enabled") })
        assertFalse("undefined 标记不得写回", lines.any { it.contains("proximity_wakeup_enabled") })
        assertTrue(lines.any { it == "settings put system screen_off_timeout 300000" })
    }

    @Test
    fun `missing snapshots fall back to safe defaults`() {
        val shell = CapturingShell()
        val dpc = dpc(shell) { null } // 全部读取失败 → saveCurrentIme 用 ?: 默认
        dpc.saveCurrentIme()
        dpc.restoreIme()
        val lines = shell.lines()
        assertTrue(lines.any { it == "settings put system screen_off_timeout 60000" })
        assertTrue(lines.any { it == "settings put system lift_wakeup_enabled 1" })
        assertTrue(lines.any { it == "settings put system proximity_wakeup_enabled 1" })
    }

    // ── 文件存档（2026-10-17 S19 真机实证的 kill 路径修正）──
    // 修复前的缺陷：快照只存内存，会话被 SIGKILL 后丢失 → 下个会话 fallback 60000，
    // 用户的 300000 被永久覆盖（vd-server.log 行 148→161→205 逐字可见）。

    private fun dpcWithFile(
        shell: CapturingShell,
        snap: TimeoutSnapshotFile,
        dpi: (String) -> String?
    ) = DisplayPowerController(
        shellProvider = { shell },
        execShellOutput = dpi,
        logErr = { },
        snapshotFile = snap,
    )

    @Test
    fun `kill-path snapshot file rescues the real original value`() {
        val dir = Files.createTempDirectory("timeout-snapshot-rescue").toFile()
        try {
            val snap = TimeoutSnapshotFile(File(dir, "screen-timeout.snapshot").absolutePath)
            snap.write("300000") // 上个会话被 kill 前留下的存档
            val shell = CapturingShell()
            val dpc = dpcWithFile(shell, snap) { cmd ->
                when {
                    cmd.contains("default_input_method") -> "com.baidu.input/.ImeService"
                    cmd.contains("screen_off_timeout") -> "2147483647" // 被 kill 留下的哨兵
                    else -> "1"
                }
            }
            dpc.saveCurrentIme()
            dpc.restoreIme()
            val lines = shell.lines()
            assertTrue(
                "应从文件存档恢复真实原值 300000（而非 fallback 60000）",
                lines.any { it == "settings put system screen_off_timeout 300000" }
            )
            assertFalse("绝不能回落 60000", lines.any { it == "settings put system screen_off_timeout 60000" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sentinel without a snapshot file still falls back to sixty seconds`() {
        val dir = Files.createTempDirectory("timeout-snapshot-absent").toFile()
        try {
            val snap = TimeoutSnapshotFile(File(dir, "screen-timeout.snapshot").absolutePath)
            val shell = CapturingShell()
            val dpc = dpcWithFile(shell, snap) { cmd ->
                when {
                    cmd.contains("default_input_method") -> "com.baidu.input/.ImeService"
                    cmd.contains("screen_off_timeout") -> "2147483647"
                    else -> "1"
                }
            }
            dpc.saveCurrentIme()
            dpc.restoreIme()
            assertTrue(
                "无存档时保持修复前语义（哨兵 → 60000）",
                shell.lines().any { it == "settings put system screen_off_timeout 60000" }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `healthy session archives the original value to the file`() {
        val dir = Files.createTempDirectory("timeout-snapshot-archive").toFile()
        try {
            val path = File(dir, "screen-timeout.snapshot").absolutePath
            val snap = TimeoutSnapshotFile(path)
            val shell = CapturingShell()
            val dpc = dpcWithFile(shell, snap) { cmd ->
                when {
                    cmd.contains("default_input_method") -> "com.baidu.input/.ImeService"
                    cmd.contains("screen_off_timeout") -> "300000"
                    else -> "1"
                }
            }
            dpc.saveCurrentIme()
            assertEquals("会话开始即落盘原值", "300000", File(path).readText().trim())
            dpc.restoreIme()
            assertTrue(
                "正常结束路径照常恢复 300000",
                shell.lines().any { it == "settings put system screen_off_timeout 300000" }
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
