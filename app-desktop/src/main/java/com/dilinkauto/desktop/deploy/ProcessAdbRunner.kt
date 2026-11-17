package com.dilinkauto.desktop.deploy

import com.dilinkauto.protocol.PlatformLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 真实实现：用 [ProcessBuilder] 起 `adb.exe`。
 *
 * `waitForExit = false` 的进程（VD server 的 launch）会被一直持有，直到
 * [killAll] —— 这不是资源泄漏，而是协议要求：本地 adb 进程一退出，设备侧的
 * shell 流就断，`exec app_process` 起来的引擎会被 adbd 回收。
 *
 * JVM 退出时还有一道兜底（companion 里的 shutdown hook，audit WIN-03）。
 *
 * ── 超时进程必须 reap（audit D-M7）──
 * `waitFor(timeout)` 返回 false 时旧的实现只调 `destroyForcibly()` 就返回：
 * 进程可能还在退出过程中（native handle 靠 GC finalize），三条 stdio 流也没关。
 * 现在 forcible destroy 之后补一次有界 `waitFor` 并显式关流。
 *
 * ── adb.exe 必须解析到绝对路径（audit D-M8）──
 * 按裸名 `adb` 交给 ProcessBuilder 时，Windows 会按进程的搜索顺序解析 ——
 * **当前工作目录排在最前**。任何能在启动目录写文件的进程，都能让本程序以用户
 * 权限执行它的 adb.exe（→ shell 身份的任意命令）。所以默认值绝不用裸名：
 * 优先 [System.getenv] 的 `DILINK_ADB`（必须是绝对路径），再找已知 SDK 位置。
 */
