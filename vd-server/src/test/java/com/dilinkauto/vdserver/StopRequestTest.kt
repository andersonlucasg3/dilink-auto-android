package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VdDeploy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [consumeStopRequest] / 启动清场契约测试（停止哨兵协议见
 * [com.dilinkauto.protocol.VdDeploy.gracefulStopCommand] 与其类头）。
 *
 * 用临时目录注入路径：vd-server 的单测跑纯 JVM，不能碰 /sdcard。
 *
 * issue #180（第三版）：哨兵按**会话**分文件。
 *  1. **会话专属名**（主）：`stop-request-<token>`，token 由部署方启动时生成并记住。
 *     引擎只读自己那份，上一轮会话的 token 落在别的文件名上，天然无效——这正是
 *     "内容寻址也挡不住晚读"的修法。
 *  2. **旧格式固定名**（回退）：车机 ADB 部署路径的引擎拿不到 token，手机端收尾只
 *     能写它；token 模式**只受理内容等于本进程 pid 的那一种**，见
 *     [isLegacyStopRequestForTokenSession]。
 *  3. 完全没有 token 的引擎（老客户端/老脚本/e2e）：内容带 pid 按内容寻址，
 *     否则按 mtime + 启动时刻判定。
 */
class StopRequestTest {

    private val dir: File = Files.createTempDirectory("stop-request-test").toFile()
    private val path: String = File(dir, "stop-request").absolutePath
    private val legacyPath: String = path

