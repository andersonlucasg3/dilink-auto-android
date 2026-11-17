package com.dilinkauto.desktop.display

import java.awt.GraphicsEnvironment

/**
 * 一块显示器的几何信息（Windows 上的"显示器"= AWT 的一个 screen device）。
 *
 * @param index 在 [GraphicsEnvironment.getScreenDevices] 里的序号，作为 UI 上的稳定标识
 * @param x/y 该屏在虚拟桌面里的左上角坐标；[Screens.moveTo] 用它定位窗口
 */
data class DesktopScreen(
    val index: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isPrimary: Boolean,
) {
    /** UI 上的显示名，如 `#1 1920x1080 (主)`。 */
    val label: String get() = "#${index + 1} ${width}x$height${if (isPrimary) " (主)" else ""}"
}

/**
 * 枚举本机显示器（Phase 5a 的多显示器切换）。
 *
 * 无图形环境（headless / 远程会话）时返回空列表而不是抛异常——调用方据此
 * 隐藏"显示器"一节，其余功能不受影响。
 */
object Screens {

    fun list(): List<DesktopScreen> = runCatching {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = env.defaultScreenDevice
        env.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            DesktopScreen(
                index = index,
                x = bounds.x,
                y = bounds.y,
                width = bounds.width,
                height = bounds.height,
                isPrimary = device == primary,
            )
        }
    }.getOrElse { emptyList() }
}
