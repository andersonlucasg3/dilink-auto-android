package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.vdRunDeploySequence
import com.dilinkauto.protocol.vdStopEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * 无 Shizuku 时的 ADB 部署路径（Phase 5c）。
 *
 * 流程与车机端 `VdServerDeployer` 完全对应，只是把 `TcpAdbConnection` 换成
 * 本地 `adb.exe`：
 *   1. `adb connect <host>:<port>`
 *   2. 优雅停止旧引擎（`stop-request` 哨兵 + 等它真的退出），并**等它真的退出**
 *      ——（两个引擎抢同一个 VD/9638-9639，抢输的
 *      那个永远不会跑 cleanup()，每轮重连泄漏一个 VirtualDisplay）
 *   3. 前台启动新引擎（`exec app_process`，不 `&`）
 *
 * 第 2/3 步的编排 —— 包括"连续两次判退出才认账"的收敛规则与超时兜底强杀 ——
 * 现在由 protocol-core 的 `vdRunDeploySequence` 单点定义，与车机 USB/TCP 两条
 * 路径、手机 Shizuku 路径共用同一实现。
 *
 * 不需要往手机推 jar：`/sdcard/DiLinkAuto/vd-server.jar` 由手机侧自己落盘。
 *
 * 许可/分发提示：依赖用户机器上的 `adb.exe`（Android SDK Platform-Tools），
 * 不打进 app-image。找不到时就报"未安装 adb"，镜像主流程不受影响。
 *
 * ── 并发与可取消（audit D-M6）──
 * [deploy] 是挂起函数：序列里的等待经 kotlinx 的 `delay`（可被协程取消），
 * 而不是 `Thread.sleep`。整段再套一把 [Mutex] —— "会话中途 restart"时旧的部署
 * 协程可能还在对**共享的** [AdbRunner] 跑命令（旧协程杀 adb 进程 / 新协程已经
 * 起了自己的进程），串行化保证同一时刻只有一轮部署。
 */
