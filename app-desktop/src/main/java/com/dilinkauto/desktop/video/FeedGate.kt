package com.dilinkauto.desktop.video

/**
 * 送进解码器的字节闸门：决定哪些字节真的写进 [VideoStreamPipe]。
 *
 * 规则来自车机端已经踩过的坑（`.plan/windows-client-plan.md` 6.2）：
 * 1. 见到 CONFIG（SPS/PPS）之前，任何视频帧都不写——FFmpeg 没有 SPS/PPS 无法初始化解析；
 * 2. 解码器实例启动/重建时调用 [beginStream]：先把缓存的 CONFIG 重放一遍，然后丢弃 P 帧
 *    直到第一个 IDR——没有参考帧的 P 帧解不出画面，还会把输入侧堵死；
 * 3. CONFIG 一旦收到就缓存，供解码器重建时重放。
 *
 * 纯逻辑：不碰 IO，写入动作由构造参数注入，便于单测（SRP）。
 * 写入方法可能被网络线程（feed）与解码线程（beginStream）同时调用，故全部同步。
 */
class FeedGate(
    private val write: (ByteArray) -> Boolean,
    initialConfig: ByteArray? = null,
) {
    /** 最近一次收到的 CONFIG（SPS/PPS）。 */
    var config: ByteArray? = initialConfig
        private set

    private var configSent = false
    private var seekingKeyFrame = true

    /** CONFIG 到达前被丢弃的帧数（诊断用）。 */
    var droppedPreConfig = 0L
        private set

    /** 重建后等待 IDR 期间被丢弃的 P 帧数（诊断用）。 */
    var skippedPFrames = 0L
        private set

    /** 解码器实例启动/重建时调用：重放缓存的 CONFIG，并重新进入"等 IDR"状态。 */
    @Synchronized
    fun beginStream() {
        configSent = false
        seekingKeyFrame = true
        config?.let { if (write(it)) configSent = true }
    }

    /** 收到 CONFIG（SPS/PPS）。返回 true 表示已写入管道。 */
    @Synchronized
    fun onConfig(data: ByteArray): Boolean {
        config = data
        if (configSent) {
            // 会话中途的 CONFIG（分辨率变化/编码器重启）：直接透传，FFmpeg 自行处理
            return write(data)
        }
        val ok = write(data)
        if (ok) configSent = true
        return ok
    }

    /**
     * 收到视频帧。返回 true 表示已写入管道；
     * false = 被丢弃（还没见到 CONFIG，或重建后还没等到 IDR）。
     */
    @Synchronized
    fun onFrame(data: ByteArray, isKeyFrame: Boolean): Boolean {
        if (!configSent) {
            droppedPreConfig++
            return false
        }
        if (seekingKeyFrame) {
            if (!isKeyFrame) {
                skippedPFrames++
                return false
            }
            seekingKeyFrame = false
        }
        return write(data)
    }
}