package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理屏恢复的判定回归（issue #23）。
 *
 * 现场证据（`client.log:108` + `vd-server.log:60`）：`cmd display power-reset`
 * 在 Flyme 上返回 `Unknown command: power-reset`，手机端却无条件打印
 * `Physical display restored via Shizuku`；同一会话 `wakeUp()` 反射失败也只打 D。
 * 于是三层兜底里两层是死的，日志却显示成功。
 *
 * 本测试锁住两点：
 *  1. power-reset 不可用 + wakeUp 不可用时，**Activity 兜底仍必须执行**；
 *  2. 日志里**不得**出现任何成功文案（restored / succeeded / 已点亮）。
 */
class DisplayRestoreSequenceTest {

    /** 内存日志：记录级别与文本，便于断言“没打成功”。 */
    private class RecordingLog : RestoreLog {
        val lines = mutableListOf<Pair<Char, String>>()
        override fun i(msg: String) { lines += 'I' to msg }
        override fun w(msg: String) { lines += 'W' to msg }
        override fun d(msg: String) { lines += 'D' to msg }
        fun texts(): List<String> = lines.map { it.second }
    }

    private class Harness {
        val log = RecordingLog()
        val commands = mutableListOf<String>()
        var activityLaunches = 0

        fun sequence(
            shellAnswer: (String) -> ShellOutcome? = { ShellOutcome("__DILINK_RC=0", "") },
            wakeUp: () -> Unit = {},
        ) = DisplayRestoreSequence(
            shell = { cmd ->
                commands += cmd
                shellAnswer(cmd)
            },
            wakeUpFn = wakeUp,
            launchActivityFn = { activityLaunches++ },
            wakeLockFn = {},
            log = log,
        )
    }

    private val successWords = listOf("restored", "succeeded", "已点亮")

    private fun List<String>.hasSuccessWord(): Boolean =
        any { line -> successWords.any { line.contains(it, ignoreCase = true) } }

    @Test
    fun `power reset unknown and wakeUp unavailable still launches activity without success log`() {
        val h = Harness()
        val seq = h.sequence(
            shellAnswer = { cmd ->
                when {
                    cmd.contains("power-reset") -> ShellOutcome("Unknown command: power-reset", "")
                    else -> ShellOutcome("__DILINK_RC=0", "")
                }
            },
            wakeUp = {
                throw NoSuchMethodException(
                    "android.os.PowerManager.wakeUp [long, int, class java.lang.String]"
                )
            },
        )

        val report = seq.run(null)

        assertFalse("power-reset 返回 Unknown command 时不得记为成功", report.powerResetOk)
        assertFalse("wakeUp 抛异常时不得记为成功", report.wakeUpOk)
        assertEquals("Activity 兜底必须执行", 1, h.activityLaunches)
        assertTrue(
            "wakeUp 失败后必须降级 KEYCODE_WAKEUP：${h.commands}",
            h.commands.any { it.contains("input keyevent 224") },
        )
        assertFalse(
            "失败场景不得出现任何成功文案：${h.log.texts()}",
            h.log.texts().hasSuccessWord(),
        )
        assertTrue(
            "power-reset 失败必须打 W 级日志：${h.log.lines}",
            h.log.lines.any { it.first == 'W' && it.second.contains("power-reset") },
        )
    }

    @Test
    fun `power reset success is logged and keyevent fallback is skipped`() {
        val h = Harness()
        val seq = h.sequence(shellAnswer = { ShellOutcome("__DILINK_RC=0", "") })
        val report = seq.run(null)

        assertTrue("rc=0 应判定成功", report.powerResetOk)
        assertTrue("wakeUp 成功", report.wakeUpOk)
        assertTrue(
            "wakeUp 成功时不应再发 keyevent 兜底：${h.commands}",
            h.commands.none { it.contains("input keyevent 224") },
        )
        assertTrue("成功必须打 I 级日志", h.log.texts().hasSuccessWord())
        assertEquals("Activity 兜底仍照常执行", 1, h.activityLaunches)
    }

    @Test
    fun `unknown command is a failure even when rc is zero`() {
        val h = Harness()
        val report = h.sequence(
            shellAnswer = { ShellOutcome("Unknown command: power-reset\n__DILINK_RC=0", "") },
        ).run(null)

        assertFalse("文本含 Unknown command 时，rc=0 也不能算成功", report.powerResetOk)
    }

    @Test
    fun `shizuku unavailable still falls through to the activity layer`() {
        val h = Harness()
        val report = h.sequence(
            shellAnswer = { null },
            wakeUp = { throw NoSuchMethodException("wakeUp") },
        ).run(null)

        assertFalse(report.powerResetOk)
        assertEquals("Shizuku 不可用时 Activity 兜底仍必须执行", 1, h.activityLaunches)
        assertFalse("没有任何显示层成功时不得打成功文案", h.log.texts().hasSuccessWord())
    }

    @Test
    fun `activity failure is reported and does not abort the wakelock layer`() {
        var wakeLockCalled = false
        val log = RecordingLog()
        val seq = DisplayRestoreSequence(
            shell = { ShellOutcome("__DILINK_RC=0", "") },
            wakeUpFn = {},
            launchActivityFn = { throw SecurityException("background activity launch blocked") },
            wakeLockFn = { wakeLockCalled = true },
            log = log,
        )

        val report = seq.run(null)

        assertFalse("Activity 启动抛异常时不得记为成功", report.activityLaunched)
        assertTrue("WakeLock 层必须继续执行", wakeLockCalled)
        assertTrue(report.wakeLockHeld)
    }
}
