package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VideoConfig

/**
 * Adaptive bitrate policy for the encode hot path.
 *
 * Extracted from [GlPipeline] so the policy can be unit-tested without EGL,
 * MediaCodec or a socket. The pipeline thread measures how long each frame's
 * socket write took and feeds the result in; this class decides whether the
 * encoder bitrate should move and asks for a sync frame when it drops.
 *
 * Two independent reactions to the same signal, by design:
 *  - **Back-pressure (fast):** a write slower than [slowWriteMillis] means the
 *    car is not draining. Drop to 75% (never below [MIN_BITRATE]) and request a
 *    sync frame so the new rate takes effect immediately.
 *  - **Recovery (slow):** after [cleanIntervalMillis] with no slow write, creep
 *    the rate back up by [RECOVERY_STEP] per interval, never above the ceiling
 *    the caller configured.
 *
 * Pure and allocation-free on the hot path: [onFrameWritten] returns `true`
 * when it wants the caller to apply [currentBitrate].
 */
internal class AdaptiveBitrate(
    ceiling: Int,
    /** A write slower than this many milliseconds counts as back-pressure. */
    private val slowWriteMillis: Long = 15L,
    /** Continuous clean time before the rate is allowed to climb again. */
    private val cleanIntervalMillis: Long = 2000L
) {
    private val ceiling = maxOf(MIN_BITRATE, ceiling)

    // `this.` is required: a bare `ceiling` in a property initializer resolves to
    // the *constructor parameter* (which shadows the property), not the clamped
    // field above — without it a too-low configured ceiling would be used as-is.
    var currentBitrate: Int = this.ceiling
        private set

    /** Set when a drop happened, so the caller requests a sync frame once. */
    var needsSyncFrame: Boolean = false
        private set

    private var cleanSinceNanos = 0L

    /**
     * Feed one frame-write duration.
     *
     * @param writeNanos how long the frame's socket write took.
     * @return true when [currentBitrate] changed and the caller must apply it.
     */
    fun onFrameWritten(writeNanos: Long): Boolean {
        val now = System.nanoTime()
        if (writeNanos / 1_000_000L > slowWriteMillis) {
            cleanSinceNanos = 0L
            // 75% of the current rate, but never below the floor.
            val target = maxOf(MIN_BITRATE, (currentBitrate * 0.75f).toInt())
            if (target < currentBitrate) {
                currentBitrate = target
                needsSyncFrame = true
                return true
            }
        } else if (cleanSinceNanos == 0L) {
            cleanSinceNanos = now
        }

        if (cleanSinceNanos > 0L &&
            (now - cleanSinceNanos) / 1_000_000L >= cleanIntervalMillis
        ) {
            cleanSinceNanos = now
            val target = minOf(ceiling, currentBitrate + RECOVERY_STEP)
            if (target > currentBitrate) {
                currentBitrate = target
                return true
            }
        }
        return false
    }

    /** Caller has applied the rate; clears the one-shot sync-frame request. */
    fun onBitrateApplied() {
        needsSyncFrame = false
    }

    companion object {
        /**
         * Never encode below this, however backed up the car is.
         *
         * Value lives in [VideoConfig.ADAPTIVE_MIN_BITRATE] so every bitrate
         * bound is single-sourced; kept as a local alias for existing call
         * sites and tests.
         */
        const val MIN_BITRATE = VideoConfig.ADAPTIVE_MIN_BITRATE

        /** How much the rate climbs per clean interval. */
        const val RECOVERY_STEP = 500_000
    }
}