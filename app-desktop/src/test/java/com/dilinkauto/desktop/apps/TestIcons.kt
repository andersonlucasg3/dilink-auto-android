package com.dilinkauto.desktop.apps

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 测试用的假 PNG 图标：生成一张纯色图并编码成 PNG 字节。
 *
 * [width]/[height] 可调是为了构造"解压炸弹"形态的输入（D-M5）：一张标称尺寸
 * 远超上限的 PNG —— 它的字节数很小，但 `ImageIO.read` 会分配
 * width×height×4 的 BufferedImage。
 */
internal object TestIcons {

    fun pngBytes(
        argb: Int = 0xFF3366FF.toInt(),
        width: Int = 8,
        height: Int = 8,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) {
            for (y in 0 until height) image.setRGB(x, y, argb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * 只写 PNG header 的宽高、不编码像素的"超大图标"字节（D-M5）。
     *
     * 用真实位图编码 width×height 在测试里太慢（600x600 也要几十毫秒，而我们要
     * 的是远超 512 的尺寸）。这里手写 IHDR：PNG 的宽高是 header 里的 4 字节，
     * [AppIconStore.decodePng] 正是先读这里再决定要不要解码。
     */
    fun oversizedHeaderPng(width: Int, height: Int): ByteArray {
        val ihdrData = java.nio.ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8)    // bit depth
            .put(2)    // color type: truecolor
            .put(0)    // compression
            .put(0)    // filter
            .put(0)    // interlace
            .array()
        val out = java.io.ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ihdrData))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** PNG chunk = length(4) + type(4) + data + crc32(4)。解码不会到这里，但别写坏格式。 */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val len = java.nio.ByteBuffer.allocate(4).putInt(data.size).array()
        out.write(len)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        val crcInput = type.toByteArray(Charsets.US_ASCII) + data
        out.write(java.nio.ByteBuffer.allocate(4).putInt(crc32(crcInput)).array())
        return out.toByteArray()
    }

    /** PNG 用的 CRC-32（zlib 多项式，与 ImageIO 校验的那只一致）。 */
    private fun crc32(data: ByteArray): Int {
        var crc = -1 // 0xFFFFFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }
}
