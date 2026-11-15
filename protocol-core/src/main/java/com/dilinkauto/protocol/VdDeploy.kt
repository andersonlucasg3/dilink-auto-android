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
 *  1. [gracefulStopCommand] — 写**本会话专属**的哨兵文件 `stop-request-<token>`
 *     （瞬时返回）；
 *  2. 引擎的 watchdog 轮询到该文件后消费它（删除）并置 `running=false`，
 *     于是所有等待点退出、`run()` 走到 finally、cleanup 完整跑完；
 *  3. 外部轮询等待进程真正退出（[vdAwaitExit]）；超时才用
 *     [killCommandForce] 兜底 —— 强杀路径接受"cleanup 被跳过"的代价。
 *
 * ── 会话归属：per-session 哨兵名（issue #180 第三版）──
 *
 * 固定文件名 `stop-request` 挡不住"上一轮会话的收尾晚到"：内容寻址也不行 ——
 * 写入方在**停止那一刻**才去读引擎标识，读到的往往已经是新引擎（`run()` 第一行
 * 就发布 pid 文件），请求于是被投递给新会话、新引擎秒退。所以哨兵名按会话区分：
 *
 *  - 部署方在启动时生成 token（[newSessionToken]），经 argv 第 11 槽传给引擎；
 *  - 引擎只监听 [stopRequestPath]`(token)`；本会话收尾用**自己记住的 token** 写
 *    哨兵（[gracefulStopCommand]），旧会话的 token 落在别的文件名上，天然无效；
 *  - 「启动前置清场」要停的是"当前在跑的、token 未知的引擎"，读设备上的
 *    [SESSION_TOKEN_PATH]（[gracefulStopAnyCommand]）—— 那一刻新引擎还不存在，
 *    这个"晚读"是安全的；
 *  - 没有 token 的引擎（老客户端 / 老脚本 / e2e）回退固定名 + mtime：内容为空或
 *    非数字时按"mtime 不早于本会话启动"判定。
 *
 * 写入一律是"同目录 temp + `mv`"（原子替换）：`rm -f; cat > 哨兵` 会让并发读者
 * 读到空半成品，进而回落到 mtime 分支。
 *
 * 引擎启动时也会清掉旧格式残留哨兵（防上一轮未消费的信号误杀新实例）。
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

    /** 停止哨兵文件名前缀（见类头"会话归属"）。旧格式 = 无 token 时直接用这个名字。 */
    const val STOP_REQUEST_PREFIX = "stop-request"

    /** 旧格式（无 token）停止哨兵文件名。 */
    const val STOP_REQUEST_NAME = STOP_REQUEST_PREFIX

    /**
     * 旧格式停止哨兵路径。**没有 token 的引擎**（老客户端 / 老脚本 / e2e）监听它；
     * 新部署的引擎改听 [stopRequestPath]`(token)`，这个文件只剩兼容意义。
     *
     * 放在 [DIR_PATH] 下：app_process(shell UID)、adb shell、Shizuku 三个身份都有
     * 读写权限。
     */
    const val STOP_REQUEST_PATH = "$DIR_PATH/$STOP_REQUEST_NAME"

    /** 引擎 pid 文件名（issue #180）：引擎启动即写入自己的 pid。 */
    const val PID_NAME = "vd-server.pid"

    /**
     * 引擎 pid 文件路径（issue #180）：旧格式哨兵的**内容寻址**标识。
     *
     * 引擎启动时把 `Process.myPid()` 写进来；旧格式停止方把它的内容抄进
     * [STOP_REQUEST_PATH]，引擎据此区分"写给自己的"和"上一轮残留的"请求。
     * 这条判据只对**没有 token** 的会话（旧客户端）生效 —— 它挡不住"停止那一刻
     * 才去读 pid"的晚读（读到的已经是新引擎），所以新客户端改走 per-session 哨兵名。
     */
    const val PID_PATH = "$DIR_PATH/$PID_NAME"

    /** 会话 token 文件名：引擎启动时把自己的 token 写进来，供"停掉当前引擎"读。 */
    const val SESSION_TOKEN_NAME = "vd-server.session"

    /**
     * 会话 token 文件路径（issue #180 第三版）。
     *
     * 只有 [gracefulStopAnyCommand]（启动前置清场）读它：那一刻要停的是"当前在跑的
     * 引擎"、token 未知，而新引擎还没启动，所以晚读是安全的；本会话收尾一律用
     * 调用方记住的 token，绝不回读这里。
     */
    const val SESSION_TOKEN_PATH = "$DIR_PATH/$SESSION_TOKEN_NAME"

    /** token 允许的字符集：只允许能安全进文件名的字符（长度 1..64）。 */
    private val SESSION_TOKEN_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * token 是否可用来拼哨兵名。
     *
     * `-`（argv 里的"未传"哨兵）必须判为非法：否则会拼出 `stop-request--`。
     */
    fun isValidSessionToken(token: String?): Boolean {
        val t = token?.trim().orEmpty()
        return t.isNotEmpty() && t != "-" && SESSION_TOKEN_REGEX.matches(t)
    }

    /**
     * 生成本次会话的 token（部署时生成、随 argv 传给引擎）。
     *
     * UUID 去掉连字符：32 个十六进制字符，落在 [SESSION_TOKEN_REGEX] 内，
     * 且不需要时钟/随机源注入即可保证跨会话不撞名。
     */
    fun newSessionToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** 会话专属哨兵文件名；token 非法/缺失 → 旧格式固定名。 */
    fun stopRequestName(token: String? = null): String =
        if (isValidSessionToken(token)) "$STOP_REQUEST_PREFIX-${token!!.trim()}" else STOP_REQUEST_NAME

    /** 会话专属（或旧格式）哨兵文件路径。 */
    fun stopRequestPath(token: String? = null): String = "$DIR_PATH/${stopRequestName(token)}"

    /**
     * 把请求**原子地**落进哨兵：同目录 `temp` + `mv`。
     *
     * 不能写成 `rm -f 哨兵; cat > 哨兵`：并发读者会在两个命令之间看到"文件不存在"、
     * 或读到被清空后的半成品，于是回落到 mtime 分支（旧格式判定）—— 这正是评审里
     * 那条 TOCTOU。`mv` 是同目录 rename，替换是原子的，读者要么看到旧文件、要么
     * 看到完整的新文件。
     *
     * 内容仍是 [PID_PATH] 的内容（旧格式引擎还需内容寻址）；pid 文件缺失时留空文件
     * （旧格式的 mtime 回退）。命令**不做任何等待**：三条 transport 的 shell 超时
     * 最紧只有 5s，等待与 -9 兜底都在 Kotlin 侧（[vdAwaitExit]）。
     */
    private fun atomicSentinelWrite(): String =
        "tmp=\"\$p.tmp.\$\$\"; rm -f \"\$tmp\" 2>/dev/null; " +
            "(cat $PID_PATH > \"\$tmp\" 2>/dev/null) || touch \"\$tmp\" 2>/dev/null; " +
            "mv -f \"\$tmp\" \"\$p\" 2>/dev/null; exit 0"

    /**
     * 请求**指定会话**的引擎优雅停止：写它专属的哨兵文件，瞬时返回。
     *
     * @param sessionToken 本次会话启动时记住的 token。旧客户端/老脚本传 null，
     *   回退固定名 [STOP_REQUEST_PATH]（内容寻址 + mtime，行为与引入前一致）。
     */
    fun gracefulStopCommand(sessionToken: String? = null): String =
        "p=${stopRequestPath(sessionToken)}; " + atomicSentinelWrite()

    /**
     * 请求**当前在跑的任意引擎**优雅停止（启动前置清场专用）。
     *
     * 读 [SESSION_TOKEN_PATH] 拿到目标引擎的 token 后写它专属的哨兵；token 缺失/非法
     * （老引擎、老客户端部署的会话）回退固定名 [STOP_REQUEST_PATH]。
     *
     * 为什么可以"晚读"：调用点只有 `vdRunDeploySequence` 的停止半段，此时新引擎
     * 还没启动，盘上的会话文件只可能属于正在跑的那个引擎。**会话收尾不得用它** ——
     * 那会在新引擎启动后读到新 token、把新会话杀掉（issue #180 的原始症状）。
     */
    val gracefulStopAnyCommand: String
        get() = "t=\$(head -c 64 $SESSION_TOKEN_PATH 2>/dev/null); " +
            "case \"\$t\" in ''|*[!A-Za-z0-9_-]*) p=$STOP_REQUEST_PATH ;; " +
            "*) p=$DIR_PATH/$STOP_REQUEST_PREFIX-\$t ;; esac; " +
            atomicSentinelWrite()

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
     * [sessionToken] 是新会话的归属标识：随 argv 传给引擎，同时决定
     * [stopCommand] 写哪个哨兵文件。调用方必须**记住它**，会话收尾时用
     * [stopCommand] 停止（而不是回读设备状态，那会读到下一代引擎）。
     */
    data class DeployPlan(
        val args: String,
        val launchCommand: String,
        val sessionToken: String = "",
        val stopCommand: String = VdDeploy.gracefulStopCommand()
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
     * @param sessionToken 本次会话的 token（issue #180）。缺省生成一个新的；
     *                  非法值（含 `-`）按"未传"处理，引擎回退旧格式固定名。
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
        allowedPackages: Collection<String> = emptyList(),
        sessionToken: String? = null
    ): DeployPlan {
        val token = sessionToken?.trim()?.takeIf { isValidSessionToken(it) } ?: newSessionToken()
        val args = VdDeployArgs.format(
            vdWidth, vdHeight, dpi, phoneHost, encodeWidth, encodeHeight, fps, bitrate, carHost,
            allowedPackages, token
        )
        return DeployPlan(
            args = args,
            launchCommand = commandLine(jarPath, logPath, args, background),
            sessionToken = token,
            stopCommand = gracefulStopCommand(token)
        )
    }
}
