package com.dilinkauto.protocol

import kotlinx.coroutines.delay

/**
 * Unified "deploy the vd-server" sequence shared by every deploy site.
 *
 * The sequence itself is: **graceful stop (stop-request 哨兵文件) → wait for the
 * old engine to exit → force-kill + re-wait on timeout → launch**.
 *
 * 两个入口共用同一条停止实现：[vdRunDeploySequence]（部署新引擎前停旧的）与
 * [vdStopEngine]（会话收尾——桌面端 `closeSession()` 在杀 adb 锚点前必须先让
 * 旧引擎退干净，否则 cleanup 被截断）。
 *
 * 为什么 grace 用文件而不是 SIGTERM：**Android ART 的 app_process 收 SIGTERM
 * 直接终止、不执行 JVM shutdown hook**（2026-10-10 MI 9 实测），而
 * `PipelineServer.cleanup()`（面板/letterbox/IME/screen_off_timeout 恢复）
 * 只在正常退出路径可靠执行——信号路径会把它整体跳过，这是"每次重连后设备
 * 状态被污染"的根因。详见 [VdDeploy] 类头"如何停止引擎"。
 *
 * Four sites previously re-implemented this: car USB/TCP ADB
 * (`VdServerDeployer.deploy`), car dev-mode TCP ADB (`deployDirect`), phone
 * Shizuku (`ConnectionService.startVdServerViaShizuku`) and desktop adb.exe
 * (`AdbDeployer.deploy`). The command strings were already single-sourced in
 * [VdDeploy], but the *orchestration* — and especially the "wait for exit"
 * convergence rule — had drifted into three different behaviours
 * (retry-once / one-shot stdout probe / two-consecutive-gone). This file is
 * the single definition; callers only supply a [VdDeployExecutor].
 */

/**
 * Result of one liveness probe of the vd-server process.
 *
 * [UNKNOWN] means "the probe could not be executed at all" (transport dead,
 * command failed) — as opposed to [GONE], which is a confirmed absence. The
 * distinction matters because transports fail in different ways and the old
 * per-site code treated "cannot probe" inconsistently.
 */
enum class VdProbeResult {
    /** Process confirmed alive. */
    ALIVE,

    /** Process confirmed gone. */
    GONE,

    /**
     * Could not probe (transport down / command failed). Callers treat this as
     * "accept the exit immediately" so deployment never stalls on a dead
     * transport — the timeout + force-kill path remains the safety net for
     * genuinely wedged engines.
     */
    UNKNOWN,
}

/**
 * Transport abstraction for the deploy sequence. Implementations wrap one
 * shell channel: car USB/TCP ADB, car dev-mode [RemoteAdbController], phone
 * Shizuku, or desktop adb.exe.
 *
 * Functions are suspend so the sequence can be driven from a coroutine;
 * implementations may block internally (the callers run them on IO threads).
 */
interface VdDeployExecutor {
    /** Run a command to completion (kill / stop / probe setup). */
    suspend fun shellSync(command: String)

    /**
     * Launch the engine. Return false when the transport reports a launch
     * failure; transports that cannot observe the launch (Shizuku
     * `execBackground` is fire-and-forget) return true.
     */
    suspend fun launch(command: String): Boolean

    /** One liveness probe of the engine. */
    suspend fun probe(): VdProbeResult
}

/** Outcome of [vdRunDeploySequence]. */
data class VdDeployOutcome(
    /** True when the graceful wait timed out and [VdDeploy.killCommandForce] was used. */
    val forcedKill: Boolean,
    /** launch() result — see [VdDeployExecutor.launch]. */
    val launched: Boolean,
    /**
     * 本次会话的 token（issue #180 第三版）。调用方必须记住它：会话收尾时用
     * [VdDeploy.gracefulStopCommand]`(sessionToken)` 停止本会话，不能回读设备状态
     * （那时设备上可能已经是下一代引擎的标识）。
     */
    val sessionToken: String = ""
)

/** Shared timing/convergence constants for the deploy sequence. */
object VdDeploySequence {
    /** Total wait budget for the old engine to exit, per attempt. */
    const val EXIT_WAIT_TIMEOUT_MS = 3_000L

