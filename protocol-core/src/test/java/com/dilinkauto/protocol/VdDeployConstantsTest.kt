package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VdDeploy 常量与命令串锁定测试。
 * （commandLine / shellQuote / buildDeployPlan 已由 VdDeployCommandLineTest 覆盖。）
 *
 * kill/stop/probe 里的 [P]ipelineServer 括号技巧"防 pkill -f 误杀包裹 shell 自身"。
 *
 * 停止协议（2026-10-10 重写）：ART 的 app_process 收 SIGTERM 直接终止、**不跑
 * shutdown hook**（MI 9 实测 cleanup 零执行），所以"请引擎优雅退出"改走哨兵
 * 文件（[VdDeploy.gracefulStopCommand] 写、引擎 watchdog 消费）——graceful 命令
 * 必须**瞬时返回**（含等待循环会让 transport 超时截断请求），等待与 -9 兜底
 * 全在 Kotlin 侧。这几条都是"改一行就静默回归"的关键契约，逐字锁死。
 *
 * 探活（同日）：`pkill -0` 在 toybox 0.8.11-android 上报 `bad -L '0'` 恒失败，
 * 两个 probe 都改用 `pgrep -f`（与 `pkill -f` 同一 toybox 源码，可用性等价）。
 */
class VdDeployConstantsTest {

    @Test
    fun `process pattern and main class are pinned`() {
        assertEquals("[P]ipelineServer", VdDeploy.PROCESS_PATTERN)
        assertEquals("com.dilinkauto.vdserver.PipelineServer", VdDeploy.MAIN_CLASS)
    }

    @Test
    fun `paths are the documented constants`() {
        assertEquals("/sdcard/DiLinkAuto/vd-server.jar", VdDeploy.JAR_PATH)
        assertEquals("/sdcard/DiLinkAuto/vd-server.log", VdDeploy.LOG_PATH)
    }

    @Test
    fun `kill commands locate with pgrep and kill by pid, never pkill -f`() {
        // 2026-10-10 MI 9 实测：`pkill -f` 会连发起它的 wrapper shell 一起杀
        // （adb 返回 137），"这次 kill 命中没有"从此不可读。强杀改用与探针同一套
        // 定位逻辑（pgrep -f），再按 pid kill。这两条断言是防止"顺手改回 pkill"的护栏。
        for (cmd in listOf(VdDeploy.killCommand, VdDeploy.killCommandForce)) {
            assertFalse("不得使用 pkill -f: $cmd", cmd.contains("pkill"))
            assertTrue("必须用 pgrep -f 定位: $cmd", cmd.contains("pgrep -f \"[P]ipelineServer\""))
            assertTrue("必须按 pid kill: $cmd", cmd.contains("kill "))
            assertTrue("没东西可杀不算失败: $cmd", cmd.endsWith("exit 0"))
        }
        assertTrue(VdDeploy.killCommandForce.contains("kill -9"))
        assertTrue(VdDeploy.killCommand.contains("kill -TERM"))
    }

    @Test
    fun `kill commands loop over the located pids and never use the bare name`() {
        val cmd = VdDeploy.killCommandForce
        assertTrue("必须遍历 pgrep 的结果: $cmd", cmd.contains("for p in \$(pgrep -f"))
        assertTrue("kill 的目标必须是循环变量: $cmd", cmd.contains("kill -9 \$p"))
        // 不能出现裸名（会匹配到包裹 shell 自身的 cmdline）
        assertFalse(cmd.contains("PipelineServer") && !cmd.contains("[P]"))
    }

    @Test
    fun `stop request path lives under the deploy dir`() {
        assertEquals("/sdcard/DiLinkAuto/stop-request", VdDeploy.STOP_REQUEST_PATH)
        assertTrue(VdDeploy.STOP_REQUEST_PATH.startsWith(VdDeploy.DIR_PATH))
    }

    @Test
    fun `graceful stop command writes the sentinel and returns immediately`() {
        assertTrue(
            "必须先 rm 清残留再 touch，保证文件属于本次请求",
            VdDeploy.gracefulStopCommand.indexOf("rm -f") < VdDeploy.gracefulStopCommand.indexOf("touch")
        )
        assertTrue(VdDeploy.gracefulStopCommand.contains("touch ${VdDeploy.STOP_REQUEST_PATH}"))
        assertTrue(VdDeploy.gracefulStopCommand.endsWith("exit 0"))
        // 瞬时契约：不得含任何等待——transport 的 shell 超时最紧只有 5s，
        // 命令内等待会被超时截断、请求半途而废。
        assertFalse(VdDeploy.gracefulStopCommand.contains("sleep"))
        assertFalse(VdDeploy.gracefulStopCommand.contains("pkill"))
    }

    /**
     * issue #180：哨兵改为**内容寻址**——命令把引擎 pid 文件的内容抄进哨兵，
     * pid 文件缺失时退化成空文件（老引擎/老脚本仍按 mtime 判定）。
     */
    @Test
    fun `graceful stop command addresses the running engine by pid with an empty fallback`() {
        assertEquals("/sdcard/DiLinkAuto/vd-server.pid", VdDeploy.PID_PATH)
        assertTrue(
            "必须把 pid 文件内容抄进哨兵: ${VdDeploy.gracefulStopCommand}",
            VdDeploy.gracefulStopCommand.contains("cat ${VdDeploy.PID_PATH} > ${VdDeploy.STOP_REQUEST_PATH}")
        )
        assertTrue(
            "pid 文件缺失时必须留空文件回退（老客户端兼容）: ${VdDeploy.gracefulStopCommand}",
            VdDeploy.gracefulStopCommand.contains("|| touch ${VdDeploy.STOP_REQUEST_PATH}")
        )
    }

    @Test
    fun `probe command prints Y and N not exit codes`() {
        assertTrue(VdDeploy.probeCommand.contains("echo Y"))
        assertTrue(VdDeploy.probeCommand.contains("echo N"))
        assertTrue(VdDeploy.probeCommand.contains("pgrep -f"))
        // toybox 0.8.11 拒收 `pkill -0`（bad -L '0'，恒 rc=1）→ 探针恒 GONE、
        // 退出等待空转。这条护栏防止任何人"顺手改回"。
        assertFalse(VdDeploy.probeCommand.contains("pkill -0"))
    }

    @Test
    fun `probe exit code command uses exit zero one and no echo`() {
        assertTrue(VdDeploy.probeExitCodeCommand.contains("exit 0"))
        assertTrue(VdDeploy.probeExitCodeCommand.contains("exit 1"))
        assertFalse(VdDeploy.probeExitCodeCommand.contains("echo"))
        assertTrue(VdDeploy.probeExitCodeCommand.contains("pgrep -f"))
        assertFalse(VdDeploy.probeExitCodeCommand.contains("pkill -0"))
    }
}
