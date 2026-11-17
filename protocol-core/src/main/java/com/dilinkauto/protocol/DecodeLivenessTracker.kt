package com.dilinkauto.protocol

/**
 * 解码器/流渲染出帧存活状态判定器。
 *
 * 背景：持续黑屏检测的纯码流判据（[BlackScreenDetector]）分不清「内容本身是黑」与「管线卡死」——
 * 两者都表现为连续极小 I 帧。切应用后 app 的启动/加载页是大面积纯色，编码器照样输出 ~200B 的关键帧，
 * 窗口一到若误判为管线故障而主动重握手重启 VD，用户看到的是「断联」数秒。
 *
 * 判据：在黑屏/卡顿窗口检测时，如果最近 [windowMs] 内解码/渲染出的帧数还在增长，
 * 说明解码器一直在正常出帧 —— 那就是内容黑，不该升级；只有出帧停摆才是真故障。
 * 统一车机端（DecodeLiveness）与桌面端（VideoDecodePipeline）解码存活判定核心。
 *
 * 线程模型：调用方在网络/检测线程问询，出帧计数在解码/渲染线程自增，
 * 因此核心状态访问通过同步锁保护。
 */
class DecodeLivenessTracker(
    /** 最近这么久内出帧计数没变化，就算解码停摆。 */
    val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var lastCount = 0L
    private var lastProgressMs = 0L

    /**
     * 解码是否仍在推进出帧。
     *
     * @param count 累计出帧数（例如 renderCount 或 framesDecoded）。
     * @param nowMs 单调毫秒时钟（如 SystemClock.elapsedRealtime() 或 System.nanoTime() / 1_000_000L）。
     * @return `true` = 最近 [windowMs] 内有增长（或还没有基线）；`false` = 停摆。
     */
    @Synchronized
    fun isAlive(count: Long, nowMs: Long): Boolean {
        if (count != lastCount) {
            lastCount = count
            lastProgressMs = nowMs
            return true
        }
        // 首次采样还没有基线（会话刚开始、一帧都没解出来）：按「活着」处理，
        // 预热期不该升级；真停摆会在一个窗口之后翻成 false。
        if (lastProgressMs == 0L) {
            lastProgressMs = nowMs
            return true
        }
        return nowMs - lastProgressMs < windowMs
    }

    /** 会话边界（start/stop/rebuild）复位，让新会话重新建立基线。 */
    @Synchronized
    fun reset() {
        lastCount = 0L
        lastProgressMs = 0L
    }

    companion object {
        /**
         * 2s：正常 30fps 下至少该解出 60 帧，2s 一帧都没有才是真停摆，而不是帧率抖动。
         * 比黑屏窗口（3s~10s）短，所以「内容黑但仍在解码」永远进不了升级分支。
         */
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