    /**
     * Wait budget for the **graceful** stop (stop-request 哨兵) — longer than
     * [EXIT_WAIT_TIMEOUT_MS] because a graceful exit runs the full
     * `PipelineServer.cleanup()`.
     *
     * 2026-10-10 MI 9 实测（桌面端 + 设备侧轮询双向互证）：从哨兵落地到**进程真的
     * 消失**需要 **6.1~6.3 秒**，不是早先估计的 2~4 秒 —— 那条估计只看到了
     * `cleanup()` 内部的 shell 命令，漏掉了 pipeline 线程的有界 join、编码器/VD
     * 释放，以及手机侧 `ConnectionService` 并发清理抢同一批 display/rotation 锁的
     * 等待。当时 6 秒预算差 130ms 没等到，每次都补一记 `-9`，把 cleanup 的尾巴
     * （`screen_off_timeout` 恢复）截掉 —— 这正是"重连后手机面板不恢复"的成因。
     * 12 秒 = 实测值的约 2 倍余量。
     */
    const val GRACEFUL_EXIT_TIMEOUT_MS = 12_000L

    /** Poll interval while waiting for the old engine to exit. */
    const val EXIT_WAIT_POLL_MS = 150L

    /**
     * Consecutive GONE probes required to accept the exit. Two (not one)
     * because transports can report a transient false on a hiccup; one
     * probe would then green-light a launch that races the still-live engine.
     */
    const val EXIT_CONFIRMATIONS = 2
}

/**
 * Wait until the old engine has exited.
 *
 * Convergence rule (single definition, used by all four deploy sites):
 *  - ALIVE   → reset the gone-counter;
 *  - GONE    → gone-counter++, [confirmations] consecutive GONE accept the exit;
 *  - UNKNOWN → accept immediately (cannot probe — do not stall deployment).
 *
 * @return true when the exit is confirmed (or the probe is unavailable);
 *         false on timeout — the caller should force-kill and re-wait.
 * @param now   injectable clock (tests use a fake to keep the wait instant)
 * @param sleep injectable sleeper (tests substitute a no-op fake)
 */
suspend fun vdAwaitExit(
    executor: VdDeployExecutor,
    timeoutMs: Long = VdDeploySequence.EXIT_WAIT_TIMEOUT_MS,
    pollMs: Long = VdDeploySequence.EXIT_WAIT_POLL_MS,
    confirmations: Int = VdDeploySequence.EXIT_CONFIRMATIONS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    var goneCount = 0
    val deadline = now() + timeoutMs
    while (now() < deadline) {
        when (executor.probe()) {
            VdProbeResult.UNKNOWN -> return true
            VdProbeResult.ALIVE -> goneCount = 0
            VdProbeResult.GONE -> if (++goneCount >= confirmations) return true
        }
        sleep(pollMs)
    }
    return false
}

/**
 * 停止旧引擎：**哨兵 → 等它优雅退出 → 超时才 -9 兜底**。
 *
 * 与 [vdRunDeploySequence]（部署序列）共用同一条实现（2026-10-10 抽出）：
 * 桌面端 `closeSession()` 在杀 adb 锚点之前也要走这一步 —— **本地 adb shell
 * 进程就是设备侧引擎的存活锚点，锚点一死引擎被 adbd 回收，`cleanup()`
 * （面板/IME/screen_off_timeout 恢复）会被截断**（实测：重连/关窗后
 * `screen_off_timeout` 停在 2147483647 哨兵）。两处的收敛规则（两次连续
 * GONE 才认账）与兜底（-9）必须一致，所以只此一份。
 *
 * @return true = 优雅退出超时、用了强杀（此时 cleanup 被跳过，属可接受的兜底）
 */
