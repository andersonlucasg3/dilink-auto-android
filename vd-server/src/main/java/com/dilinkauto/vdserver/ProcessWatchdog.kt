package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VdDeploy
import java.io.File

/**
 * 进程监控守护：负责停止哨兵文件轮询与超时强制退出。
 *
 * Watchdog: when the lifecycle channel breaks, readLifecycleCommands sets
 * running=false and calls cleanup() in its finally block. But if the
 * pipeline thread is stuck in a native MediaCodec call (dequeueOutputBuffer
 * after stop()), the run() finally { cleanup() } never executes, and the
 * physical panel stays powered off → phone is unusable (black screen).
 *
 * This daemon polls running and cleanedUp. If running went false but
 * cleanup did not complete within GRACE_MS, it forces cleanup() directly
 * and then System.exit(1) / Runtime.halt(1) so the JVM ShutdownHook also fires. The forced
 * path restores the panel via DisplayControl reflection — same mechanism
 * restorePanel uses — and does NOT depend on Shizuku.
 *
 * It also polls for external stop requests ([consumeStopRequest]), triggering graceful shutdown.
 */
internal class ProcessWatchdog(
    private val isRunning: () -> Boolean,
    private val isCleanedUp: () -> Boolean,
    private val onStopRequested: () -> Unit,
    private val onForceCleanup: () -> Unit,
    /**
     * issue #180（第三版）：本会话 token。非空时主监听它专属的
     * `stop-request-<token>`。
     *
     * 但 token 模式**不能只读**这一份：车机 ADB 路径的引擎由车机部署，手机端拿不到
     * token，收尾只能写旧格式固定名（见 [isLegacyStopRequestForTokenSession]）。那条
     * 请求的内容是本进程 pid，必须受理，否则 12s 后 `-9`、cleanup 整段跳过。
     */
    private val sessionToken: String = "",
    private val stopRequestPath: String = VdDeploy.stopRequestPath(sessionToken),
    /** 旧格式固定名哨兵：token 模式下的兜底通道（严格限定，见 [isLegacyStopRequestForTokenSession]）。 */
    private val legacyStopRequestPath: String = VdDeploy.STOP_REQUEST_PATH,
    /**
     * issue #180：本会话（进程）启动时刻——旧格式（空/非数字）哨兵的 mtime 回退
     * 判据。会话专属哨兵不看时间。
     */
    private val sessionStartedAtMs: Long = System.currentTimeMillis(),
    /** issue #180：本进程 pid —— 旧格式哨兵的**内容寻址**标识。 */
    private val sessionPid: String = "",
    private val gracePeriodMs: Long = WATCHDOG_GRACE_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val cleanupCheckIntervalMs: Long = CLEANUP_CHECK_INTERVAL_MS,
    private val log: (String) -> Unit = { PipeLog.log(it) },
    private val err: (String) -> Unit = { PipeLog.err(it) },
    private val exitProcess: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = daemonThread("Watchdog", start = true) {
            runLoop()
        }
    }

    internal fun runLoop() {
        while (isRunning()) {
            // 停止哨兵：部署序列 / PhoneDisplayRestorer 写文件请求优雅停止。
            // 不能用信号——ART 的 app_process 收 SIGTERM 直接终止、不跑 shutdown
            // hook，cleanup 会被整体跳过（2026-10-10 MI 9 实测，见 VdDeploy 类头）。
            // 这里置 running=false 后各等待点（accept 轮询 / park 切片 / bind 重试）
            // 会在 ≤100ms 内退出，run() 走到 finally，cleanup 完整执行。
            if (consumeStopRequest(stopRequestPath, sessionStartedAtMs, sessionPid, sessionToken)) {
                log("Stop request detected ($stopRequestPath) — graceful shutdown")
                onStopRequested()
                break
            }
            // token 模式的兜底：车机 ADB 部署路径（引擎不是手机端启的，没有 token）。
            if (VdDeploy.isValidSessionToken(sessionToken) &&
                stopRequestPath != legacyStopRequestPath &&
                consumeLegacyFallbackStopRequest(legacyStopRequestPath, sessionPid)
            ) {
                log("Stop request detected ($legacyStopRequestPath, addressed to pid=$sessionPid) — graceful shutdown")
                onStopRequested()
                break
            }
            try { sleep(pollIntervalMs) } catch (_: InterruptedException) { break }
        }
        val deadline = System.currentTimeMillis() + gracePeriodMs
        while (System.currentTimeMillis() < deadline) {
            if (isCleanedUp()) return
            try { sleep(cleanupCheckIntervalMs) } catch (_: InterruptedException) { break }
        }
        if (!isCleanedUp()) {
            err("Watchdog: cleanup did not complete within ${gracePeriodMs}ms — forcing panel restore + exit")
            try { onForceCleanup() } catch (e: Exception) { err("Watchdog cleanup error: ${e.describe()}") }
            // Give cleanup's restorePanel() time to land before
            // halt() tears the process down (halt bypasses ShutdownHook).
            try { sleep(500) } catch (_: Exception) {}
            exitProcess(1)
        }
    }

    companion object {
        const val WATCHDOG_GRACE_MS = 3000L
        const val POLL_INTERVAL_MS = 500L
        const val CLEANUP_CHECK_INTERVAL_MS = 200L
    }
}

