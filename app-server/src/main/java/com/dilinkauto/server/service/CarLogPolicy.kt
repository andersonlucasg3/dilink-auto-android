package com.dilinkauto.server.service

/**
 * 车机日志回传积压的上报口径（issue #206）。
 *
 * 实车 `client.log:386`：外层手机时间 `[16:36:15.996]` 与车机内嵌时间 `[16:36:09.378]`
 * 差 6.6s，同一时刻 40ms 内涌入 49 行 —— 跨端对齐时间线（如 #200 的 6 秒停滞）时
 * 会把 6 秒前的因果当成当前因果。这里只补「积压可见」，**不丢任何行**：
 * teardown / cleanup / FORTIFY 这类收尾关键行必须在任何降延迟策略下保留。
 */

/** 单条日志「入队 → 写出」超过该阈值才认为出现回传积压。 */
internal const val CAR_LOG_BACKLOG_DELAY_MS = 1000L

/** 积压告警的打印间隔下限：每 5 秒最多一行，避免告警自己把队列刷爆。 */
internal const val CAR_LOG_BACKLOG_REPORT_INTERVAL_MS = 5000L

/** `CarLog backlog=N delay=Xms` —— 现场 `grep 'CarLog backlog'` 的固定串。 */
internal fun carLogBacklogLine(backlog: Int, delayMs: Long): String =
    "CarLog backlog=$backlog delay=${delayMs}ms"

/**
 * 积压上报的限流器（纯 JVM，时钟由调用方注入）。
 *
 * 规则：
 * 1. 延迟 < [thresholdMs] 直接不报 —— 正常排队不算积压；
 * 2. 两次上报至少间隔 [intervalMs]（**只由真正上报的那次推进窗口**，
 *    低于阈值的行不会把下一次真积压的告警挤掉）；
 * 3. 返回 null 表示本行不报；返回值即完整日志行，方便调用方原样写出。
 *
 * [backlog] 由调用方给出（队列深度 + 离线缓冲行数），本类不持有队列。
 */
internal class CarLogBacklogReporter(
    private val clockMs: () -> Long,
    private val thresholdMs: Long = CAR_LOG_BACKLOG_DELAY_MS,
    private val intervalMs: Long = CAR_LOG_BACKLOG_REPORT_INTERVAL_MS,
) {
    private var lastReportMs: Long? = null

    fun onDrained(delayMs: Long, backlog: Int): String? {
        if (delayMs < thresholdMs) return null
        val now = clockMs()
        val last = lastReportMs
        if (last != null && now - last < intervalMs) return null
        lastReportMs = now
        return carLogBacklogLine(backlog, delayMs)
    }
}
