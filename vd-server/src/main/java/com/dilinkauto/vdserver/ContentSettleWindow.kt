package com.dilinkauto.vdserver

/**
 * 新建 VD 的"内容沉降窗口"（issue #202 C）。
 *
 * 现场（`.tmp/run-1116/vd-server.log:1109`）每次建屏后立刻判定：
 * `ensureContent: display=6 has no visible window, 10 live tasks` —— 而同一段日志里
 * 这条判定之后几百毫秒，`am start --task` 就能把任务拉回去。也就是说"没有可见窗口"
 * 有一段时间是**新建 display 的正常过渡态**，与 issue #193 的黑屏判定叠加后会误报黑屏。
 *
 * 这里只做一件事：在判定"无内容"之前，给一个短沉降窗口，窗口内一旦探到可见任务就
 * 直接复用（不再搬任务）。时间源与 sleep 都由调用方注入，单测用假时钟驱动，不真睡。
 */
internal class ContentSettleWindow(
    /** 总沉降窗口（毫秒）。0 = 不做沉降（保持旧行为）。 */
    private val settleMs: Long = DEFAULT_SETTLE_MS,
    /** 轮询间隔（毫秒）。 */
    private val pollMs: Long = DEFAULT_POLL_MS,
    /** 注入的时钟（纳秒）：生产用 `System.nanoTime`，单测用假时钟。 */
    private val nanoTime: () -> Long = { System.nanoTime() },
    /** 注入的等待：生产 `Thread.sleep`，单测只推进假时钟。 */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        /** 默认沉降窗口：500ms（issue #202 修复候选 C 给出的量级）。 */
        const val DEFAULT_SETTLE_MS = 500L

        /** 默认轮询间隔：100ms —— 窗口内最多 5 次探测，与一次 dumpsys 的耗时同量级。 */
        const val DEFAULT_POLL_MS = 100L
    }

    /**
     * 在沉降窗口内轮询 [probe]，直到它返回 true 或窗口耗尽。
     *
     * @return true = 窗口内探到内容（调用方应跳过搬任务）；false = 窗口耗尽仍无内容。
     */
    fun awaitContent(probe: () -> Boolean): Boolean {
        if (settleMs <= 0L) return false
        if (probe()) return true
        val deadline = nanoTime() + settleMs * 1_000_000L
        while (true) {
            val remainingMs = (deadline - nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) return false
            sleep(minOf(pollMs, remainingMs))
            if (probe()) return true
        }
    }
}
