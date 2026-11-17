package com.dilinkauto.server.decoder

/**
 * 渲染停顿诊断（issue #58）。
 *
 * 实车症状是「画面卡住 1~2 秒后自己恢复」，但手机端线路日志里 `sendDrops` 几乎为 0，
 * 光看手机侧分不清缺口出在车机端的哪一段。这个类把「两次渲染推进之间的间隔」抽出来，
 * 停顿超过阈值时产出一份带增量的快照，供 [VideoDecoder] 打一条 `STALL` 日志：
 *
 *  - `recv+=0` → 压根没收到帧（手机端漏发 / 链路停顿）
 *  - `recv+>0` 且 `fed+=0` → 收到了但没喂进去（重同步跳帧，看 `skips` / `resync`）
 *  - `drops+>0` / `inputFails+>0` → 接收队列驱逐或 `dequeueInputBuffer` 超时
 *
 * 检测点是「渲染推进」而不是「时间流逝」：卡顿恢复时必然有一次渲染推进，所以停顿一定能被
 * 观察到；真·解码停摆（一直不出帧）由黑屏闸门与重握手负责，不在这里重复告警。
 *
 * 纯逻辑、无 Android 依赖，便于用 JVM 单测钉住（见 `RenderStallDetectorTest`）。
 */
class RenderStallDetector(
    /** 两次渲染推进间隔达到该值即产出一次停顿报告。 */
    private val alertMs: Long = DEFAULT_ALERT_MS,
) {
    /** 每次喂帧循环调用一次的快照，字段都是单调递增的累计计数。 */
    data class Snapshot(
        val renders: Long,
        val receive: Long,
        val fed: Long,
        val drops: Long,
        val inputFails: Long,
        val keysDropped: Long,
        val queueSize: Int,
        val awaitingIdr: Boolean,
        val skips: Long,
    )

    /** 一次停顿的汇总，字段含义见类注释。 */
    data class StallReport(
        val gapMs: Long,
        val fromRenders: Long,
        val toRenders: Long,
        val receiveDelta: Long,
        val fedDelta: Long,
        val dropDelta: Long,
        val inputFailDelta: Long,
        val keyDropDelta: Long,
        val queueSize: Int,
        val awaitingIdr: Boolean,
        val skips: Long,
    )

    private var lastAdvanceMs = 0L
    private var lastAdvanceCount = 0L
    private var lastReceive = 0L
    private var lastFed = 0L
    private var lastDrops = 0L
    private var lastInputFails = 0L
    private var lastKeysDropped = 0L

    /**
     * 传入当前时刻与累计计数。返回本次需要告警的停顿报告；无新渲染推进或间隔未超阈值时返回 null。
     */
    fun onTick(nowMs: Long, snap: Snapshot): StallReport? {
        // 没有新的渲染推进 → 仍在同一次停顿里，基线保持不动，等恢复后再算总时长。
        if (snap.renders <= lastAdvanceCount) return null

        val report = if (lastAdvanceMs != 0L && nowMs - lastAdvanceMs >= alertMs) {
            StallReport(
                gapMs = nowMs - lastAdvanceMs,
                fromRenders = lastAdvanceCount,
                toRenders = snap.renders,
                receiveDelta = snap.receive - lastReceive,
                fedDelta = snap.fed - lastFed,
                dropDelta = snap.drops - lastDrops,
                inputFailDelta = snap.inputFails - lastInputFails,
                keyDropDelta = snap.keysDropped - lastKeysDropped,
                queueSize = snap.queueSize,
                awaitingIdr = snap.awaitingIdr,
                skips = snap.skips,
            )
        } else null

        // 无论是否告警都推进基线：下一次停顿从这一帧重新起算。
        lastAdvanceMs = nowMs
        lastAdvanceCount = snap.renders
        lastReceive = snap.receive
        lastFed = snap.fed
        lastDrops = snap.drops
        lastInputFails = snap.inputFails
        lastKeysDropped = snap.keysDropped
        return report
    }

    /** 新会话重新起算（decoder `start()` 时调用）。 */
    fun reset() {
        lastAdvanceMs = 0L
        lastAdvanceCount = 0L
        lastReceive = 0L
        lastFed = 0L
        lastDrops = 0L
        lastInputFails = 0L
        lastKeysDropped = 0L
    }

    companion object {
        /** 默认停顿告警阈值：用户可感知的卡顿量级（1~2s）的下沿。 */
        const val DEFAULT_ALERT_MS = 1000L
    }
}
