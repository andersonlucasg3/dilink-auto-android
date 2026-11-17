package com.dilinkauto.server.decoder

import com.dilinkauto.protocol.DecodeLivenessTracker

/**
 * 解码器是否仍在出帧的判定器（issue #11）。
 *
 * 背景：持续黑屏检测的纯码流判据（[com.dilinkauto.protocol.BlackScreenDetector]）
 * 分不清「内容本身是黑」与「管线卡死」——两者都表现为连续极小 I 帧。切应用后
 * app 的启动/加载页是大面积纯色，编码器照样输出 ~200B 的关键帧，车机端 3s 窗口
 * 一到就误判为管线故障、主动重握手重启 VD，用户看到的是「断联」10 秒左右。
 *
 * 判据：黑屏满窗时如果最近 [windowMs] 内解码出的帧数还在增长，说明解码器一直在
 * 正常出帧 —— 那就是内容黑，不该升级；只有出帧停摆才是真故障。与桌面端
 * `VideoDecodePipeline.decodingAlive()` 同一语义，底层统一委托至
 * [com.dilinkauto.protocol.DecodeLivenessTracker]。
 *
 * 线程模型：调用方在**网络线程**（黑屏检测）里问询，出帧计数在**喂帧线程**里自增，
 * 底层 [DecodeLivenessTracker] 的 [isAlive] 与 [reset] 互斥。
 */
class DecodeLiveness(
    /** 最近这么久内出帧计数没变化，就算解码停摆。 */
    windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val tracker = DecodeLivenessTracker(windowMs)

    fun isAlive(count: Long, nowMs: Long): Boolean = tracker.isAlive(count, nowMs)

    /** 会话边界（start/stop）复位，让新会话重新建立基线。 */
    fun reset() = tracker.reset()

    companion object {
        /**
         * 2s：正常 30fps 下至少该解出 60 帧，2s 一帧都没有才是真停摆，而不是帧率抖动。
         * 比黑屏窗口（3s）短，所以「内容黑但仍在解码」永远进不了升级分支。
         */
        const val DEFAULT_WINDOW_MS = DecodeLivenessTracker.DEFAULT_WINDOW_MS
    }
}
