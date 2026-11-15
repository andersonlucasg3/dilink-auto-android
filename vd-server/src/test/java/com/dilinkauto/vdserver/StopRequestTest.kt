package com.dilinkauto.vdserver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * [consumeStopRequest] / 启动清场契约测试（停止哨兵协议见
 * [com.dilinkauto.protocol.VdDeploy.gracefulStopCommand] 与其类头）。
 *
 * 用临时目录注入路径：vd-server 的单测跑纯 JVM，不能碰 /sdcard。watchdog 的
 * 线程集成不在单测范围内——它只依赖本函数的返回值与"消费即删除"语义。
 *
 * issue #180：哨兵是**全局固定文件名**，上一轮会话收尾（`vdStopEngine` /
 * `PhoneDisplayRestorer`）的请求可能落进新引擎「已清场、watchdog 未武装」的
 * 初始化窗口，被新引擎当成自己的停止请求而秒退。归属判定分两级：
 *  1. **内容寻址**（主）：写入方把 [com.dilinkauto.protocol.VdDeploy.PID_PATH]
 *     的内容（目标引擎 pid）抄进哨兵，引擎只认自己的 pid；
 *  2. **mtime + 启动时刻**（回退）：哨兵为空/非数字（老客户端、老脚本）时沿用，
 *     早于本进程启动（扣 FUSE 秒级精度容差）的判为上一轮残留。
 */
class StopRequestTest {

    private val dir: File = Files.createTempDirectory("stop-request-test").toFile()
    private val path: String = File(dir, "stop-request").absolutePath

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun createFile(content: String = ""): File = File(path).apply { writeText(content) }

    @Test
    fun `absent file is not a stop request`() {
        assertFalse(consumeStopRequest(path, System.currentTimeMillis(), SELF_PID))
    }

    @Test
    fun `existing legacy file is consumed and deleted`() {
        val f = createFile()
        // 会话起点早于文件写入时刻 → 属于本会话
        val sessionStart = System.currentTimeMillis() - 60_000

        assertTrue("存在即受理", consumeStopRequest(path, sessionStart, SELF_PID))
        assertFalse("受理必须消费掉文件（外部据此观察已受理）", f.exists())
        assertFalse("二次调用为空", consumeStopRequest(path, sessionStart, SELF_PID))
    }

    @Test
    fun `stale legacy file from previous session is not a stop request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        assertTrue("前置：把 mtime 倒拨到上一轮会话", f.setLastModified(sessionStart - 60_000))

        assertFalse("上一轮残留不得杀掉本会话（issue #180）", consumeStopRequest(path, sessionStart, SELF_PID))
        assertFalse("残留顺手清掉，避免下一轮再被读到", f.exists())
    }

    @Test
    fun `legacy file just older than the tolerance window is not a stop request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        assertTrue(f.setLastModified(sessionStart - STOP_REQUEST_MTIME_TOLERANCE_MS - 500))

        assertFalse("超出容差的旧 mtime 必须判为残留", consumeStopRequest(path, sessionStart, SELF_PID))
    }

    @Test
    fun `legacy request written after session start is consumed`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis() - 5_000
        assertTrue(f.setLastModified(sessionStart + 2_000))

        assertTrue("本会话启动之后写入的请求必须受理", consumeStopRequest(path, sessionStart, SELF_PID))
        assertFalse(f.exists())
    }

    @Test
    fun `second precision timestamp does not hide a fresh legacy request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        // /sdcard 是 FUSE，touch 落盘 mtime 只有秒级精度：真实写于启动之后的文件，
        // 读出来的 mtime 可能比启动时刻早最多 999ms，必须靠容差兜住。
        assertTrue(f.setLastModified(sessionStart - 900))

        assertTrue(consumeStopRequest(path, sessionStart, SELF_PID))
    }

    /**
     * 删除失败也要返回 true：信号已"受理"，残留由引擎启动时的清场兜底。
     * Windows 上打开着的文件 delete 会失败（Unix 上 unlink 仍成功）——
     * 两种平台都必须保持"受理即 true"的契约。
     */
    @Test
    fun `consumed even when delete fails`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis() - 1_000
        FileInputStream(f).use {
            assertTrue(consumeStopRequest(path, sessionStart, SELF_PID))
        }
    }

    /** 读不到时间戳（`lastModified()` 返回 0）时，旧格式保持"存在即受理"的契约。 */
    @Test
    fun `unknown mtime keeps the legacy presence-only contract`() {
        val sessionStart = System.currentTimeMillis()

        assertTrue(isStopRequestForSession(null, 0L, sessionStart, SELF_PID))
        assertFalse(isStopRequestForSession(null, sessionStart - 60_000, sessionStart, SELF_PID))
        assertTrue(isStopRequestForSession("", sessionStart, sessionStart, SELF_PID))
    }

    // ── 内容寻址（issue #180 主修复） ─────────────────────────────────────────

    @Test
    fun `request addressed to this pid wins over an old mtime`() {
        val f = createFile(SELF_PID)
        val sessionStart = System.currentTimeMillis()
        f.setLastModified(sessionStart - 60_000)

        assertTrue(
            "内容寻址优先：写给自己 pid 的请求不受 mtime 影响",
            consumeStopRequest(path, sessionStart, SELF_PID)
        )
        assertFalse(f.exists())
    }

    @Test
    fun `request addressed to another pid is ignored and left for its target`() {
        val f = createFile("424242")
        val sessionStart = System.currentTimeMillis() - 60_000

        assertFalse(
            "写给别的 pid 的请求不属于本会话",
            consumeStopRequest(path, sessionStart, SELF_PID)
        )
        assertTrue("不得删除别人的请求（目标引擎可能还在读）", f.exists())
    }

    @Test
    fun `non numeric content falls back to the mtime rule`() {
        val f = createFile("not-a-pid")
        val sessionStart = System.currentTimeMillis()
        f.setLastModified(sessionStart - 60_000)

        assertFalse("无法解析的内容退回 mtime 判定，旧 mtime = 残留", consumeStopRequest(path, sessionStart, SELF_PID))
        assertFalse("残留顺手清掉", f.exists())
    }

    // ── 启动清场（issue #180 第二处修复） ─────────────────────────────────────

    @Test
    fun `startup cleanup drops anything not addressed to this pid`() {
        assertTrue("空内容（老客户端）= 上一轮/未知 → 清掉", shouldDropStartupStopRequest("", SELF_PID))
        assertTrue("无法解析 → 清掉", shouldDropStartupStopRequest("not-a-pid", SELF_PID))
        assertTrue("写给别的 pid → 清掉", shouldDropStartupStopRequest("424242", SELF_PID))
        assertFalse(
            "写给自己 pid 的真停止请求必须保留（初始化窗口内用户按停止）",
            shouldDropStartupStopRequest(SELF_PID, SELF_PID)
        )
    }

    @Test
    fun `watchdog is armed only after the startup cleanup`() {
        val calls = mutableListOf<String>()

        armWatchdogWithStartupCleanup(
            clearStartupStopRequest = { calls += "clear" },
            armWatchdog = { calls += "arm" }
        )

        assertEquals("顺序契约：先清场、再武装，不能交换", listOf("clear", "arm"), calls)
    }

    private companion object {
        /** 本会话 pid 的注入值（测试不依赖真实进程）。 */
        const val SELF_PID = "4321"
    }
}
