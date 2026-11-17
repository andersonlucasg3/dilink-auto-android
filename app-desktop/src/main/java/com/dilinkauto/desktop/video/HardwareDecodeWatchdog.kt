package com.dilinkauto.desktop.video

/**
 * 硬解"起不来"的判定器。
 *
 * 判据只有一个：**从码流开始喂入算起，超过 [timeoutMs] 仍然一帧都没解出来**。
 * 硬解在这台机器上跑不通的典型表现就是这个——`FFmpegFrameGrabber.start()` 要么直接抛异常，
 * 要么挂住把喂进来的字节全吞掉，画面永远停在第一帧之前。出过画面之后再出问题（花屏、
 * 中途解码失败）不算硬解的锅，交给解码器重建逻辑处理。
 *
 * 刻意做成纯状态机（时间由 [clock] 注入）以便单测——真实触发需要一台硬解不可用的机器。
 *
 * 线程模型：`onStreamStarted` 由解码线程调用，`onFrame` 由网络线程调用，
 * 因此内部状态用 `@Volatile` + 单次判定（返回 true 后 [resolved] 置位）保证只触发一次。
 */
class HardwareDecodeWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var startedAt: Long = UNSET

    @Volatile
    private var resolved: Boolean = false

    /** 码流开始（解码器即将用硬解启动）。已经判定过则忽略。 */
    fun onStreamStarted() {
        if (!resolved) startedAt = clock()
    }

    /**
     * 每收到一帧调用一次。
     *
     * @param framesDecoded 当前累计解码出的画面数
     * @return true 表示应回退软解；同一次判定只会返回一次 true
     */
    fun onFrame(framesDecoded: Long): Boolean {
        if (resolved) return false
        val started = startedAt
        if (started == UNSET) return false
        if (framesDecoded > 0) {
            // 出了画面 → 硬解可用，本次运行不再判它
            resolved = true
            return false
        }
        if (clock() - started < timeoutMs) return false
        resolved = true
        return true
    }

    /** 重置（新的硬解尝试时用）。 */
    fun reset() {
        startedAt = UNSET
        resolved = false
    }

    companion object Defaults {
        /** 默认容忍窗口：3 秒还没出第一帧就认为硬解没跑通。 */
        const val DEFAULT_TIMEOUT_MS = 3_000L

        private const val UNSET = -1L
    }
}
