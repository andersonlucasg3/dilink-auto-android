package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the command-line shape produced by [VdDeploy.commandLine] for the
 * two deploy flavors. The phone (Shizuku) path MUST background with `setsid`
 * so the engine survives the parent sh's exit; the car (ADB) path MUST use
 * `exec` so the engine stays attached to the ADB stream. Both MUST append
 * (>>) so a reconnect storm does not erase the first failure's diagnostic
 * output — regressing to `>` produced a 0-byte log on the phone path.
 *
 * 两条路径的路径参数都必须被 shell 引用住（WIN-09）：`jarPath` 取自对端握手响应。
 */
class VdDeployCommandLineTest {

    private val jar = "/sdcard/DiLinkAuto/vd-server.jar"
    private val log = "/sdcard/DiLinkAuto/vd-server.log"
    private val args = "2990 1640 400 127.0.0.1 1204 660 20 2000000"

    @Test
    fun backgroundPath_usesSetsidAndAmp_andAppends() {
        val cmd = VdDeploy.commandLine(jar, log, args, background = true)

        assertTrue(
            "background path must start with setsid so the engine detaches from the parent sh",
            cmd.startsWith("CLASSPATH='$jar' setsid app_process ")
        )
        assertTrue(
            "background path must end with ' &' to actually background",
            cmd.endsWith(" &")
        )
        assertTrue(
            "background path must append (>>) so reconnect storms do not erase prior output",
            " >>'$log' 2>&1" in cmd
        )
        assertTrue(
            "background path must NOT use exec (exec + & lets the parent sh exit and Shizuku reap the group)",
            !cmd.contains(" exec ")
        )
    }

    @Test
    fun foregroundPath_usesExecNoAmp_andAppends() {
        val cmd = VdDeploy.commandLine(jar, log, args, background = false)

        assertTrue(
            "foreground path must start with exec so app_process replaces the ADB shell",
            cmd.startsWith("CLASSPATH='$jar' exec app_process ")
        )
        assertEquals(
            "foreground path must not background with &",
            false, cmd.endsWith(" &")
        )
        assertTrue(
            "foreground path must append (>>) too",
            " >>'$log' 2>&1" in cmd
        )
    }

    @Test
    fun peerSuppliedJarPathCannotBreakOutOfTheShellWord() {
        val evil = "/sdcard/x.jar; pkill -f com.dilinkauto.vd; echo 'pwned'"

        val cmd = VdDeploy.commandLine(evil, log, args, background = true)

        assertEquals(
            "整条命令行必须只剩固定骨架，对端字符串只出现在被引用住的 CLASSPATH 词里",
            "CLASSPATH=${VdDeploy.shellQuote(evil)} setsid app_process / " +
                "${VdDeploy.MAIN_CLASS} $args >>${VdDeploy.shellQuote(log)} 2>&1 &",
            cmd,
        )
    }

    @Test
    fun shellQuote_escapesEmbeddedSingleQuotes() {
        assertEquals("'abc'", VdDeploy.shellQuote("abc"))
        assertEquals("''", VdDeploy.shellQuote(""))
        assertEquals(
            "'/sdcard/it'\\''s.jar'",
            VdDeploy.shellQuote("/sdcard/it's.jar")
        )
    }

    @Test
    fun buildDeployPlan_propagatesBackgroundFlag() {
        val bg = VdDeploy.buildDeployPlan(
            jarPath = jar, logPath = log,
            vdWidth = 2990, vdHeight = 1640, dpi = 400,
            encodeWidth = 1204, encodeHeight = 660,
            phoneHost = "127.0.0.1", fps = 20,
            background = true
        )
        val fg = VdDeploy.buildDeployPlan(
            jarPath = jar, logPath = log,
            vdWidth = 2990, vdHeight = 1640, dpi = 400,
            encodeWidth = 1204, encodeHeight = 660,
            phoneHost = "127.0.0.1", fps = 20,
            background = false
        )
        assertTrue(bg.launchCommand.contains(" setsid "))
        assertTrue(!fg.launchCommand.contains(" setsid "))
        assertTrue(fg.launchCommand.contains(" exec "))
    }
}
