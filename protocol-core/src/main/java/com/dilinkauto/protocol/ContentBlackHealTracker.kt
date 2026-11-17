package com.dilinkauto.protocol

/**
 * "解码仍在出帧、但码流内容是黑的"这条路上的升级限流器（issue #15）。
 *
 * 背景：issue #11 给黑屏自愈接上 `DecodeLiveness` 闸门后，**内容本身是黑**（切应用
 * 的纯色启动页、空 launcher）不再触发重握手 —— 这是对的。但实车（issue #15）出现
 * 了闸门的盲区：VD 的顶层 task 长时间停在 `visibleRequested=false`，编码器持续输出
 * 极小 I 帧、解码也一直在出帧，于是闸门永远"抑制重建"，用户只能断连重连。
 *
 * 这里补的是"轻量自愈"：黑且解码存活持续超过 [firstRequestAfterMs] 后，允许向手机
 * 发一次 `REQUEST_DISPLAY_HEAL`，之后每 [minIntervalMs] 最多一次 —— 手机侧要跑
 * dumpsys、必要时还要做 display 往返与层树修复（数秒），不能逐帧触发。
 *
 * 只做判定与限流，不发命令：车机端与桌面端可以共用同一套节奏，副作用留给调用方。
 * 时间用调用方注入的单调毫秒时钟（车机端 `SystemClock.elapsedRealtime()`）。
 *
 * 哨兵用 `null` 而不是 `0L`：`nowMs` 是进程相对时钟，量级数分钟起步，`0L` 会让
 * "第一帧黑"立刻满足阈值 —— [BlackScreenDetector] 里记过同一个坑。
 */
class ContentBlackHealTracker(
    /** 黑屏累计多久才发第一次自愈请求。 */
    private val firstRequestAfterMs: Long = DEFAULT_FIRST_REQUEST_AFTER_MS,
    /** 两次自愈请求之间的最小间隔。 */
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    /** 本轮"存活 + 黑"的起点；不在连续黑屏里时为 null。 */
    private var sinceMs: Long? = null
    private var lastRequestMs: Long? = null

    /**
     * 喂一帧"解码存活 + 码流极小"的观测。
     *
     * @return true = 本次应当发一次自愈请求（调用方负责真正发命令）。
     */
    fun onAliveBlackFrame(nowMs: Long): Boolean {
        val since = sinceMs
        if (since == null) {
            sinceMs = nowMs
            return false
        }
        if (nowMs - since < firstRequestAfterMs) return false
        val last = lastRequestMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastRequestMs = nowMs
        return true
    }

    /** 码流恢复正常（出现正常大小 I 帧）或解码重启时清空本轮状态。 */
    fun reset() {
        sinceMs = null
        lastRequestMs = null
    }

    /** 是否正处于一轮连续"存活 + 黑"里（仅用于日志/测试）。 */
    fun isTracking(): Boolean = sinceMs != null

    companion object {
        /**
         * 6 秒：`BlackScreenDetector` 的升级窗口是 3 秒，再留一倍余量给应用启动/加载页
         * 的纯色画面，避免刚切应用就发无谓的自愈请求。
         */
        const val DEFAULT_FIRST_REQUEST_AFTER_MS = 6_000L

        /**
         * 15 秒：手机侧一次自愈最多要跑 3 次 dumpsys + 2 次 move-stack（每步 400ms），
         * 极端情况还要旋转往返（~3s）。太密会把 shell 与 WM 刷爆，反而拖慢恢复。
         */
        const val DEFAULT_MIN_INTERVAL_MS = 15_000L
    }
}
