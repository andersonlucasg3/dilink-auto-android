package com.dilinkauto.desktop.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** [DesktopLog] 单测：文件写入开关、控制台回显、格式与启动期轮转。 */
class DesktopLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fixedTime = LocalDateTime.of(2026, 10, 8, 14, 33, 1, 123_000_000)

    private fun log(
        file: File?,
        fileEnabled: Boolean = true,
        console: MutableList<String> = mutableListOf(),
    ) = DesktopLog(
        file = file,
        fileEnabled = fileEnabled,
        echoToConsole = true,
        console = { console.add(it) },
        clock = { fixedTime },
    )

    @Test
    fun writesLevelTagAndMessageToFile() {
        val file = File(temp.root, "desktop.log")
        log(file).info("session", "连接 192.168.3.206:9637 ...")

        val line = file.readLines().single()
        assertEquals("2026-10-08 14:33:01.123 [INFO] [session] 连接 192.168.3.206:9637 ...", line)
    }

    @Test
    fun appendsAcrossCallsAndCreatesParentDirectory() {
        val file = File(File(temp.root, "nested/dir"), "desktop.log")
        val logger = log(file)
        logger.info("a", "第一条")
        logger.warn("b", "第二条")
        logger.error("c", "第三条")

        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("[WARN] [b] 第二条"))
        assertTrue(lines[2].contains("[ERROR] [c] 第三条"))
    }

    @Test
    fun fileDisabled_stillEchoesToConsoleButLeavesNoFile() {
        val file = File(temp.root, "desktop.log")
        val console = mutableListOf<String>()
        log(file, fileEnabled = false, console = console).info("t", "仅控制台")

        assertFalse(file.exists())
        assertEquals(1, console.size)
        assertTrue(console.single().contains("仅控制台"))
    }

    @Test
    fun nullFile_doesNotCrash() {
        val console = mutableListOf<String>()
        log(file = null, console = console).info("t", "无文件")
        assertEquals(1, console.size)
    }

    @Test
    fun constructor_rotatesOversizedLog() {
        val file = File(temp.root, "desktop.log")
        // 造一个超过上限的历史日志
        file.writeBytes(ByteArray((DesktopLog.MAX_BYTES + 1).toInt()) { 'x'.code.toByte() })

        log(file).info("t", "轮转后写入")

        val backup = File(temp.root, "desktop.log.1")
        assertTrue("旧日志应被轮转到 .1", backup.isFile)
        assertTrue(backup.length() > DesktopLog.MAX_BYTES)
        // 轮转后的新文件只含新写入的一行
        assertEquals(1, file.readLines().size)
    }

    @Test
    fun constructor_leavesSmallLogAlone() {
        val file = File(temp.root, "desktop.log")
        file.writeText("旧日志\n")

        log(file).info("t", "追加")

        assertFalse(File(temp.root, "desktop.log.1").exists())
        assertEquals(2, file.readLines().size)
    }
}
