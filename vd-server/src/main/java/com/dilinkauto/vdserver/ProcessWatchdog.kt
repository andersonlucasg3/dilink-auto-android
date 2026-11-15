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
    private val stopRequestPath: String = VdDeploy.STOP_REQUEST_PATH,
    /**
     * issue #180：本会话（进程）启动时刻——旧格式（空/非数字）哨兵的 mtime 回退
     * 判据。内容带 pid 的哨兵走内容寻址，不看时间。
     */
    private val sessionStartedAtMs: Long = System.currentTimeMillis(),
    /** issue #180：本进程 pid —— 停止哨兵内容寻址的标识。 */
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
            if (consumeStopRequest(stopRequestPath, sessionStartedAtMs, sessionPid)) {
                log("Stop request detected ($stopRequestPath) — graceful shutdown")
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
 * 两级判定，**内容优先**：
 *  1. 内容寻址（主）：哨兵内容是某个 pid 时，只有等于本进程 pid 才受理。写入方每次
 *     都 `rm -f; touch`，mtime 永远是新值——旧会话的停止命令晚到（落在
 *     `createVirtualDisplay`/`bindAndAccept` 期间）只有靠内容才能识别出"不是写给我的"。
 *  2. mtime + 启动时刻（回退）：内容为空/非数字（老客户端、老脚本、pid 文件写失败）
 *     时，mtime 早于本会话启动（扣 [STOP_REQUEST_MTIME_TOLERANCE_MS] 容差）判为残留；
 *     `fileMtimeMs <= 0`（读不到时间戳）按引入前的契约"存在即受理"——宁可多停一次，
 *     也不要让真停止请求被静默吞掉。
 */
internal fun isStopRequestForSession(
    content: String?,
    fileMtimeMs: Long,
    sessionStartedAtMs: Long,
    sessionPid: String
): Boolean {
    val target = stopRequestTargetPid(content)
    if (target != null) return target.toString() == sessionPid.trim()
    return fileMtimeMs <= 0L || fileMtimeMs + STOP_REQUEST_MTIME_TOLERANCE_MS >= sessionStartedAtMs
}

/**
 * 启动清场时是否丢弃该哨兵（issue #180）。
 *
 * 只有「内容写成本进程 pid」的哨兵才保留——那是初始化窗口内用户按下的真停止；
 * 其余（空/非数字/写给别的 pid）都是跨会话信号，一律丢弃。
 */
internal fun shouldDropStartupStopRequest(content: String?, sessionPid: String): Boolean =
    stopRequestTargetPid(content)?.toString() != sessionPid.trim()

/**
 * 消费停止哨兵（协议见 [VdDeploy.gracefulStopCommand]）：文件存在**且属于本会话**
 * 则删除并返回 true——删除是给外部的"已受理"信号（可选观察），删不掉也不影响
 * 停止（残留由下一次启动的清场兜底）。不存在、或是别人的请求都返回 false。
 *
 * 删除规则（issue #180 评审的 TOCTOU 项）：
 *  - 受理（本会话）：受理后立即停，此时删掉"更新一点的重复请求"也无副作用；
 *  - 残留（旧格式 + 早于本进程启动）：删前重读内容，内容变了说明写入方刚重写过
 *    （`rm -f; touch`），不再删除，留给下一轮判定；
 *  - 写给**别的 pid**：不删——目标引擎可能还在读。
 */
internal fun consumeStopRequest(
    path: String = VdDeploy.STOP_REQUEST_PATH,
    sessionStartedAtMs: Long,
    sessionPid: String
): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    val content = runCatching { f.readText() }.getOrNull()
    if (isStopRequestForSession(content, f.lastModified(), sessionStartedAtMs, sessionPid)) {
        runCatching { f.delete() }
        return true
    }
    if (stopRequestTargetPid(content) == null) {
        // 旧格式残留：重读确认没被并发重写，避免误删刚写下的真请求。
        if (runCatching { f.readText() }.getOrNull() == content) runCatching { f.delete() }
    }
    return false
}
