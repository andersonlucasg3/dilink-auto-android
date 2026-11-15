package com.dilinkauto.protocol

/**
 * Shared constants and command builders for deploying the vd-server
 * ([com.dilinkauto.vdserver.PipelineServer]) process.
 *
 * Three deploy sites build the same kill command + app_process line; keeping
 * them here means a change to the classpath layout, the main class, or the
 * kill flag lands in one place. The argv tail is built by [VdDeployArgs].
 *
 * ── How the engine is stopped (2026-10-10, MI 9 实测重写) ──
 *
 * **SIGTERM does NOT run the JVM shutdown hook on Android.** ART's
 * `app_process` treats a bare SIGTERM as an immediate, uncatchable terminate:
 * MI 9 实测 `kill -TERM <pid>` 后进程 1 秒内消失、`PipelineServer.cleanup()`
 * （面板电源/letterbox/IME/screen_off_timeout 恢复、VD 释放）**零执行**——
 * vd-server.log 里连 cleanup 的第一条 shell 命令都没有。历史上
 * [killCommand]/`stopCommand` 依赖的"SIGTERM 会触发 shutdown hook"是
 * **桌面 JVM 的语义，在 ART 上不成立**，这正是"每次重连后设备状态被
 * 污染"的根因（`screen_off_timeout` 永远停在哨兵值）。
 *
 * cleanup 只在**正常退出路径**（main 线程的 `run()` finally）可靠执行——
 * socket 断开、绑定超时等都走这条路。因此停止协议改为**文件哨兵**：
 *
 *  1. [gracefulStopCommand] — 写 [STOP_REQUEST_PATH]（瞬时返回）；
 *  2. 引擎的 watchdog 轮询到该文件后消费它（删除）并置 `running=false`，
 *     于是所有等待点退出、`run()` 走到 finally、cleanup 完整跑完；
 *  3. 外部轮询等待进程真正退出（[vdAwaitExit]）；超时才用
 *     [killCommandForce] 兜底 —— 强杀路径接受"cleanup 被跳过"的代价。
 *
 * 引擎启动时也会删除残留哨兵（防上一轮未消费的信号误杀新实例）。
 *
 * [killCommand]（裸 SIGTERM）**不再用于部署序列**——在 ART 上它等同于
 * SIGKILL 但少一层"明确表态"；保留常量仅供手动排查/调试。
 *
 * ── 进程定位与 `[P]ipelineServer` 方括号技巧（2026-10-10 MI 9 实测）──
 *
 * **裸名 `PipelineServer` 不能用**：命令原文会进发起它的 wrapper shell 自己的
 * cmdline（`sh -c "... pgrep -f PipelineServer ..."`），于是定位会命中自己，
 * 后续命令在同一行里被静默跳过。方括号形式（[PROCESS_PATTERN]）是"能被正则
 * 解释成引擎、但不会匹配 wrapper 里那段字面文本"的写法。
 *
 * 实测边界（重要）：
 *  - `pgrep -f "[P]ipelineServer"` —— 方括号技巧**有效**：引擎在位 rc=0、
 *    不在位 rc=1、wrapper 不自匹配。三个探针 [probeCommand]/[probeExitCodeCommand]
 *    与强杀命令都建立在这个已实测的行为上。
 *  - `pkill -f "[P]ipelineServer"` —— **会连 wrapper 一起杀**（adb 返回 137，
 *    `echo` 都没跑）。引擎也被杀掉（已复现两次），但"这次 kill 命中没有"读不出来。
 *    所以强杀改走 [killCommandForce]（pgrep 定位 + 按 pid kill），语义与探针一致。
 */
object VdDeploy {
    const val MAIN_CLASS = "com.dilinkauto.vdserver.PipelineServer"

