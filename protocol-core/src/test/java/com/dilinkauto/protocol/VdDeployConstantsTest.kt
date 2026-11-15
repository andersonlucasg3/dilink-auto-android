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
        // issue #180 第三版：token 决定各自的哨兵文件名，旧格式固定名保持同名兼容。
        assertEquals("stop-request", VdDeploy.stopRequestName(null))
        assertEquals("stop-request", VdDeploy.stopRequestName("-"))
        assertEquals("/sdcard/DiLinkAuto/stop-request-AbC123", VdDeploy.stopRequestPath("AbC123"))
        assertEquals(VdDeploy.STOP_REQUEST_PATH, VdDeploy.stopRequestPath("a/b"))
        assertTrue(VdDeploy.isValidSessionToken("AbC123_-xyz"))
        assertFalse(VdDeploy.isValidSessionToken(""))
        assertFalse(VdDeploy.isValidSessionToken("-"))
        assertFalse(VdDeploy.isValidSessionToken("a".repeat(65)))
        assertFalse(VdDeploy.isValidSessionToken("a b"))
    }

    @Test
    fun `generated session tokens are unique and shell safe`() {
        val tokens = (1..64).map { VdDeploy.newSessionToken() }
        assertEquals("token 必须互不相同（旧会话哨兵才不可能命中新会话）", 64, tokens.toSet().size)
        assertTrue("token 必须落在允许的字符集内", tokens.all { VdDeploy.isValidSessionToken(it) })
    }

    @Test
    fun `graceful stop command writes the session sentinel atomically and returns immediately`() {
        val cmd = VdDeploy.gracefulStopCommand("AbC123")
        assertTrue("必须写本会话专属文件: $cmd", cmd.contains("p=${VdDeploy.stopRequestPath("AbC123")}"))
        assertTrue("必须用同目录 temp + mv 原子替换: $cmd", cmd.contains("mv -f \"\$tmp\" \"\$p\""))
        assertFalse(
            "不得直接重定向进哨兵（会让并发读者读到空半成品）: $cmd",
            cmd.contains("> ${VdDeploy.stopRequestPath("AbC123")}")
        )
        assertTrue(cmd.endsWith("exit 0"))
        // 瞬时契约：不得含任何等待——transport 的 shell 超时最紧只有 5s，
        // 命令内等待会被超时截断、请求半途而废。
        assertFalse(cmd.contains("sleep"))
        assertFalse(cmd.contains("pkill"))
    }

    /** 无 token（老客户端/老脚本）回退旧格式固定名 + 内容寻址。 */
    @Test
    fun `graceful stop command falls back to the legacy sentinel without a token`() {
        val cmd = VdDeploy.gracefulStopCommand()
        assertTrue("无 token 必须写固定名: $cmd", cmd.contains("p=${VdDeploy.STOP_REQUEST_PATH}"))
        assertEquals("/sdcard/DiLinkAuto/vd-server.pid", VdDeploy.PID_PATH)
        assertTrue(
            "必须把 pid 文件内容抄进哨兵（旧格式内容寻址）: $cmd",
            cmd.contains("cat ${VdDeploy.PID_PATH} > \"\$tmp\"")
        )
        assertFalse("不得再出现 `cat pid > 哨兵`（非原子）", cmd.contains("cat ${VdDeploy.PID_PATH} > ${VdDeploy.STOP_REQUEST_PATH}"))
    }

    /**
     * 启动前置清场用 `gracefulStopAnyCommand`：读设备上的会话文件（新引擎）或回退固定名
     * （老引擎）。它只允许用在"新引擎还没启动"的位置。
     */
    @Test
    fun `graceful stop any command reads the session file and falls back to the legacy path`() {
        val cmd = VdDeploy.gracefulStopAnyCommand
        assertTrue("必须读会话 token: $cmd", cmd.contains("head -c 64 ${VdDeploy.SESSION_TOKEN_PATH}"))
        assertTrue("token 必须做字符集校验（会拼进文件名）: $cmd", cmd.contains("*[!A-Za-z0-9_-]*"))
        assertTrue(
            "token 合法时写会话哨兵: $cmd",
            cmd.contains("p=${VdDeploy.DIR_PATH}/${VdDeploy.STOP_REQUEST_PREFIX}-\$t")
        )
        assertTrue("token 缺失时回退固定名: $cmd", cmd.contains("p=${VdDeploy.STOP_REQUEST_PATH}"))
        assertTrue("同样是原子替换: $cmd", cmd.contains("mv -f \"\$tmp\" \"\$p\""))
        assertTrue(cmd.endsWith("exit 0"))
        assertFalse(cmd.contains("sleep"))
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
