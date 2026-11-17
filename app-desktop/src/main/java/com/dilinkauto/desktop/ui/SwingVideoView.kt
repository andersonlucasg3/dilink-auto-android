package com.dilinkauto.desktop.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * 视频画面组件：等比缩放（letterbox）绘制"最近一帧"解码图像，并把鼠标手势换算成
 * 归一化触摸坐标回调给上层。
 *
 * 职责边界（SRP）：只做"绘制 + 坐标换算"，不碰解码、网络与输入协议；
 * 帧由解码线程通过 [setFrame] 投递，手势通过构造参数的三个回调上报归一化坐标。
 * 放在 Compose `SwingPanel` 里使用——不每帧重建 Compose ImageBitmap（计划 6.3）。
 *
 * 帧交接（audit WIN-05）：解码线程交给我们的那张 `BufferedImage` 是**复用的**
 * （JavaCV 的设计），所以本组件在 [setFrame] 里立刻拷一份自己的副本；绘制在 EDT 上
 * 从 EDT 独占的缓冲进行 —— 三条线程各碰各的图，没有共享可变像素。
 */
class SwingVideoView(
    private val onTouchDown: (Float, Float) -> Unit = { _, _ -> },
    private val onTouchMove: (Float, Float) -> Unit = { _, _ -> },
    private val onTouchUp: (Float, Float) -> Unit = { _, _ -> },
) : JComponent() {

    /**
     * 帧交接锁（audit WIN-05）。
     *
     * 解码线程写 [latest]、EDT 取帧都在它内部完成，所以"解码线程正在改写的那张图"
     * 不可能同时被 EDT 读到。锁只覆盖两次同尺寸像素拷贝（各约 1ms @720p），
     * 绘制本身在锁外进行。
     */
    private val frameLock = Any()

    /** 解码线程投递来的最新一帧（本组件自己持有的副本，不再受解码线程影响）。 */
    private var latest: BufferedImage? = null

    /** EDT 独占的绘制缓冲：绘制在锁外发生，所以不能直接画 [latest]。 */
    private var paintBuffer: BufferedImage? = null
    private var paintStale = true

    /** 手势是否已开始（左键在内容区内按下）。 */
    private var dragging = false

    init {
        isOpaque = true
        background = Color.BLACK
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val n = normalizedOrNull(e.x, e.y) ?: return
                dragging = true
                onTouchDown(n.x, n.y)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || !dragging) return
                dragging = false
                // 抬起时即使指针已在黑边上也继续上报（clamp），避免手机侧手指卡住。
                val n = normalizedClamped(e.x, e.y)
                onTouchUp(n.x, n.y)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (!dragging) return
                val n = normalizedClamped(e.x, e.y)
                onTouchMove(n.x, n.y)
            }
        })
    }

    /**
     * 投递最新一帧并请求重绘（可在任意线程调用，`repaint` 线程安全且会合并刷新）。
     *
     * **拷贝是必须的**：JavaCV 的 `Java2DFrameConverter` 对所有帧复用同一张
     * `BufferedImage`（javap 反汇编 javacv 1.5.10 证实），解码线程下一帧就会改写它，
     * 而 Swing 的绘制在 EDT 上异步发生 —— 只交换引用等于把一张正在被改写的图交出去
     * （撕裂，audit WIN-05）。拷贝量约一帧像素（720p ≈ 3.7MB），远低于每帧新建
     * `BufferedImage` 的分配 + GC 代价（那才是报告里不建议的做法）。
     */
    fun setFrame(image: BufferedImage) {
        synchronized(frameLock) {
            latest = blitInto(image, latest)
            paintStale = true
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val image = frameForPainting() ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val f = Letterbox.fit(width, height, image.width, image.height)
            g2.drawImage(image, f.x, f.y, f.width, f.height, null)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 取一张可安全绘制的图：在锁内把最新一帧拷进 EDT 独占的 [paintBuffer]。
     * 没有新帧时直接复用上次那张（复用期间解码线程只写 [latest]）。
     */
    private fun frameForPainting(): BufferedImage? = synchronized(frameLock) {
        val source = latest ?: return null
        if (paintStale) {
            paintBuffer = blitInto(source, paintBuffer)
            paintStale = false
        }
        paintBuffer
    }

    /**
     * 测试可见（audit WIN-15）：与 [paintComponent] 完全同路的一次取帧，
     * 用于断言"源图改写不影响绘制内容"（WIN-05 的回归防线）。生产代码不要调用。
     */
    internal fun frameForPaintingForTest(): BufferedImage? = frameForPainting()

    /** 内容区内的归一化坐标；点黑边或尚无画面时返回 null。 */
    private fun normalizedOrNull(px: Int, py: Int): Letterbox.Normalized? {
        val (w, h) = latestSize() ?: return null
        return Letterbox.toNormalized(px, py, Letterbox.fit(width, height, w, h))
    }

    /** 归一化坐标（越界收敛到边界）；尚无画面时回落到 (0,0)。 */
    private fun normalizedClamped(px: Int, py: Int): Letterbox.Normalized {
        val (w, h) = latestSize() ?: return Letterbox.Normalized(0f, 0f)
        return Letterbox.toNormalizedClamped(px, py, Letterbox.fit(width, height, w, h))
    }

    /** 最近一帧的像素尺寸；尚无画面时为 null。 */
    private fun latestSize(): Pair<Int, Int>? = synchronized(frameLock) {
        latest?.let { it.width to it.height }
    }

    /**
     * 把 [source] 的像素拷进 [target]（尺寸/类型不符时新建一张），返回承载结果的缓冲。
     *
     * `AlphaComposite.Src` 让目标被完整覆盖，不做混合 —— 同尺寸下就是一次逐行 blit。
     */
    private fun blitInto(source: BufferedImage, target: BufferedImage?): BufferedImage {
        val out = if (target == null ||
            target.width != source.width ||
            target.height != source.height ||
            target.type != source.type
        ) {
            BufferedImage(source.width, source.height, source.type)
        } else {
            target
        }
        val g = out.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
