package com.dilinkauto.desktop.ui

/**
 * 等比缩放（letterbox）几何计算。
 *
 * 视频内容按容器宽高比居中等比适配：比例不一致时上下/左右留黑边，绝不拉伸。
 *
 * 绘制（[SwingVideoView.paintComponent]）与触摸坐标换算必须使用同一套算法，
 * 否则画面和点击位置会错位——这是计划书 8 节点名的风险点，故抽成单点实现。
 */
object Letterbox {

    /** 内容在容器内居中等比适配后的矩形（像素，含偏移）。 */
    data class Fitted(val x: Int, val y: Int, val width: Int, val height: Int) {
        /** 点是否落在内容矩形内（黑边区域不产生触摸）。 */
        fun contains(px: Int, py: Int): Boolean =
            px >= x && px < x + width && py >= y && py < y + height
    }

    /** 归一化触摸坐标（0..1，相对视频内容）。 */
    data class Normalized(val x: Float, val y: Float)

    /** 计算内容在容器内的适配矩形；容器或内容尺寸非法时退化为 1x1 占位。 */
    fun fit(containerWidth: Int, containerHeight: Int, contentWidth: Int, contentHeight: Int): Fitted {
        if (containerWidth <= 0 || containerHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return Fitted(0, 0, 1, 1)
        }
        val scale = minOf(
            containerWidth.toDouble() / contentWidth,
            containerHeight.toDouble() / contentHeight,
        )
        val w = (contentWidth * scale).toInt().coerceAtLeast(1)
        val h = (contentHeight * scale).toInt().coerceAtLeast(1)
        return Fitted((containerWidth - w) / 2, (containerHeight - h) / 2, w, h)
    }

    /**
     * 容器坐标 → 归一化内容坐标；点落在黑边上时返回 null。
     * 用于"按下"判定：点黑边不应产生触摸事件。
     */
    fun toNormalized(px: Int, py: Int, fitted: Fitted): Normalized? {
        if (!fitted.contains(px, py)) return null
        return Normalized(
            x = ((px - fitted.x).toFloat() / fitted.width).coerceIn(0f, 1f),
            y = ((py - fitted.y).toFloat() / fitted.height).coerceIn(0f, 1f),
        )
    }

    /**
     * 容器坐标 → 归一化内容坐标，越界时收敛到 [0,1]。
     * 用于拖动/抬起：手势一旦开始，即使指针拖出内容区也要继续上报，
     * 否则手机侧会留下一个永远收不到 UP 的"悬空手指"。
     */
    fun toNormalizedClamped(px: Int, py: Int, fitted: Fitted): Normalized = Normalized(
        x = ((px - fitted.x).toFloat() / fitted.width).coerceIn(0f, 1f),
        y = ((py - fitted.y).toFloat() / fitted.height).coerceIn(0f, 1f),
    )
}
