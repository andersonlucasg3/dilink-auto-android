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
 * setPhysicalDisplayPower uses — and does NOT depend on Shizuku.
 *
 * It also polls for external stop requests ([consumeStopRequest]), triggering graceful shutdown.
 */
internal class ProcessWatchdog(
    private val isRunning: () -> Boolean,
    private val isCleanedUp: () -> Boolean,
    private val onStopRequested: () -> Unit,
    private val onForceCleanup: () -> Unit,
    private val stopRequestPath: String = VdDeploy.STOP_REQUEST_PATH,
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
            if (consumeStopRequest(stopRequestPath)) {
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
 * 消费停止哨兵（协议见 [VdDeploy.gracefulStopCommand]）：文件存在则**删除并返回
 * true**——删除是给外部的"已受理"信号（可选观察），删不掉也不影响停止（残留由
 * 下一次启动的 `run()` 开头兜底清理）。不存在则返回 false。
 *
 * Top-level 且路径可注入：vd-server 的单测在纯 JVM 上跑，用临时目录验证
 * "不存在→false / 存在→true 且被删除 / 二次调用→false"的契约；watchdog 的
 * 线程集成不在单测范围内。
 */
internal fun consumeStopRequest(path: String = VdDeploy.STOP_REQUEST_PATH): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    runCatching { f.delete() }
    return true
}