/**
 * 判定"属于本会话"时的 mtime 容差（毫秒）。
 *
 * `/sdcard` 是 FUSE 挂载，toybox `touch` 落盘的 mtime 只有**秒**级精度：真实写于
 * 本进程启动之后 0~999ms 的请求，读出来的 mtime 可能早于启动时刻。向前放 1s，
 * 既不误杀刚写下的真请求，也挡得住跨会话残留（会话间隔以秒计）。
 */
internal const val STOP_REQUEST_MTIME_TOLERANCE_MS = 1_000L

/**
 * 解析哨兵的**内容寻址**目标（issue #180）：纯数字内容 = 目标引擎 pid；其余
 * （空文件、无法解析）= `null`，走 mtime 回退。
 */
internal fun stopRequestTargetPid(content: String?): Long? {
    val t = content?.trim().orEmpty()
    if (t.isEmpty() || t.any { it !in '0'..'9' }) return null
    return t.toLongOrNull()
}

/**
 * 停止哨兵的「会话归属」判定（issue #180）。
 *
 * 三级判定，**文件名优先**：
 *  1. 会话专属文件名（主）：[VdDeploy.isValidSessionToken]`(sessionToken)` 时，
 *     读到的是 `stop-request-<token>` —— 只有持有本会话 token 的部署方会写它，
 *     所以内容与 mtime 都不参与判定。上一轮会话的 token 落在别的文件名上，压根
 *     不会被读到（这正是内容寻址挡不住的那个晚读场景）。
 *  2. 内容寻址（旧格式）：哨兵内容是某个 pid 时，只有等于本进程 pid 才受理。
 *  3. mtime + 启动时刻（旧格式）：内容为空/非数字（老客户端、老脚本、e2e）时，
 *     mtime 早于本会话启动（扣 [STOP_REQUEST_MTIME_TOLERANCE_MS] 容差）判为残留；
 *     `fileMtimeMs <= 0`（读不到时间戳）按引入前的契约"存在即受理"——宁可多停一次，
 *     也不要让真停止请求被静默吞掉。
 */
internal fun isStopRequestForSession(
    content: String?,
    fileMtimeMs: Long,
    sessionStartedAtMs: Long,
    sessionPid: String,
    sessionToken: String = ""
): Boolean {
    if (VdDeploy.isValidSessionToken(sessionToken)) return true
    val target = stopRequestTargetPid(content)
    if (target != null) return target.toString() == sessionPid.trim()
    return fileMtimeMs <= 0L || fileMtimeMs + STOP_REQUEST_MTIME_TOLERANCE_MS >= sessionStartedAtMs
}

/**
 * 启动清场时是否丢弃该哨兵（issue #180）。
 *
 * - 会话专属哨兵（token 非空）：**保留**。它的写入方必须是持有本会话 token 的
 *   部署方，那是初始化窗口（watchdog 还没武装）里用户按下的真停止。
 * - 旧格式固定名：只有「内容写成本进程 pid」的哨兵才保留；其余（空/非数字/写给
 *   别的 pid）都是跨会话信号，一律丢弃。
 */
internal fun shouldDropStartupStopRequest(
    content: String?,
    sessionPid: String,
    sessionToken: String = ""
): Boolean {
    if (VdDeploy.isValidSessionToken(sessionToken)) return false
    return stopRequestTargetPid(content)?.toString() != sessionPid.trim()
}