class AdbDeployer(
    private val runner: AdbRunner = ProcessAdbRunner(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 部署序列里的等待 seam（audit D-M6）：生产是 [delay]（可取消），测试注入 no-op
     * 让等待瞬时完成。
     *
     * 注入的是**已声明的挂起函数引用**（如 `::delay`），而不是"类型为 suspend 的
     * lambda 字面量"：后者在这个版本的编译器后端上会让 continuation lowering 崩
     * （AddContinuationLowering 的 assertion）。
     */
    private val sleep: (suspend (Long) -> Unit) = ::delay,
) {

    /** 单部署串行化（audit D-M6）：restart 期间新旧两代不能同时跑部署序列。 */
    private val deployMutex = Mutex()

    /**
     * 最近一次 [deploy] 使用的 adb serial（`host:port`）。
     *
     * [gracefulStop] 用它把停止命令发到同一台设备上；null = 本端从未部署过引擎
     * （例如手机走了 Shizuku 路径），收尾时无事可做。
     */
    @Volatile
    private var lastSerial: String? = null

    /**
     * 最近一次 [deploy] 启动的会话 token（issue #180 第三版）。
     *
     * [gracefulStop] 必须用**这一次**的 token 写哨兵：回读设备上的 `vd-server.session`
     * 可能已经是下一代引擎的标识（"应用并重连"时新引擎先起、旧收尾后到），那样会
     * 把新会话杀掉 —— 这正是 issue #180 的原始症状。
     */
    @Volatile
    internal var lastSessionToken: String? = null
        private set

    /**
     * 部署一次 VD server。返回 false 时上层应提示用户走 Shizuku 或检查 adb 环境；
     * 但**不要**反复重试 —— 失败原因（没装 adb / 没开无线调试 / 不同网段）重试也不会变。
     *
     * @param jarPath 手机侧 vd-server.jar 的路径，取自握手响应的 `vdServerJarPath`。
     *   调用方必须先过白名单校验（见 [AdbDeploy.sanitizeJarPath]，audit D-01）。
     * @param phoneVdWidth 手机侧握手响应建议的 VirtualDisplay 宽（防 IME 裁切）；
     *   0 = 旧版手机未下发，回退视口尺寸。见 [AdbDeploy.plan]（内部会钳制到
     *   [AdbDeploy.MIN_VD_DIM]..[AdbDeploy.MAX_VD_DIM]，audit D-M4）。
     * @param phoneVdHeight 同上，高。
     */
    suspend fun deploy(
        config: DesktopConfig,
        adbPort: Int,
        jarPath: String,
        phoneVdWidth: Int = 0,
        phoneVdHeight: Int = 0,
        log: (String) -> Unit,
    ): Boolean {
        // 为什么不用惯用的 `deployMutex.withLock { ... }`：本方法要把 [sleep]
        // （挂起函数类型的值）传给同为 inline 的 `vdRunDeploySequence`——两个
        // inline suspend lambda 套在一起会撞上 Kotlin JVM-IR 后端的
        // AddContinuationLowering assertion（编译期崩，不是运行时问题）。
        // 显式 lock/unlock 语义等价（含异常与取消路径，见 finally），只是绕开那个
        // 编译器 bug。
        deployMutex.lock()
        try {
            val serial = AdbDeploy.serial(config.phoneHost, adbPort)
            log("ADB 部署：connect $serial")
            val connected = runner.run(
                AdbDeploy.connectArgs(config.phoneHost, adbPort),
                waitForExit = true,
                timeoutMs = CONNECT_TIMEOUT_MS,
                onOutput = log,
            )
            if (!connected) {
                log("ADB connect 失败 —— 请确认手机已开启「无线调试」且与 PC 同网段")
                return false
            }

            val plan = AdbDeploy.plan(config, jarPath = jarPath, phoneVdWidth = phoneVdWidth, phoneVdHeight = phoneVdHeight)
            log("ADB 部署：优雅停止旧引擎")

            // 记录 serial + 会话 token：收尾（closeSession）时的 gracefulStop 需要它们
            // —— 见本方法注释与 [lastSessionToken]。
            lastSerial = serial
            lastSessionToken = plan.sessionToken
            val executor = executorFor(serial, log)

            // 直接挂起驱动共享序列（audit D-M6）：等旧引擎退出的轮询等待走 [sleep]
            // （生产 = kotlinx 的 delay，协程取消时立刻抛 CancellationException 停下），
            // 而不是 Thread.sleep。此前这里包一层 runBlocking + Thread.sleep，部署既
            // 不可取消、restart 期间又会与新一轮部署并发跑同一套共享 runner。
            val outcome = vdRunDeploySequence(plan, executor, now = now, sleep = sleep)
            if (outcome.forcedKill) log("ADB 部署：旧引擎未按时退出，强制 kill")
            // 把最终 argv 打进日志：peer 可控字段（vdWidth/vdHeight/jarPath/adbPort）
            // 的校验结果只能从这里事后查证（audit D-01/D-M4）。
            log("ADB 部署：app_process 参数 ${plan.args}")
            log("ADB 部署：启动 VD server（设备侧日志 ${VdDeploy.LOG_PATH}）")
            if (!outcome.launched) log("ADB 部署：VD server 启动失败")
            return outcome.launched
        } finally {
            deployMutex.unlock()
        }
    }

    /**
     * 收尾：让设备侧引擎**优雅退出**（哨兵 → 等它退出 → 超时才 -9 兜底）。
     *
     * 为什么必须在 [close] 之前调用：本地 `adb shell` 进程**就是设备侧引擎的
     * 存活锚点**（见 [ProcessAdbRunner] 的硬约束）—— 直接 [close]（杀锚点）
     * 等于强杀，`PipelineServer.cleanup()` 被截断：手机留在"面板保持熄灭、
     * `screen_off_timeout` 停在 2147483647 哨兵"的状态（2026-10-10 MI 9 实测；
     * 「应用并重连」与关窗都走这条）。同理，引擎自己也要先收到"该退了"的信号
     * —— 光靠"视频口断开"它虽然最终也会退出，但这里用哨兵把等待收敛到确定时限。
     *
     * 与部署序列共用 `protocol-core` 的 [vdStopEngine]（同一套"两次连续 GONE"
     * 收敛规则与 -9 兜底）。本端没部署过引擎（Shizuku 路径）时直接返回。
     *
     * 与 [deploy] 一样拿 [deployMutex]：同一时刻只允许一轮在跑。
     *
     * @param gracefulTimeoutMs 优雅退出预算。默认与部署序列同值（12s，见常量
     *   注释里的实测数据）；等不到才 -9，代价是这次 cleanup 被跳过。
     * @param log 收尾日志（放最后是为了让调用点写成 `gracefulStop { log(...) }`）。
     * @return false = 本端没有需要停的引擎（或从未部署）；true = 已走完停止序列
     */
    suspend fun gracefulStop(
        gracefulTimeoutMs: Long = TEARDOWN_GRACEFUL_TIMEOUT_MS,
        log: (String) -> Unit,
    ): Boolean {
        val serial = lastSerial ?: return false
        // 本会话的 token（部署时记下的）；null = 老路径，回退固定名 + mtime。
        val token = lastSessionToken
        deployMutex.lock()
        try {
            log("ADB 收尾：优雅停止设备侧引擎（哨兵 ${VdDeploy.stopRequestPath(token)}）")
            val t0 = now()
            // 探针跃迁日志：这条路径上"等不到引擎退出"有两种完全不同的成因 ——
            // 引擎真的还在跑 cleanup（探针一路 ALIVE），还是探针本身没认账
            // （引擎已 GONE 但第一次 GONE 没有立刻出现）。两者的修法完全不同，
            // 所以把跃迁点记下来；正常一次收尾只有 1~2 行。
            var lastProbe: VdProbeResult? = null
            val base = executorFor(serial, log)
            val traced = object : VdDeployExecutor {
                override suspend fun shellSync(command: String) = base.shellSync(command)
                override suspend fun launch(command: String) = base.launch(command)
                override suspend fun probe(): VdProbeResult = base.probe().also { r ->
                    if (r != lastProbe) {
                        log("ADB 收尾：探针 $r @+${now() - t0}ms")
                        lastProbe = r
                    }
                }
            }
            val forcedKill = vdStopEngine(
                traced,
                sessionToken = token,
                gracefulTimeoutMs = gracefulTimeoutMs,
                now = now,
                sleep = sleep,
            )
            val elapsed = now() - t0
            if (forcedKill) {
                log("ADB 收尾：引擎未在 ${gracefulTimeoutMs}ms 内退出（实测 ${elapsed}ms），已强制 kill（cleanup 可能未跑完）")
            } else {
                log("ADB 收尾：引擎已优雅退出（耗时 ${elapsed}ms）")
            }
            return true
        } finally {
            deployMutex.unlock()
        }
    }

    /** [deploy] 与 [gracefulStop] 共用的 ADB 执行器（一个 serial 一套 shell 通道）。 */
    private fun executorFor(serial: String, log: (String) -> Unit): VdDeployExecutor =
        object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                runner.run(AdbDeploy.shellArgs(serial, command), true, SHELL_TIMEOUT_MS, log)
            }

            override suspend fun launch(command: String): Boolean = runner.run(
                AdbDeploy.shellArgs(serial, command),
                waitForExit = false,
                timeoutMs = 0,
                onOutput = log,
            )

            override suspend fun probe(): VdProbeResult {
                val alive = runner.run(
                    AdbDeploy.shellArgs(serial, VdDeploy.probeExitCodeCommand),
                    waitForExit = true,
                    timeoutMs = SHELL_TIMEOUT_MS,
                    onOutput = {},
                )
                return if (alive) VdProbeResult.ALIVE else VdProbeResult.GONE
            }
        }

    /**
     * 结束本端持有的 adb 进程（会连带关掉设备侧引擎的 shell 流 = 引擎被 adbd
     * 回收）。**调用前必须先 [gracefulStop]**，否则引擎的 cleanup 被截断。
     */
    fun close() = runner.killAll()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SHELL_TIMEOUT_MS = 5_000L

        /**
         * 收尾（关窗 / 「应用并重连」）等引擎优雅退出的预算。
         *
         * **必须 ≥ `VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`**：这是同一个操作
         * （哨兵 → 完整 cleanup → 进程退出），只是入口不同。2026-10-10 MI 9 实测
         * 6.1~6.3 秒（桌面端探针日志 + 设备侧轮询互证）；当时这里的预算是 6 秒，
         * 差 130ms 没等到 → 每轮重连都补一记 -9，把 `screen_off_timeout` 恢复截掉。
         * 现在与部署序列取同一个数（12s），不再"更紧一点"。
         *
         * 常见情况仍是 6 秒出头就返回 —— 预算只在引擎卡死时才起作用，那时多等
         * 几秒换取"不把 cleanup 截断"是划算的。
         */
        private const val TEARDOWN_GRACEFUL_TIMEOUT_MS = 12_000L
    }
}
