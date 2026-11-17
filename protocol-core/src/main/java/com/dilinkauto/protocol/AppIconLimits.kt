package com.dilinkauto.protocol

/**
 * Standard safety limits for application icon decoding and memory budgets.
 */
object AppIconLimits {
    /**
     * Max decoded width / height in pixels for a single app icon (audit S-M5, D-M5).
     */
    const val MAX_ICON_DIM = 512

    /**
     * Backward-compatible alias for [MAX_ICON_DIM].
     */
    const val MAX_ICON_DIMENSION = 512

    /**
     * Max raw byte size for a single icon payload (1MB = 1048576 bytes).
     */
    const val MAX_ICON_BYTES = 1048576

    /**
     * Backward-compatible alias for [MAX_ICON_BYTES].
     */
    const val MAX_ICON_PNG_BYTES = 1048576
}
