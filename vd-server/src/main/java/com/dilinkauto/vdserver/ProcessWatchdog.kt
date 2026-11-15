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
     * issue #180：本会话（进程）启动时刻。只有 mtime 不早于它（含
     * [STOP_REQUEST_MTIME_TOLERANCE_MS] 容差）的哨兵才被当成写给本会话的请求。
     */
    private val sessionStartedAtMs: Long = System.currentTimeMillis(),
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
            if (consumeStopRequest(stopRequestPath, sessionStartedAtMs)) {
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
 * 停止哨兵的「会话归属」判定（issue #180）。
 *
 * 哨兵是**全局固定文件名**（`/sdcard/DiLinkAuto/stop-request`），而上一轮会话的
 * 收尾（`vdStopEngine` / `PhoneDisplayRestorer`）可能与本次 launch 并发，请求落在
 * 新引擎「已清场、watchdog 未武装」的初始化窗口里，就会被新引擎当成自己的停止
 * 请求而秒退。因此：mtime 早于本会话启动（扣掉 [STOP_REQUEST_MTIME_TOLERANCE_MS]
 * 的 FUSE 秒级精度容差）的哨兵一律属于上一轮，不是写给本进程的。
 *
 * `fileMtimeMs <= 0`（读不到时间戳）保持引入前的契约——存在即受理：宁可多停一次，
 * 也不要让真停止请求被静默吞掉（那会退化成 12s 后的 -9 强杀、cleanup 被跳过）。
 */
internal fun isStopRequestForSession(fileMtimeMs: Long, sessionStartedAtMs: Long): Boolean =
    fileMtimeMs <= 0L || fileMtimeMs + STOP_REQUEST_MTIME_TOLERANCE_MS >= sessionStartedAtMs

/**
 * 消费停止哨兵（协议见 [VdDeploy.gracefulStopCommand]）：文件存在**且属于本会话**
 * 则删除并返回 true——删除是给外部的"已受理"信号（可选观察），删不掉也不影响
 * 停止（残留由下一次启动的清场兜底）。不存在、或是上一轮残留都返回 false；残留
 * 顺手删掉，避免它在下一轮再被读到。
 *
 * Top-level 且路径/会话起点可注入：vd-server 的单测在纯 JVM 上跑，用临时目录验证
 * "不存在→false / 本会话→true 且被删除 / 上一轮残留→false / 二次调用→false"的契约；
 * watchdog 的线程集成不在单测范围内。
 */
internal fun consumeStopRequest(
    path: String = VdDeploy.STOP_REQUEST_PATH,
    sessionStartedAtMs: Long
): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    if (!isStopRequestForSession(f.lastModified(), sessionStartedAtMs)) {
        runCatching { f.delete() }
        return false
    }
    runCatching { f.delete() }
    return true
}