suspend fun vdStopEngine(
    executor: VdDeployExecutor,
    /**
     * 本会话的 token（issue #180 第三版）。**必须**是部署时记住的那一个：停止
     * 请求要写进本会话专属的哨兵，回读设备状态只会读到下一代引擎。
     * null = 旧格式（固定名 + mtime），供没有 token 的老路径使用。
     */
    sessionToken: String? = null,
    gracefulTimeoutMs: Long = VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS,
    timeoutMs: Long = VdDeploySequence.EXIT_WAIT_TIMEOUT_MS,
    pollMs: Long = VdDeploySequence.EXIT_WAIT_POLL_MS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Boolean = stopEngineWith(
    executor,
    VdDeploy.gracefulStopCommand(sessionToken),
    gracefulTimeoutMs, timeoutMs, pollMs, now, sleep
)

/**
 * 停止**当前在跑的引擎**（token 未知）。只允许用在"新引擎还没启动"的位置——
 * 也就是 [vdRunDeploySequence] 的启动前置清场；见 [VdDeploy.gracefulStopAnyCommand]。
 */
private suspend fun vdStopAnyEngine(
    executor: VdDeployExecutor,
    gracefulTimeoutMs: Long,
    timeoutMs: Long,
    pollMs: Long,
    now: () -> Long,
    sleep: suspend (Long) -> Unit,
): Boolean = stopEngineWith(
    executor,
    VdDeploy.gracefulStopAnyCommand,
    gracefulTimeoutMs, timeoutMs, pollMs, now, sleep
)

/** [vdStopEngine] / 启动前置清场共用的收敛实现（只有停止命令的来源不同）。 */
private suspend fun stopEngineWith(
    executor: VdDeployExecutor,
    stopCommand: String,
    gracefulTimeoutMs: Long,
    timeoutMs: Long,
    pollMs: Long,
    now: () -> Long,
    sleep: suspend (Long) -> Unit,
): Boolean {
    executor.shellSync(stopCommand)
    if (vdAwaitExit(executor, gracefulTimeoutMs, pollMs, now = now, sleep = sleep)) return false
    // 引擎没有响应哨兵（卡死 / 旧版引擎）：-9 强杀兜底。强杀路径接受
    // cleanup 被跳过的代价——watchdog 与 pre-clean 的兜底另算。
    executor.shellSync(VdDeploy.killCommandForce)
    vdAwaitExit(executor, timeoutMs, pollMs, now = now, sleep = sleep)
    return true
}

/**
 * The unified deploy sequence:
 *
 *  1. [vdStopAnyEngine] — 停"当前在跑的引擎"（token 未知，读设备上的会话文件）；
 *  2. [VdDeploy.DeployPlan.launchCommand] — launch the replacement.
 *
 * 第 1 步**不能**用本次 plan 的 token：新引擎还没启动，写进它的专属哨兵只会让
 * 它启动后立刻读到自己的停止请求、整段会话不足 1 秒（issue #180 的原始症状）。
 * 会话收尾走 [vdStopEngine]`(sessionToken)`，调用方用自己记住的 token。
 *
 * 为什么不用 SIGTERM：**Android ART 的 app_process 收到 SIGTERM 直接终止，
 * 不执行 JVM shutdown hook**（2026-10-10 MI 9 实测：进程 1 秒内消失、
 * cleanup 零执行、日志无任何恢复步骤）。cleanup 只在正常退出路径
 * （`run()` 的 finally）可靠执行，所以"请引擎自己退"必须走哨兵文件而不是信号。
 *
 * Two live engines race for the same VirtualDisplay / DTA / 9638-9639 binds
 * and the loser never runs cleanup() — one leaked VD per reconnect. That is
 * why the stop half exists and why it is shared: a divergence here (see the
 * file doc) is exactly how the leak was re-introduced once before.
 */
suspend fun vdRunDeploySequence(
    plan: VdDeploy.DeployPlan,
    executor: VdDeployExecutor,
    timeoutMs: Long = VdDeploySequence.EXIT_WAIT_TIMEOUT_MS,
    gracefulTimeoutMs: Long = VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS,
    pollMs: Long = VdDeploySequence.EXIT_WAIT_POLL_MS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): VdDeployOutcome {
    val forcedKill = vdStopAnyEngine(executor, gracefulTimeoutMs, timeoutMs, pollMs, now, sleep)
    val launched = executor.launch(plan.launchCommand)
    return VdDeployOutcome(
        forcedKill = forcedKill,
        launched = launched,
        sessionToken = plan.sessionToken
    )
}