/**
 * 消费停止哨兵（协议见 [VdDeploy.gracefulStopCommand]）：文件存在**且属于本会话**
 * 则删除并返回 true——删除是给外部的"已受理"信号（可选观察），删不掉也不影响
 * 停止（残留由下一次启动的清场兜底）。不存在、或是别人的请求都返回 false。
 *
 * 删除规则（issue #180 评审的 TOCTOU 项）：
 *  - 受理（本会话）：受理后立即停，此时删掉"更新一点的重复请求"也无副作用；
 *  - **未受理：一律不删**（旧格式残留也一样）。原来"重读内容相同就删"的做法挡不住
 *    `rm -f; mv` 的并发写入——空文件重读仍是空串，`content == content` 恒真，
 *    于是刚写下的真请求被误删；残留由下一次启动清场兜底。
 *  - 写给**别的 pid**：更不该删——目标引擎可能还在读。
 */
internal fun consumeStopRequest(
    path: String = VdDeploy.STOP_REQUEST_PATH,
    sessionStartedAtMs: Long,
    sessionPid: String,
    sessionToken: String = ""
): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    val content = runCatching { f.readText() }.getOrNull()
    if (isStopRequestForSession(content, f.lastModified(), sessionStartedAtMs, sessionPid, sessionToken)) {
        runCatching { f.delete() }
        return true
    }
    return false
}

/**
 * token 会话是否受理**旧格式固定名**哨兵（issue #180 第四轮评审的阻塞项）。
 *
 * 约束来源：车机 ADB 部署路径里引擎是**车机**启的，手机端 `vdStartVdServerViaShizuku`
 * 没跑过、`vdSessionToken` 为空，会话收尾只能写固定名。那条命令
 * （`gracefulStopCommand(null)`）会把 `vd-server.pid` 的内容抄进哨兵 —— 也就是
 * **本进程 pid**。若 token 模式完全无视固定名，这条唯一可用的停止通道就失效：
 * 12s 超时后 `-9`，`cleanup()`（面板/letterbox/IME/`screen_off_timeout`）整段跳过。
 *
 * 但兜底必须**只看内容等于本进程 pid**：
 *  - 空内容 / 无法解析：老客户端（1.0.3 及更早）写的就是空哨兵，晚到时 mtime 是新的
 *    —— 放行它等于把 #180 重新装回去。这类老客户端本来也只会驱动老引擎（无 token），
 *    让它们走各自的 12s 兜底；
 *  - 内容是别的 pid：目标可能是上一代引擎，不能删、也不能停。
 */
internal fun isLegacyStopRequestForTokenSession(content: String?, sessionPid: String): Boolean {
    val target = stopRequestTargetPid(content) ?: return false
    return sessionPid.isNotBlank() && target.toString() == sessionPid.trim()
}

/**
 * 消费 token 会话的**旧格式兜底**哨兵：规则见 [isLegacyStopRequestForTokenSession]。
 *
 * 与 [consumeStopRequest] 一样，只删"已受理"的文件；不匹配的一律留着（可能是别的
 * 引擎的请求，或者等下一次启动清场处理）。
 */
internal fun consumeLegacyFallbackStopRequest(
    path: String = VdDeploy.STOP_REQUEST_PATH,
    sessionPid: String
): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    val content = runCatching { f.readText() }.getOrNull()
    if (!isLegacyStopRequestForTokenSession(content, sessionPid)) return false
    runCatching { f.delete() }
    return true
}

/**
 * 原子写文件：同目录 temp + rename（issue #180 评审第 1 条）。
 *
 * 直接 `writeText` 会先截断再写，并发读者可能读到空文件——对 pid/token 文件来说
 * 就等于"标识不存在"，停止方随之回落到旧判定。用 temp + `Files.move` 保证读者
 * 要么看到旧内容、要么看到完整的新内容。
 */
internal fun writeTextAtomically(path: String, content: String): Boolean = runCatching {
    val target = File(path)
    val dir = target.absoluteFile.parentFile ?: return@runCatching false
    if (!dir.exists()) dir.mkdirs()
    val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
    try {
        tmp.writeText(content)
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
        true
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}.getOrDefault(false)

/**
 * 只在文件内容等于 [expected] 时删除（issue #180）。
 *
 * 收尾时删 pid / token 文件必须这么做：下一代引擎可能已经启动并发布了自己的标识，
 * 无条件删除会把**别人的**标识抹掉。
 */
internal fun deleteIfContentMatches(path: String, expected: String): Boolean = runCatching {
    val f = File(path)
    if (!f.exists()) return@runCatching false
    if (f.readText() != expected) return@runCatching false
    f.delete()
}.getOrDefault(false)
