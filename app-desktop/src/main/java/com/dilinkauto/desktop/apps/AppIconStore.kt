package com.dilinkauto.desktop.apps

import com.dilinkauto.protocol.AppIconLimits
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 应用图标缓存（会话内内存缓存，包名 → 已解码位图）。
 *
 * ── 为什么可以用内存缓存 ──
 * 手机侧 `AppListBuilder` 用 `lastSentIconHash` 按 `packageName.lastUpdateTime`
 * 抑制重复图标，且 `ConnectionService.cleanupSession()` 会调 `resetIconHashes()`
 * ——也就是说**每次重连都会重发全部图标**。因此桌面端不需要磁盘缓存，
 * 只要在会话存续期间记住已解码的位图即可；跨会话丢失也无所谓。
 *
 * ── 空图标语义 ──
 * `AppInfo.iconPng` 为空表示"手机侧认为你已经有这个图标了"（hash 未变，被抑制）。
 * 这时必须沿用上一次的位图，而不是把已有图标清掉，否则列表刷新一次图标就全没了。
 *
 * 线程模型：由数据帧分发协程（IO）单侧写入、UI 线程读取，故用 [ConcurrentHashMap]。
 */
class AppIconStore(
    private val decode: (ByteArray) -> BufferedImage? = ::decodePng,
) {
    private val icons = ConcurrentHashMap<String, BufferedImage>()

    /**
     * 存入（或沿用）某个包的图标，返回该包当前生效的位图。
     *
     * @param png 手机下发的 PNG 字节；为空或解码失败时沿用已有缓存。
     * @return 当前生效的位图，从未有过则为 null。
     */
    fun put(packageName: String, png: ByteArray): BufferedImage? {
        val existing = icons[packageName]
        if (png.isEmpty()) return existing
        val decoded = decode(png) ?: return existing
        icons[packageName] = decoded
        return decoded
    }

    fun get(packageName: String): BufferedImage? = icons[packageName]

    fun remove(packageName: String) {
        icons.remove(packageName)
    }

    // 没有 clear()：跨会话清缓存靠新建 store 实例（每代 AppCatalog 新的一个，
    // 见 AppCatalog 的 KDoc）。此前那个只被测试调用的 clear() 已删（audit WIN-10）。

    /** 当前缓存的图标数（供日志/测试断言用）。 */
    val size: Int get() = icons.size

    companion object {
        /**
         * 单张图标解码前的宽高上限（audit D-M5，引用 [AppIconLimits.MAX_ICON_DIM]）。
         *
         * PNG 是压缩格式，"几十 KB 的文件"完全可以是几千像素宽的位图 ——
         * 一张 8000x8000 的解码结果是 ~256MB 的 BufferedImage，而唯一的旧边界
         * （协议帧 128MB）远不够拦。应用列表图标 512 已经远超实际需要（手机
         * 启动器图标最大 192dp，约 1080px 的极端情况也不过 512 的一倍）。
         */
        const val MAX_ICON_DIM = AppIconLimits.MAX_ICON_DIM

        /**
         * 单张图标的**原始字节**上限（audit D-M5，引用 [AppIconLimits.MAX_ICON_BYTES]）。
         *
         * 解压炸弹的另一种形态是"合法尺寸、但被刻意撑大的 PNG"（极大 palette /
         * ancillary chunk）。1MB 足够任何真实图标（512x512 的 PNG 通常 < 300KB）。
         */
        const val MAX_ICON_BYTES = AppIconLimits.MAX_ICON_BYTES

        /**
         * 默认解码器：把 PNG 字节解成 [BufferedImage]。失败（或不合法）返回 null，
         * 由调用方决定回退策略。
         *
         * **先读 header 再解码**（audit D-M5）：[ImageIO.read] 本身没有任何尺寸
         * 限额，所以先用 [ImageIO.createImageInputStream] + `ImageReader` 取宽高，
         * 超限直接拒解码 —— 炸弹在分配位图**之前**就被挡住。
         */
        fun decodePng(png: ByteArray): BufferedImage? {
            if (png.size > AppIconLimits.MAX_ICON_BYTES) return null
            val header = runCatching { readHeader(png) }.getOrNull() ?: return null
            val (width, height) = header
            // getWidth/getHeight 在 header 不完整时可能返回 -1：同样按"拒解码"处理
            if (width <= 0 || height <= 0) return null
            if (width > AppIconLimits.MAX_ICON_DIM || height > AppIconLimits.MAX_ICON_DIM) return null
            return runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
        }

        /**
         * 读 PNG header 拿宽高；读不出来（格式不认识 / 文件过短）返回 null。
         *
         * 用 [ImageIO.getImageReaders] 逐个问"你能读这个吗"而不是按扩展名猜 ——
         * 输入是网络字节，没有文件名可依。
         */
        private fun readHeader(png: ByteArray): Pair<Int, Int>? {
            val stream = ImageIO.createImageInputStream(ByteArrayInputStream(png))
                ?: return null
            stream.use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                // ImageReader 不是 kotlin.io.use 的 Closeable（平台类型窄化问题），
                // 用 try/finally + dispose() 显式收尾。
                try {
                    reader.input = input
                    return reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    runCatching { reader.dispose() }
                }
            }
        }
    }
}
