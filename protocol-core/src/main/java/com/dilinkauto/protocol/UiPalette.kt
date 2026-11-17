package com.dilinkauto.protocol

/**
 * Shared cross-app UI palette (ARGB color ints).
 *
 * The phone app and the car app are one product and must look like one system,
 * but they are separate APKs with no shared UI module — these values previously
 * lived (identically) in both `Theme.kt` and `CarTheme.kt` and could drift
 * silently. Kept as raw ARGB ints so :protocol-core stays free of Compose /
 * Android UI types; each theme wraps them in `Color(...)`.
 *
 * Car-only slots (tertiary, surfaceVariant, error, …) stay in CarTheme.
 */
object UiPalette {
    val PRIMARY: Int = 0xFF4FC3F7.toInt()
    val ON_PRIMARY: Int = 0xFF000000.toInt()
    val SECONDARY: Int = 0xFF1A73E8.toInt()
    val BACKGROUND: Int = 0xFF0D1117.toInt()
    val SURFACE: Int = 0xFF161B22.toInt()
    val ON_BACKGROUND: Int = 0xFFFFFFFF.toInt()
    val ON_SURFACE: Int = 0xFFFFFFFF.toInt()
}
