package com.dilinkauto.desktop.video

import com.dilinkauto.desktop.log.DesktopLog
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 联调钩子（audit R3-SRP-12，从 `DesktopApp` 文件移出）：`DILINK_DUMP_FRAME=<png 路径>`
 * 时把第 N 帧画面落盘，`DILINK_DUMP_FRAME_AT` 指定第几帧（默认 96 ≈ 解码开始 4 秒后；
 * VD 里的 App 需要时间投射，联调时可调大等到画面就绪）。锁屏/显示器休眠时无法截屏，
 * 用解码输出来证明"画面是真内容"。不设置则零行为变化。
 */
internal class FrameDumper(private val log: DesktopLog) {

    private val path = System.getenv("DILINK_DUMP_FRAME")?.takeIf { it.isNotBlank() }?.let(::File)
    private val at = System.getenv("DILINK_DUMP_FRAME_AT")?.toLongOrNull() ?: 96L
    private var done = false

    fun maybeDump(image: BufferedImage, framesDecoded: Long) {
        val target = path ?: return
        if (done || framesDecoded < at) return
        done = true
        runCatching { ImageIO.write(image, "png", target) }
            .onSuccess { log.info("video", "解码帧已落盘: ${target.absolutePath}") }
            .onFailure { log.warn("video", "解码帧落盘失败: ${it.message}") }
    }
}
