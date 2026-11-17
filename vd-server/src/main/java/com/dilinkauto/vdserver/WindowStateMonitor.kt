package com.dilinkauto.vdserver

import android.os.SystemClock

/**
 * 一次 Task 窗口状态的只读快照（issue #64）。
 *
 * 字段名与 `dumpsys activity activities` 的原生字段一一对应，便于日志里直接比对。
 */
internal data class TaskWindow(
    val taskId: Int,
    val component: String?,
    val windowingMode: String?,
    val visibleRequested: Boolean?,
    val bounds: String?,
    val lastNonfullscreenBounds: String?,
)

/**
 * 实车复测自动取证：VD display 上的窗口形态采样（issue #64）。
 *
 * #59 锁定 VD 方向后，是否仍被应用压成 letterbox 小窗，只能持续观察
 * `mLastNonfullscreenBounds` 才能事后判定。之前只在建屏恢复/teardown 的一次性
 * dumpsys 里读过（见 `CarCommandRouter`），运行中的窗口形态没有记录。
 *
 * 采集策略：
 *  - 每 [pollMs] 读一次 `dumpsys activity activities`，只解析 VD display 段；
 *  - 指纹变化立刻落一行 `[Window]`（含 `mLastNonfullscreenBounds`）；
 *  - 指纹未变时每 [heartbeatMs] 落一次心跳，证明采样线程还活着；
 *  - 每行带 `HH:mm:ss.SSS` 时钟，方便和车机端 `STALL` 行对齐时间点。
 *
 * 线程是 daemon，`stop()` 幂等；cleanup 必须先停它再关 shell，避免关闭后的
 * shell 被 dumpsys 踩到。
 */
internal class WindowStateMonitor(
    private val execOut: (String) -> String?,
    private val displayIdProvider: () -> Int,
    private val log: (String) -> Unit,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastFingerprint: String? = null
    private var lastSampleMs = 0L
    private var lastHeartbeatMs = 0L

    fun start() {
        if (running) return
        running = true
        thread = daemonThread("WindowState", start = true) { loop() }
    }

    /** 幂等停止；最多等 500ms，绝不把 cleanup 挂住。 */
    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.interrupt()
        try { thread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        thread = null
    }

    private fun loop() {
        while (running) {
            try { sample() } catch (e: Exception) { PipeLog.err("[Window] sample failed: ${e.message}") }
            // 小步睡眠，stop() 能及时生效（dumpsys 本身最多几百毫秒）。
            var slept = 0L
            while (running && slept < pollMs) {
                try { Thread.sleep(SLEEP_SLICE_MS) } catch (_: InterruptedException) { return }
                slept += SLEEP_SLICE_MS
            }
        }
    }

    /** 采一次样：解析 + 变化判定 + 落日志。包内可见，便于以后单独触发。 */
    fun sample() {
        val id = displayIdProvider()
        if (id < 0) return
        val dump = execOut("dumpsys activity activities 2>/dev/null") ?: return
        val tasks = WindowStateParser.parseDisplay(dump, id)
        val now = SystemClock.uptimeMillis()
        val fingerprint = WindowStateParser.fingerprint(tasks)
        val changed = fingerprint != lastFingerprint
        // 首次采样一定落；之后变化落；不变时按心跳间隔落。
        if (changed || lastSampleMs == 0L || now - lastHeartbeatMs >= heartbeatMs) {
            log(formatLine(id, tasks, changed))
            lastFingerprint = fingerprint
            lastSampleMs = now
            lastHeartbeatMs = now
        }
    }

    private fun formatLine(displayId: Int, tasks: List<TaskWindow>, changed: Boolean): String {
        val top = tasks.firstOrNull()
        val body = if (tasks.isEmpty()) {
            "tasks=<none>"
        } else {
            tasks.joinToString(" ; ") { t ->
                "task=${t.taskId} comp=${t.component ?: "<unknown>"} " +
                    "mode=${t.windowingMode ?: "<unknown>"} visReq=${t.visibleRequested ?: "<unknown>"} " +
                    "bounds=${t.bounds ?: "<unknown>"} mLastNonfullscreenBounds=${t.lastNonfullscreenBounds ?: "<none>"}"
            }
        }
        return "[Window] display=$displayId changed=$changed n=${tasks.size} top=${top?.taskId ?: "-"} $body"
    }

    internal companion object {
        /** 10s 一次 dumpsys：足够抓住持续的 letterbox，又不至于自己拖慢投屏。 */
        const val DEFAULT_POLL_MS = 10_000L

        /** 状态不变时每分钟留一条心跳，证明采样还在跑。 */
        const val DEFAULT_HEARTBEAT_MS = 60_000L

        /** 睡眠切片，保证 stop() 最多等一个切片。 */
        const val SLEEP_SLICE_MS = 100L
    }
}

