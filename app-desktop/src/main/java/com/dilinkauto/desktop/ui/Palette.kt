package com.dilinkauto.desktop.ui

import androidx.compose.ui.graphics.Color

/**
 * 桌面端配色（audit R3-SRP-15，从 `DesktopWindow.kt` 移出）——Material 3 深色系的
 * 一个极简子集，避免额外引入 material 依赖。
 *
 * 被窗口外壳、应用网格与显示面板共用，因此不再锁在窗口文件里。
 */
internal object Palette {
    val Backdrop = Color(0xFF101014)
    val Rail = Color(0xFF1B1B1F)
    val Button = Color(0xFF2A2A31)
    val ButtonActive = Color(0xFF3A3A42)
    val Divider = Color(0xFF33333A)
    val Text = Color(0xFFE6E6E9)
    val TextDim = Color(0xFF9A9AA2)
    val Accent = Color(0xFF4C8DFF)
}
