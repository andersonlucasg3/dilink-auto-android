package com.dilinkauto.protocol

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    const val TARGET_FPS = 30  // reduced from 60 — fixed rate, no adaptive
    const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 33ms at 30fps
    const val VIRTUAL_DISPLAY_DPI = 480  // phone DPI used for VD creation and touch mapping
    const val TARGET_SW_DP = 600  // smallest-width dp for VD size calculation
}