/**
 * `dumpsys activity activities` 里 VD display 段的窗口解析（issue #64）。
 *
 * 纯函数、无 Android 依赖，单测用真实 dump 片段钉住（见 `WindowStateParserTest`）。
 */
internal object WindowStateParser {
    /** 解析指定 display 段内的所有 task；display 不存在时返回空列表。 */
    fun parseDisplay(dump: String, displayId: Int): List<TaskWindow> {
        val section = ActivityDumpParser.displaySection(dump, displayId) ?: return emptyList()
        // issue #77：切段与「只认 task 头」统一走 ActivityDumpParser；同一 id 只取首次出现，
        // 否则 `rootOfTask=true task=Task{…#N …}` 这类引用会让同一 task 被解析多次。
        val headers = ActivityDumpParser.taskHeaders(section)
        if (headers.isEmpty()) return emptyList()
        val out = ArrayList<TaskWindow>(headers.size)
        for ((index, header) in headers.withIndex()) {
            val blockEnd = headers.getOrNull(index + 1)?.range?.first ?: section.length
            val block = section.substring(header.range.first, blockEnd)
            val taskId = header.groupValues[1].toIntOrNull() ?: continue
            val headerEnd = block.indexOf('}')
            val headerText = if (headerEnd >= 0) block.substring(0, headerEnd) else block
            out += TaskWindow(
                taskId = taskId,
                component = ACTIVITY_RE.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
                windowingMode = MODE_RE.find(headerText)?.groupValues?.get(1),
                visibleRequested = VISIBLE_RE.find(headerText)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
                bounds = BOUNDS_RE.find(block)?.groupValues?.get(1),
                lastNonfullscreenBounds = NONFULLSCREEN_RE.find(block)?.groupValues?.get(1),
            )
        }
        return out
    }

    /** 用于判定「窗口形态是否变化」的紧凑指纹。 */
    fun fingerprint(tasks: List<TaskWindow>): String = tasks.joinToString("|") {
        "${it.taskId},${it.component},${it.windowingMode},${it.visibleRequested},${it.bounds},${it.lastNonfullscreenBounds}"
    }

    /** ActivityRecord 行：抓组件名（如 `com.bilibili.app.in/.MainActivity`）。 */
    private val ACTIVITY_RE = Regex("""ActivityRecord\{[^}]*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s+t\d+\}""")

    /** Task 头里的 `mode=fullscreen`。 */
    private val MODE_RE = Regex("""\bmode=(\w+)""")

    /** Task 头里的 `visibleRequested=true/false`。 */
    private val VISIBLE_RE = Regex("""\bvisibleRequested=(true|false)""")

    /** Task 块里的 `mBounds=Rect(...)`（不同 ROM 可能不打印，缺失即 null）。 */
    private val BOUNDS_RE = Regex("""mBounds=(Rect\([^)]*\)|null)""")

    /** 竖屏小窗的核心字段（issue #59/#64）。 */
    private val NONFULLSCREEN_RE = Regex("""mLastNonfullscreenBounds=(Rect\([^)]*\)|null)""")
}