    /**
     * 定位引擎用的**正则**（`pgrep -f` 语义）：匹配引擎的
     * `com.dilinkauto.vdserver.PipelineServer`，但**不**匹配 wrapper shell 自己
     * cmdline 里那段字面文本 `[P]ipelineServer`。类头有实测记录 —— 注意这条性质
     * 只对 `pgrep` 成立，`pkill -f` 不可依赖它（见 [killCommandForce]）。
     */
    const val PROCESS_PATTERN = "[P]ipelineServer"

    /** Directory on shared storage where the JAR and log live. */
    const val DIR_PATH = "/sdcard/DiLinkAuto"
    const val JAR_NAME = "vd-server.jar"
    const val LOG_NAME = "vd-server.log"

    val JAR_PATH get() = "$DIR_PATH/$JAR_NAME"
    val LOG_PATH get() = "$DIR_PATH/$LOG_NAME"

    /**
     * 杀引擎命令的模板：**先 `pgrep -f` 定位、再按 pid `kill`** —— 不用 `pkill -f`。
     *
     * 为什么不用 `pkill -9 -f <pattern>`（2026-10-10 MI 9 实测）：
     * 命令原文会进发起它的那个 wrapper shell 自己的 cmdline，于是 `pkill -f`
     * **连这个 wrapper 一起杀**（内联执行 `adb shell 'pkill -9 -f "[P]ipelineServer"'`
     * 时 adb 直接返回 137，`echo` 都来不及打印）。引擎**确实**也被杀掉了，但
     * "这次 kill 到底命中没有"从此读不出来 —— 兜底路径最不该有这种模糊信号。
     *
     * `pgrep -f` 的语义在本文件里已被逐条实测（方括号技巧防自匹配、引擎在位 rc=0、
     * 不在位 rc=1，见 [probeCommand]），强杀复用**同一条定位逻辑**，就不存在
     * "pgrep 与 pkill 判定不一致"的可能 —— 而那正是"等退出"与"强杀"必须对齐的地方。
     *
     * `2>/dev/null` + `exit 0`：没东西可杀不是失败。
     */
    private fun killByPid(signal: String): String =
        "for p in \$(pgrep -f \"$PROCESS_PATTERN\"); do kill $signal \$p; done 2>/dev/null; exit 0"

    /** Graceful kill (SIGTERM). 保留仅供手动排查：ART 上 SIGTERM 直接终止进程、不跑 shutdown hook，等价于强杀。 */
    val killCommand: String get() = killByPid("-TERM")

    /** Force kill (-9). The only kill signal with well-defined semantics on ART. */
    val killCommandForce: String get() = killByPid("-9")

    /** 停止哨兵文件名（见类头"如何停止引擎"）。 */
    const val STOP_REQUEST_NAME = "stop-request"

    /**
     * 停止哨兵文件路径。外部（部署序列 / PhoneDisplayRestorer）写它表示
     * "请优雅退出"；引擎的 watchdog 轮询它、消费（删除）它、然后走正常退出
     * 路径跑完整 cleanup。放在 [DIR_PATH] 下：app_process(shell UID)、
     * adb shell、Shizuku 三个身份都有读写权限。
     */
    const val STOP_REQUEST_PATH = "$DIR_PATH/$STOP_REQUEST_NAME"

    /** 引擎 pid 文件名（issue #180）：引擎启动即写入自己的 pid。 */
    const val PID_NAME = "vd-server.pid"

    /**
     * 引擎 pid 文件路径（issue #180）：停止哨兵的**内容寻址**标识。
     *
     * 引擎启动时把 `Process.myPid()` 写进来；停止方把它的内容抄进
     * [STOP_REQUEST_PATH]，引擎只受理内容等于自己 pid 的请求。这样"上一轮会话的
     * 停止命令晚到、落进新引擎的初始化窗口"就不会误杀新会话——mtime 不可靠正是
     * 因为写入方每次都 `rm -f; touch`，永远产生**新**时间戳。
     */
    const val PID_PATH = "$DIR_PATH/$PID_NAME"

