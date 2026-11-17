package com.dilinkauto.server.decoder

/**
 * 参考链断裂后的重同步状态机（issue #9）。
 *
 * 背景：H.264 的 P 帧依赖前一帧。车机端任何一次「P 帧被丢」——4 槽队列满时丢弃、
 * 为关键帧驱逐、或 MediaCodec 输入缓冲超时——都会在参考链上留一个洞。旧实现只在
 * `consecutiveDrops > 10` 的 flush 分支置 `seekingKeyFrame`，普通丢弃不重同步，
 * 于是解码器继续用坏参考解 P 帧 → 马赛克，直到下一个自然 IDR（GOP=1s）才恢复。
 *
 * 这里把「发现问题 → 停止喂 P 帧 → 等到 IDR → 恢复」收敛成一个可单测的纯状态机：
 *  - [onGap] 由任意丢弃点调用，第一次进入重同步时返回 `true`（供上层向上游索要 IDR，
 *    且不会每次丢弃都重复索要）；
 *  - [shouldFeed] 由喂帧线程调用：重同步期间只放行关键帧，放行即自动回到正常态；
 *  - [reset] 用于 start/stop 等会话边界。
 *
 * 线程模型：丢弃点分布在网络线程与喂帧线程，[onGap] 与 [shouldFeed] 互斥；
 * [awaitingIdr] 是只读快照，用于统计行。
 */
class ResyncPolicy {

    @Volatile
    private var awaiting = false

    /** 当前是否处于「等待 IDR」的重同步状态（只读快照）。 */
    val awaitingIdr: Boolean get() = awaiting

    /**
     * 记录一次参考链缺口。
     *
     * @return 本次调用是否**首次**进入重同步。只有返回 `true` 时才应向上游索要
     *   IDR —— 后续丢弃点处于同一次缺口里，索要无意义且会造成请求风暴。
     */
    @Synchronized
    fun onGap(): Boolean {
        if (awaiting) return false
        awaiting = true
        return true
    }

    /**
     * 重同步期间决定这一帧是否还要喂给解码器。
     *
     * @param isKeyFrame 该帧是否为 IDR 关键帧。
     * @return `true` 表示照常喂；`false` 表示丢弃（P 帧在坏参考链上解码只会产
     *   马赛克）。放行关键帧的同时清除重同步状态。
     */
    @Synchronized
    fun shouldFeed(isKeyFrame: Boolean): Boolean {
        if (!awaiting) return true
        if (!isKeyFrame) return false
        awaiting = false
        return true
    }

    /** 会话边界（start/stop）复位。 */
    @Synchronized
    fun reset() {
        awaiting = false
    }
}