class ProcessAdbRunner(
    private val adbPath: String = defaultAdbPath(),
) : AdbRunner {

    private val streams = CopyOnWriteArrayList<Process>()

    override fun run(
        args: List<String>,
        waitForExit: Boolean,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): Boolean = try {
        val process = ProcessBuilder(listOf(adbPath) + args)
            .redirectErrorStream(true)
            .start()
        // 输出一律在独立线程里排空：同步命令也可能在超时前一直不产出，
        // 在读线程里等待会吃掉 waitFor 的超时语义。
        Thread({ drain(process, onOutput) }, "adb-output").apply {
            isDaemon = true
            start()
        }
        if (waitForExit) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                // D-M7：destroyForcibly 是异步的 —— 不再 waitFor 的话 native 进程
                // handle 只能等 GC finalize，stdio 也一直开着。补一次有界等待
                // （ destroyForcibly 之后 SIGKILL 已发，正常几百微秒就退）。
                process.destroyForcibly()
                process.waitFor(REAP_GRACE_MS, TimeUnit.MILLISECONDS)
                closeStreams(process)
                reaped.incrementAndGet()
            }
            finished && process.exitValue() == 0
        } else {
            streams += process
            true
        }
    } catch (_: Exception) {
        // 找不到 adb / 权限不足 / 进程启动失败，一律当作命令失败
        false
    }

    override fun killAll() {
        // 顺序：destroy → 有界等待退出 → 关流。反过来（先关流再等）在 Windows 上
        // 可能让 adb.exe 的 pipe 提前断开，设备侧引擎的行为就不确定了 —— killAll
        // 的语义是"杀掉"，那就先确保它死掉。
        val held = streams.toList()
        streams.clear()
        held.forEach { runCatching { it.destroy() } }
        held.forEach { runCatching { it.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS) } }
        held.forEach { closeStreams(it) }
    }

    /**
     * 测试可见（audit WIN-15）：当前被持有、等待 [killAll] 的常驻进程快照。
     *
     * "持有的进程就是设备侧引擎的存活锚点"这条协议约定（见类 KDoc）没法用公共
     * API 观察 —— 这里给测试一条只读支路，生产代码不要调用。
     */
    internal fun heldProcesses(): List<Process> = streams.toList()

    /**
     * 测试可见（audit D-M7）：被 reap（超时强杀后有界 waitFor + 关流）的进程数。
     *
     * 同样只给测试用：reap 是"不泄漏 native handle"的兜底动作，除了这个计数器
     * 没有别的可观测信号。
     */
    internal fun reapedProcesses(): Int = reaped.get()

    private val reaped = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 退出兜底：destroy 之后短暂等待每个进程真的消失，并关掉它们的 stdio。
     *
     * shutdown hook 一返回就允许 JVM 结束，而 [Process.destroy] 是异步的 ——
     * 不等一下的话 adb.exe 可能比 JVM 活得久，那这道保险就等于没做（audit WIN-03）。
     */
    private fun killAllAndAwait(graceMs: Long) {
        val held = streams.toList()
        streams.clear()
        held.forEach { runCatching { it.destroy() } }
        held.forEach { runCatching { it.waitFor(graceMs, TimeUnit.MILLISECONDS) } }
        held.forEach { closeStreams(it) }
    }

    private fun drain(process: Process, onOutput: (String) -> Unit) {
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            onOutput(line)
        }
    }

    /** 关掉三条 stdio（audit D-M7）：不关的话 fd 要等 GC finalize 才回收。 */
    private fun closeStreams(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    companion object {
        /**
         * 解析 adb 可执行文件的绝对路径（audit D-M8）。
         *
         * 顺序：
         *  1. `DILINK_ADB` 环境变量（SDK 没进 PATH 时最省事）—— 必须是**绝对路径**，
         *     相对值直接拒绝并回报原因（相对路径同样会被 CWD 解释，等于没校验）；
         *  2. 已知 SDK 安装位置（Android Studio 默认路径 + `ANDROID_HOME` 系列变量）；
         *  3. 兜底 `adb` 裸名（交给 PATH）。此时把最终路径打进日志 —— 出安全问题
         *     （连的是启动目录里的假 adb）时这是唯一能事后查到的线索。
         */
        fun defaultAdbPath(): String = resolveAdbPath(
            envAdb = System.getenv("DILINK_ADB"),
            candidates = knownAdbPaths(),
            exists = { File(it).isFile },
        )

        /**
         * 解析逻辑本体（抽出来是为了单测，audit D-M8）：环境值与候选列表都注入，
         * 不依赖真实机器上装了什么。
         *
         * @param envAdb `DILINK_ADB` 的值（null/空 = 未设置）
         * @param candidates 已知 SDK 位置的候选路径（按优先级）
         * @param exists 候选路径的存在性判定（测试注入）
         * @return adb 可执行文件的路径；找不到时是兜底的裸名 `adb`
         */
        internal fun resolveAdbPath(
            envAdb: String?,
            candidates: List<String>,
            exists: (String) -> Boolean = { File(it).isFile },
        ): String {
            val env = envAdb?.trim().orEmpty()
            if (env.isNotEmpty()) {
                if (File(env).isAbsolute) {
                    logResolved("DILINK_ADB=$env")
                    return env
                }
                // 相对值一律拒绝：按裸名/相对路径解析时 Windows 会先搜当前工作目录，
                // 能在启动目录写文件的进程即以用户权限执行（这正是 D-M8 要堵的洞）。
                PlatformLog.w(TAG, "忽略相对的 DILINK_ADB（必须是绝对路径）: $env")
            }
            val fromSdk = candidates.firstOrNull(exists)
            if (fromSdk != null) {
                logResolved(fromSdk)
                return fromSdk
            }
            logResolved("adb（按 PATH 查找，未在已知 SDK 位置找到）")
            return FALLBACK_ADB
        }

        /** 已知的 SDK / platform-tools 位置（Windows 优先，顺带覆盖 macOS/Linux）。 */
        private fun knownAdbPaths(): List<String> = buildList {
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (!sdk.isNullOrBlank()) add(File(sdk, "platform-tools/adb.exe").path)
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) {
                add(File(localAppData, "Android/Sdk/platform-tools/adb.exe").path)
            }
            val home = System.getProperty("user.home")
            if (!home.isNullOrBlank()) {
                add(File(home, "AppData/Local/Android/Sdk/platform-tools/adb.exe").path)
                add(File(home, "Library/Android/sdk/platform-tools/adb").path)
                add(File(home, "Android/Sdk/platform-tools/adb").path)
            }
        }

        /** 把最终解析结果打进日志（audit D-M8：启动时就该知道将执行哪个 adb）。 */
        private fun logResolved(path: String) {
            PlatformLog.w(TAG, "adb.exe = $path")
        }

        private const val FALLBACK_ADB = "adb"

        /** forcible destroy 之后等它真的退出的宽限时间（audit D-M7）。 */
        private const val REAP_GRACE_MS = 1_000L

        /** [killAll] 里等被持有的进程退出的宽限时间。 */
        private const val KILL_GRACE_MS = 1_000L

        private const val TAG = "adb"

        /** 已创建的实例（每个进程 1–2 个：DesktopApp / ProbeRunner 各一个）。 */
        private val live = ConcurrentHashMap.newKeySet<ProcessAdbRunner>()

        /** 兜底时等 adb.exe 退出的宽限时间。 */
        private const val EXIT_GRACE_MS = 500L

        init {
            // JVM 退出兜底（audit WIN-03）：被持有的 adb.exe 在 Windows 上不随父进程退出，
            // 而它顶着设备侧的 shell 流与 `exec app_process` 起的引擎。主路径仍是
            // [AdbDeployer.close]（会话真正结束时调用），这里只处理"收尾被截断"的情况。
            Runtime.getRuntime().addShutdownHook(
                Thread({ live.forEach { runCatching { it.killAllAndAwait(EXIT_GRACE_MS) } } }, "adb-reaper"),
            )
        }
    }

    init {
        live += this
    }
}