    /**
     * 请求引擎优雅停止：写哨兵文件，瞬时返回。
     *
     * 命令本身**不做任何等待**（三条 transport 的 shell 超时各不相同，最紧的
     * 只有 5s）——等待由调用方用 [vdAwaitExit] 轮询，超时兜底用
     * [killCommandForce]。`rm -f` 先清残留，保证文件属于本次请求。
     *
     * 内容寻址（issue #180）：优先把 [PID_PATH] 的内容（目标引擎 pid）抄进哨兵；
     * pid 文件不存在时退化成**空文件**（`rm -f; touch` 的旧形态），引擎侧据此回退
     * 到 mtime + 启动时刻的旧判定——老客户端/老脚本因此不会被卡死。
     */
    const val gracefulStopCommand =
        "rm -f $STOP_REQUEST_PATH 2>/dev/null; " +
            "(cat $PID_PATH > $STOP_REQUEST_PATH 2>/dev/null) || touch $STOP_REQUEST_PATH 2>/dev/null; " +
            "exit 0"

    /**
     * Liveness probe, output form: prints `Y` when a vd-server process exists,
     * `N` otherwise. For callers that can read stdout (the phone's Shizuku path).
     *
     * ── `pgrep -f`, not `pkill -0` (2026-10-10, MI 9 实测) ──
     * toybox 0.8.11-android 的 `pkill` **不接受 `-0`**：`pkill -0 -f '[P]ipelineServer'`
     * 直接报 `pkill: bad -L '0'` 并以 rc=1 退出 → 探针**永远**返回 GONE，
     * `vdAwaitExit` 形同虚设（这正是"两个引擎抢同一个 VD"的历史土壤）。
     * `pgrep` 与 `pkill` 在 toybox 里是同一份源码、同一张选项表，所以凡是
     * `pkill -f`（强杀路径必需）能跑的设备，`pgrep -f` 一定可用。
     * 实测：引擎在跑 rc=0，不存在 rc=1（均带括号技巧）。
     */
    const val probeCommand =
        "if pgrep -f $PROCESS_PATTERN >/dev/null 2>&1; then echo Y; else echo N; fi"

    /**
     * Liveness probe, exit-code form: exit 0 = alive, exit 1 = gone.
     * For callers that only see an exit status (the car's ADB `shell()` path,
     * the desktop's `AdbDeployer.probe()`).
     *
     * Also `pgrep` for the same reason as [probeCommand] —— `pkill -0` 在
     * toybox 上恒报错、恒 GONE，会让"等旧引擎退出"变成空转。
     */
    const val probeExitCodeCommand =
        "if pgrep -f $PROCESS_PATTERN >/dev/null 2>&1; then exit 0; else exit 1; fi"

    /**
     * Build the full app_process command line.
     *
     * @param jarPath  CLASSPATH argument (location of vd-server.jar).
     * @param logPath  where stdout+stderr are redirected.
     * @param args     argv tail from [VdDeployArgs.format].
     * @param background when false, the line uses `exec` so app_process replaces
     *   the caller's shell — used by the car USB/TCP-ADB paths, where the ADB
     *   stream must stay attached to the engine process (the car's
     *   TcpAdbConnection has no stream-demux reader; a closing stream kills the
     *   just-started engine). When true, the line uses `setsid ... &` so
     *   app_process detaches into its own session — used by the phone Shizuku
     *   path, where `execBackground` is fire-and-forget and the engine must
     *   survive the parent sh's exit (and Shizuku's process tracking, which is
     *   tied to the parent sh PID). `setsid` is what actually detaches: with
     *   plain `exec app_process ... &`, the engine remained in Shizuku's
     *   process group and could be reaped when the parent sh exited, leaving
     *   vd-server.log at 0 bytes.
     */
    fun commandLine(jarPath: String, logPath: String, args: String, background: Boolean): String {
        val prefix = if (background) "setsid " else "exec "
        val amp = if (background) " &" else ""
        // Append (>>), not truncate (>): a reconnect storm runs pkill + restart
        // several times in quick succession, and each restart reopens the log.
        // With truncation the LAST restart's process — which often dies before its
        // first stdout flush — erases the FIRST failure's diagnostic output,
        // leaving a 0-byte log. Append preserves every run's output in order.
        //
        // 路径一律经 [shellQuote]（audit WIN-09）：`jarPath` 来自**对端**（握手响应的
        // `vdServerJarPath`），原样拼进设备侧 shell 就是把"对端可控字符串"喂给
        // `sh -c`（执行身份 shell）。三条部署路径最后都是 shell，所以单引号在这里
        // 是通用且必要的。`logPath` 目前只来自本端常量，同样加上以免日后被人接到参数上。
        return "CLASSPATH=${shellQuote(jarPath)} ${prefix}app_process / $MAIN_CLASS $args " +
            ">>${shellQuote(logPath)} 2>&1$amp"
    }

