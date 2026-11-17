package com.dilinkauto.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * [SwingVideoView] 的帧交接测试（audit WIN-15）。
 *
 * 锁定的正是 WIN-05 修复的语义：解码线程投递来的 `BufferedImage` 是**复用**的
 * （JavaCV 的设计），所以视图必须在 [SwingVideoView.setFrame] 里立刻拷贝 ——
 * 源图随后被解码线程改写，绘制路径拿到的内容不能跟着变。
 *
 * 不启动真实绘制（不碰 EDT / paintComponent）：断言走与绘制完全同路的
 * `frameForPaintingForTest()` 取帧。
 */
class SwingVideoViewTest {

    private val red = Color.RED.rgb
    private val green = Color.GREEN.rgb

    @Test
    fun `源图被解码线程改写后绘制内容不受影响`() {
        val view = SwingVideoView()
        val source = image(8, 8, red)
        view.setFrame(source)

        // 模拟 JavaCV 复用同一张缓冲：解码线程在下一帧原地改写源图
        val g = source.createGraphics()
        g.color = Color.GREEN
        g.fillRect(0, 0, source.width, source.height)
        g.dispose()

        val painted = view.frameForPaintingForTest()!!
        assertEquals(
            "setFrame 必须已经拷贝 —— 源图改写不得影响绘制内容（WIN-05）",
            0xFF0000,
            painted.getRGB(0, 0) and 0xFFFFFF,
        )
    }

    @Test
    fun `新帧到达后绘制内容跟随刷新`() {
        val view = SwingVideoView()
        view.setFrame(image(8, 8, red))
        assertEquals(0xFF0000, view.frameForPaintingForTest()!!.getRGB(0, 0) and 0xFFFFFF)

        view.setFrame(image(8, 8, green))
        assertEquals(0x00FF00, view.frameForPaintingForTest()!!.getRGB(0, 0) and 0xFFFFFF)
    }

    @Test
    fun `还没有帧时绘制路径取不到图`() {
        assertNull(SwingVideoView().frameForPaintingForTest())
    }

    @Test
    fun `帧尺寸变化后绘制缓冲跟随重建`() {
        val view = SwingVideoView()
        view.setFrame(image(8, 4, red))
        val first = view.frameForPaintingForTest()!!
        assertEquals(8, first.width)
        assertEquals(4, first.height)

        view.setFrame(image(4, 8, green))
        val second = view.frameForPaintingForTest()!!
        assertEquals(4, second.width)
        assertEquals(8, second.height)
        assertEquals(0x00FF00, second.getRGB(0, 0) and 0xFFFFFF)
    }

    private fun image(width: Int, height: Int, rgb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR).apply {
            val g = createGraphics()
            g.color = Color(rgb)
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
}
