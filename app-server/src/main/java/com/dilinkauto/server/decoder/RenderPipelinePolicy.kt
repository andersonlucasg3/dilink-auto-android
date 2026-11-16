package com.dilinkauto.server.decoder

/**
 * 车机端「Surface ↔ 解码器」绑定与「何时允许进入 STREAMING」的纯判据（issue #198）。
 *
 * 现场故障（`client.log` 第 6 次会话）：`SurfaceView` 从未创建 Surface、解码器从未
 * `start()`，状态机却因为「收到一个视频帧」就报 `Video ready — streaming`，整段会话零画面。
 * 根因是这两件事在中转代码里没有任何判据：
 *  - `surfaceCreated` 只是在解码器已经跑着时重绑（`setOutputSurface`），失败也照样打成功日志；
 *  - `handleVideoFrame` 收到非 CONFIG 帧就把 `_videoReady` 置 true，与解码器/ Surface 状态无关。
 *
 * 这里把判据抽成不依赖 Android 的纯函数，单测才能在没有 Robolectric / MediaCodec 的
 * 情况下用假 Surface 状态驱动（见 `RenderPipelinePolicyTest`）。
 */
internal object RenderPipelinePolicy {

    /** `surfaceCreated` 到达时应执行的动作。 */
    enum class SurfaceAction {
        /** 解码器在跑：用 `MediaCodec.setOutputSurface` 重绑到新 Surface（不丢参考链）。 */
        SWITCH_SURFACE,

        /** 解码器已停：用新 Surface 冷启动（会用 cached CONFIG + cached IDR）。 */
        START_DECODER,

        /** Surface 在回调里已经失效：什么都不做，等下一次 `surfaceCreated`。 */
        IGNORE,
    }

    /**
     * `surfaceCreated` 的分派判据。
     *
     * `surfaceValid == false` 优先于运行状态：SurfaceView 的销毁/重建可能抢跑，
     * 往一个已经失效的 Surface 上 `setOutputSurface` 只会抛异常并留下「日志成功、
     * 实际永久黑屏」的状态。
     */
    fun onSurfaceCreated(decoderRunning: Boolean, surfaceValid: Boolean): SurfaceAction = when {
        !surfaceValid -> SurfaceAction.IGNORE
        decoderRunning -> SurfaceAction.SWITCH_SURFACE
        else -> SurfaceAction.START_DECODER
    }

    /**
     * 是否允许把会话状态推进到 `STREAMING`。
     *
     * 三个条件必须同时成立：解码器在跑、输出绑定的 Surface 有效、**当前 Surface 上**
     * 至少渲染过 1 帧。只看「收到帧」会把「手机在发流、车机没有可见输出」误判成就绪。
     */
    fun canEnterStreaming(
        decoderRunning: Boolean,
        outputSurfaceValid: Boolean,
        framesRenderedOnSurface: Long,
    ): Boolean = decoderRunning && outputSurfaceValid && framesRenderedOnSurface > 0
}

/**
 * 「等 Surface / 等首帧」的诊断节奏（issue #198）。
 *
 * 状态停在 `CONNECTED` 时，收帧路径每 [intervalMs] 最多要一行等待日志，便于实车回签
 * 区分「没有 Surface（用户在主页，SurfaceView 不可见）」与「有 Surface 但没出帧」。
 * 只做时间比较，不读 Surface、不碰 MediaCodec。
 */
internal class SurfaceWaitLogger(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {

    private var startMs = -1L
    private var lastLogMs = -1L

    /** 会话切换/断开时复位，下一次未就绪重新计时。 */
    fun reset() {
        startMs = -1L
        lastLogMs = -1L
    }

    /**
     * 未就绪时调用。返回本轮应输出的「已等待秒数」；本 tick 未到间隔时返回 null。
     * 首次调用立即返回（0s），保证一进等待态就能看到诊断行。
     */
    fun onNotReady(nowMs: Long): Long? {
        if (startMs < 0) startMs = nowMs
        if (lastLogMs >= 0 && nowMs - lastLogMs < intervalMs) return null
        lastLogMs = nowMs
        return (nowMs - startMs) / 1000L
    }

    companion object {
        /** issue #198 的等待日志间隔：2s 一条，够密也够省。 */
        const val DEFAULT_INTERVAL_MS = 2_000L
    }
}