    /**
     * POSIX shell 单引号引用（audit WIN-09）。
     *
     * 单引号内除 `'` 本身外不解释任何字符，所以只需把内嵌的 `'` 拆成 `'\''`
     * （闭合 → 转义单引号 → 重新打开）。用于任何"字符串要进设备侧 shell"的位置。
     */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * A fully-assembled VD-server deploy plan: the argv tail and the launch
     * command line. Built once from viewport + DPI + fps by [buildDeployPlan]
     * and handed to whichever executor is available (car USB ADB, car TCP ADB,
     * or phone Shizuku) so the three deploy sites do not each re-assemble the
     * same sequence.
     *
     * 停止命令**不在 plan 里**：`vdRunDeploySequence` 统一用
     * [gracefulStopCommand]（哨兵文件）+ [killCommandForce]（超时兜底）——
     * 见类头"如何停止引擎"。
     */
    data class DeployPlan(
        val args: String,
        val launchCommand: String
    )

    /**
     * Build a [DeployPlan] from the viewport dimensions, phone DPI, and fps.
     *
     * @param jarPath   vd-server.jar location (caller-selected: VdDeploy.JAR_PATH
     *                  for the car paths, or a Shizuku-resolved path on the phone).
     * @param logPath   vd-server.log location.
     * @param vdWidth    VirtualDisplay width (car-native, even-aligned).
     * @param vdHeight   VirtualDisplay height (car-native, even-aligned).
     * @param dpi        DPI to use (caller-resolved: handshake echo or auto-calibrated).
     * @param encodeWidth encoder width (clamped to 1920 by VdDeployArgs).
     * @param encodeHeight encoder height (clamped to 1080 by VdDeployArgs).
     * @param fps        target frame rate.
     * @param background when true, the launch command backgrounds the server.
     * @param carHost   receiver IP the engine pins its 9638/9639 accepts to
     *                  (audit S-01). [VdDeployArgs.CAR_HOST_ANY] keeps the
     *                  legacy accept-any behaviour for deploy sites that cannot
     *                  determine their own outbound address.
     * @param allowedPackages 车机可见应用的包名集合（issue #21）。只有手机端
     *                  Shizuku 部署路径能读到 allowlist prefs 并下传；车机/桌面
     *                  ADB 路径传空集合（引擎不做过滤，行为不变）。
     */
    fun buildDeployPlan(
        jarPath: String,
        logPath: String,
        vdWidth: Int,
        vdHeight: Int,
        dpi: Int,
        encodeWidth: Int,
        encodeHeight: Int,
        phoneHost: String,
        fps: Int,
        bitrate: Int = VideoConfig.DEFAULT_BITRATE,
        background: Boolean,
        carHost: String = VdDeployArgs.CAR_HOST_ANY,
        allowedPackages: Collection<String> = emptyList()
    ): DeployPlan {
        val args = VdDeployArgs.format(
            vdWidth, vdHeight, dpi, phoneHost, encodeWidth, encodeHeight, fps, bitrate, carHost,
            allowedPackages
        )
        return DeployPlan(
            args = args,
            launchCommand = commandLine(jarPath, logPath, args, background)
        )
    }
}