    private fun tokenPath(token: String): String = File(dir, VdDeploy.stopRequestName(token)).absolutePath

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
        assertTrue(
            "残留只忽略、不删除：删除会与写入方的 temp+mv 抢同一路径（评审 3）",
            f.exists()
        )
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
        assertTrue("旧格式残留同样只忽略、不删", f.exists())
    }

    /**
     * 评审第 3 条：旧格式 TOCTOU。残留只忽略不删 → 写入方随后 `mv` 进来的**真请求**
     * 不会在我们判定残留的瞬间被抹掉，下一轮轮询就能正常受理。
     */
    @Test
    fun `a fresh legacy request written after a stale one is still consumed`() {
        val f = createFile()
        val sessionStart = System.currentTimeMillis()
        f.setLastModified(sessionStart - 60_000)
        assertFalse(consumeStopRequest(path, sessionStart, SELF_PID))
        assertTrue("前置：残留仍在盘上", f.exists())

        // 写入方原子替换（temp + mv = 内容/时间戳都换新）
        f.writeText("")

        assertTrue("刚写下的真请求必须受理", consumeStopRequest(path, sessionStart, SELF_PID))
        assertFalse(f.exists())
    }

    // ── 会话专属哨兵（issue #180 第三版主修复） ──────────────────────────────

    /** ② 本会话正常停止生效：写了 `stop-request-<本会话 token>` 就必须停。 */
    @Test
    fun `own session token sentinel stops the session regardless of mtime`() {
        val f = File(tokenPath(SELF_TOKEN)).apply { writeText("") }
        val sessionStart = System.currentTimeMillis()
        f.setLastModified(sessionStart - 60_000)

        assertTrue(
            "会话专属文件只看文件名归属，mtime 不参与判定",
            consumeStopRequest(tokenPath(SELF_TOKEN), sessionStart, SELF_PID, SELF_TOKEN)
        )
        assertFalse("受理后消费掉", f.exists())
    }

    /**
     * ① 旧会话遗留哨兵挡不住新会话：上一轮会话（token 旧、pid 旧）在新引擎**发布标识
     * 之后**写入也不终止它。
     */
    @Test
    fun `stale previous session sentinel cannot stop the new session`() {
        val newSessionStart = System.currentTimeMillis()
        // 新引擎发布自己的 pid + token（模拟 run() 第一行的 publishSessionFiles）
        File(File(dir, "vd-server.pid").absolutePath).writeText(SELF_PID)
        File(File(dir, "vd-server.session").absolutePath).writeText(SELF_TOKEN)

        // 旧会话收尾：写它自己那份哨兵（token 旧），而且此刻才落盘
        val staleOldTokenSentinel = File(tokenPath(OLD_TOKEN)).apply { writeText(SELF_PID) }
        staleOldTokenSentinel.setLastModified(System.currentTimeMillis())

        // 旧格式写入方晚读 pid 文件、把**新 pid** 抄进固定名哨兵（这正是内容寻址失效的场景）
        val staleLegacy = File(legacyPath).apply { writeText(SELF_PID) }

        assertFalse(
            "旧 token 的哨兵不是本会话的文件",
            consumeStopRequest(tokenPath(SELF_TOKEN), newSessionStart, SELF_PID, SELF_TOKEN)
        )
        assertFalse(
            "旧格式固定名不是本会话的文件（token 模式不读它）",
            consumeStopRequest(tokenPath(SELF_TOKEN), newSessionStart, SELF_PID, SELF_TOKEN)
        )
        assertTrue("旧 token 的文件留给它自己的引擎/启动清场处理", staleOldTokenSentinel.exists())
        assertTrue("固定名残留同样不由本会话删除", staleLegacy.exists())
    }

    /** 旧格式的 pid 内容也不能让 `stop-request` 固定名被 token 会话受理。 */
    @Test
    fun `legacy pid addressed sentinel is not read by a token session`() {
        // 固定名哨兵写的就是本进程 pid（旧会话晚读新 pid 的现场场景），token 会话也不读它。
        val legacy = File(legacyPath).apply { writeText(SELF_PID) }
        assertFalse(
            "token 会话只读自己的文件",
            consumeStopRequest(tokenPath(SELF_TOKEN), System.currentTimeMillis(), SELF_PID, SELF_TOKEN)
        )
        assertTrue("固定名残留不由本会话消费/删除", legacy.exists())

        // 纯判定函数在 token 模式下返回 true：调用方传进来的路径已由 token 命名
        // （`VdDeploy.stopRequestPath(sessionToken)`），文件名本身就是归属。
        assertTrue(
            "会话专属文件即属本会话",
            isStopRequestForSession(SELF_PID, 0L, 0L, SELF_PID, SELF_TOKEN)
        )
        assertTrue(
            "旧格式（无 token）仍按内容寻址受理",
            isStopRequestForSession(SELF_PID, System.currentTimeMillis(), 0L, SELF_PID, "")
        )
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

    /**
     * token 模式下清场**不删**自己的哨兵：写入方必须持有本会话 token（即本次部署的
     * 客户端），那是初始化窗口里的真停止；旧格式固定名则一律清掉（本会话不读它）。
     */
    @Test
    fun `startup cleanup in token mode keeps its own sentinel and ignores the legacy one`() {
        assertFalse(
            "会话专属哨兵必须保留（真停止不能在建屏期被吞掉）",
            shouldDropStartupStopRequest(null, SELF_PID, SELF_TOKEN)
        )
        assertFalse(shouldDropStartupStopRequest(SELF_PID, SELF_PID, SELF_TOKEN))
    }

    /**
     * 评审第 4 条：真调用点的启动顺序可断言 —— 清场 → 武装 → 建屏。
     *
     * [PipelineServer.run] 就是通过 [runStartupSequence] 走这三步；顺序换掉会让
     * "已清场、未武装"的窗口重新出现（旧会话哨兵落进去 = 新引擎秒退）。
     */
    @Test
    fun `startup sequence is cleanup then arm then create display`() {
        val calls = mutableListOf<String>()

        val created = runStartupSequence(
            clearStartupStopRequest = { calls += "clear" },
            armWatchdog = { calls += "arm" },
            createVirtualDisplay = {
                calls += "createVd"
                true
            }
        )

        assertTrue(created)
        assertEquals(
            "顺序契约：先清场、再武装、最后建屏，不能交换",
            listOf("clear", "arm", "createVd"),
            calls
        )
    }

    /** 建屏失败必须原样传出去（run() 依据它中止会话），且不影响前面的两步。 */
    @Test
    fun `startup sequence propagates a failed display creation`() {
        val calls = mutableListOf<String>()

        val created = runStartupSequence(
            clearStartupStopRequest = { calls += "clear" },
            armWatchdog = { calls += "arm" },
            createVirtualDisplay = {
                calls += "createVd"
                false
            }
        )

        assertFalse(created)
        assertEquals(listOf("clear", "arm", "createVd"), calls)
    }

    // ── 原子写（评审第 1、2 条） ──────────────────────────────────────────────

    @Test
    fun `atomic write replaces an existing file without leaving partial content`() {
        val target = File(dir, "vd-server.pid").absolutePath
        assertTrue(writeTextAtomically(target, "1111"))
        assertTrue(writeTextAtomically(target, "2222"))

        assertEquals("2222", File(target).readText())
        assertEquals(
            "不得留下 temp 文件（同目录扫描会被当成残留）",
            0,
            dir.listFiles()!!.count { it.name.contains(".tmp") }
        )
    }

    @Test
    fun `session files are removed only when they still belong to this session`() {
        val pidPath = File(dir, "vd-server.pid").absolutePath
        writeTextAtomically(pidPath, "1234")
        assertFalse("内容不是自己 → 不删（可能已是下一代引擎的标识）", deleteIfContentMatches(pidPath, "9999"))
        assertTrue(File(pidPath).exists())
        assertTrue(deleteIfContentMatches(pidPath, "1234"))
        assertFalse(File(pidPath).exists())
    }

    // ── 车机 ADB 部署路径的兜底（第四轮复审阻塞项） ────────────────────────────

    /**
     * 车机 ADB 路径：引擎是**车机**启的，手机端没有 token，收尾只能写固定名
     * （`gracefulStopCommand(null)` 把 `vd-server.pid` 的内容抄进哨兵）。
     * token 引擎必须受理这条请求，否则只能等 12s 超时 `-9`、cleanup 整段跳过。
     */
    @Test
    fun `token session accepts the legacy sentinel addressed to its own pid`() {
        val legacy = File(legacyPath).apply { writeText(SELF_PID) }
        val sessionStart = System.currentTimeMillis()

        // 主通道（会话专属文件）不存在
        assertFalse(consumeStopRequest(tokenPath(SELF_TOKEN), sessionStart, SELF_PID, SELF_TOKEN))
        assertTrue(isLegacyStopRequestForTokenSession(SELF_PID, SELF_PID))
        assertTrue(
            "车机部署路径的停止必须被受理",
            consumeLegacyFallbackStopRequest(legacyPath, SELF_PID)
        )
        assertFalse("受理后消费掉", legacy.exists())
    }

    /** 老客户端写的**空**哨兵晚到时 mtime 是新的：token 会话不得受理，否则 #180 回归。 */
    @Test
    fun `token session ignores an empty legacy sentinel even with a fresh mtime`() {
        val legacy = File(legacyPath).apply { writeText("") }

        assertFalse(isLegacyStopRequestForTokenSession("", SELF_PID))
        assertFalse(
            "空内容 + 新 mtime 不能让 token 会话退出（那正是 #180 的旧路径）",
            consumeLegacyFallbackStopRequest(legacyPath, SELF_PID)
        )
        assertTrue("不匹配的请求保留（可能属于别的引擎）", legacy.exists())
    }

    @Test
    fun `token session ignores a legacy sentinel addressed to another pid`() {
        val legacy = File(legacyPath).apply { writeText(OLD_PID) }

        assertFalse(isLegacyStopRequestForTokenSession(OLD_PID, SELF_PID))
        assertFalse(consumeLegacyFallbackStopRequest(legacyPath, SELF_PID))
        assertTrue(legacy.exists())
    }

    /**
     * 端到端（引擎侧）：token 会话的 watchdog 在**一次轮询内**消费车机路径写下的
     * 固定名哨兵 → `onStopRequested`（= `running=false`，随后 `run()` finally 跑
     * 完整 `cleanup()`），且不走强杀兜底。
     */
    @Test
    fun `token session watchdog consumes the car-path legacy stop without forcing cleanup`() {
        val stopLatch = CountDownLatch(1)
        val cleanedUp = AtomicBoolean(false)
        val forced = AtomicBoolean(false)
        val watchdog = ProcessWatchdog(
            isRunning = { !cleanedUp.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {
                cleanedUp.set(true)
                stopLatch.countDown()
            },
            onForceCleanup = { forced.set(true) },
            sessionToken = SELF_TOKEN,
            stopRequestPath = tokenPath(SELF_TOKEN),
            legacyStopRequestPath = legacyPath,
            sessionStartedAtMs = System.currentTimeMillis(),
            sessionPid = SELF_PID,
            gracePeriodMs = 200,
            pollIntervalMs = 20,
            cleanupCheckIntervalMs = 10,
            log = {},
            err = {},
            exitProcess = {},
            sleep = { Thread.sleep(it) }
        )
        val thread = Thread { watchdog.runLoop() }.apply { isDaemon = true; start() }
        try {
            // 车机部署的引擎：手机端收尾写固定名 + 抄本进程 pid（= 当前引擎 pid）
            File(legacyPath).writeText(SELF_PID)

            assertTrue(
                "必须在一次轮询内被消费（远小于 12s 的优雅退出预算）",
                stopLatch.await(2, TimeUnit.SECONDS)
            )
            assertFalse("不得走 -9 兜底（那会跳过 cleanup）", forced.get())
            assertFalse("哨兵已消费", File(legacyPath).exists())
        } finally {
            thread.interrupt()
        }
    }

    /** 启动清场的删除集合不碰并发写入的临时文件（第四轮复审非阻塞项 1）。 */
    @Test
    fun `other session sentinel scan excludes temp files and the own file`() {
        assertTrue(isOtherSessionSentinelName("stop-request-oldToken", "stop-request-self"))
        assertFalse("本会话自己的文件不能删", isOtherSessionSentinelName("stop-request-self", "stop-request-self"))
        assertFalse("固定名不是「别人会话的」", isOtherSessionSentinelName("stop-request", "stop-request-self"))
        assertFalse(
            "写入中的 temp 文件（temp + mv）不能被清场删掉",
            isOtherSessionSentinelName("stop-request-oldToken.tmp.4321", "stop-request-self")
        )
    }

    private companion object {
        /** 本会话 pid 的注入值（测试不依赖真实进程）。 */
        const val SELF_PID = "4321"

        /** 上一轮会话 pid 的注入值。 */
        const val OLD_PID = "9876"

        /** 本会话 token / 上一轮会话 token 的注入值。 */
        const val SELF_TOKEN = "selfToken0123"
        const val OLD_TOKEN = "oldToken4567"
    }
}
