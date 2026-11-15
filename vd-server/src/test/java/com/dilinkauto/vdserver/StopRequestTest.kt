package com.dilinkauto.vdserver

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * [consumeStopRequest] 契约测试（停止哨兵协议见
 * [com.dilinkauto.protocol.VdDeploy.gracefulStopCommand] 与其类头）。
 *
 * 用临时目录注入路径：vd-server 的单测跑纯 JVM，不能碰 /sdcard。watchdog 的
 * 线程集成不在单测范围内——它只依赖本函数的返回值与"消费即删除"语义。
 *
 * issue #180：哨兵是**全局固定文件名**，上一轮会话收尾（`vdStopEngine` /
 * `PhoneDisplayRestorer`）的请求可能落进新引擎「已清场、watchdog 未武装」的
 * 初始化窗口，被新引擎当成自己的停止请求而秒退。因此受理前必须做**会话归属**
 * 判定：mtime 早于本进程启动（扣掉 /sdcard 的 FUSE 秒级精度容差）的一律判为残留。
 */
class StopRequestTest {

    private val dir: File = Files.createTempDirectory("stop-request-test").toFile()
    private val path: String = File(dir, "stop-request").absolutePath

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun createFile(): File = File(path).apply { writeText("") }

    @Test
    fun `absent file is not a stop request`() {
        assertFalse(consumeStopRequest(path, System.currentTimeMillis()))
    }

    @Test
    fun `existing file is consumed and deleted`() {
        val f = createFile()
        // 会话起点早于文件写入时刻 → 属于本会话
        val sessionStart = System.currentTimeMillis() - 60_000

        assertTrue("存在即受理", consumeStopRequest(path, sessionStart))
        assertFalse("受理必须消费掉文件（外部据此观察已受理）", f.exists())
        assertFalse("二次调用为空", consumeStopRequest(path, sessionStart))
    }

    @Test
    fun `stale file from previous session is not a stop request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        assertTrue("前置：把 mtime 倒拨到上一轮会话", f.setLastModified(sessionStart - 60_000))

        assertFalse("上一轮残留不得杀掉本会话（issue #180）", consumeStopRequest(path, sessionStart))
        assertFalse("残留顺手清掉，避免下一轮再被读到", f.exists())
    }

    @Test
    fun `file just older than the tolerance window is not a stop request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        assertTrue(f.setLastModified(sessionStart - STOP_REQUEST_MTIME_TOLERANCE_MS - 500))

        assertFalse("超出容差的旧 mtime 必须判为残留", consumeStopRequest(path, sessionStart))
    }

    @Test
    fun `request written after session start is consumed`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis() - 5_000
        assertTrue(f.setLastModified(sessionStart + 2_000))

        assertTrue("本会话启动之后写入的请求必须受理", consumeStopRequest(path, sessionStart))
        assertFalse(f.exists())
    }

    @Test
    fun `second precision timestamp does not hide a fresh request`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        // /sdcard 是 FUSE，touch 落盘 mtime 只有秒级精度：真实写于启动之后的文件，
        // 读出来的 mtime 可能比启动时刻早最多 999ms，必须靠容差兜住。
        assertTrue(f.setLastModified(sessionStart - 900))

        assertTrue(consumeStopRequest(path, sessionStart))
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
            assertTrue(consumeStopRequest(path, sessionStart))
        }
    }

    /** 读不到时间戳（`lastModified()` 返回 0）时保持引入前的契约：存在即受理。 */
    @Test
    fun `unknown mtime keeps the legacy presence-only contract`() {
        val sessionStart = System.currentTimeMillis()

        assertTrue(isStopRequestForSession(0L, sessionStart))
        assertFalse(isStopRequestForSession(sessionStart - 60_000, sessionStart))
        assertTrue(isStopRequestForSession(sessionStart, sessionStart))
    }
}
