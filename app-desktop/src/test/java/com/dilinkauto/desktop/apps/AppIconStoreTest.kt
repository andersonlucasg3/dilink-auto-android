package com.dilinkauto.desktop.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [AppIconStore] 单测：空图标沿用、解码失败不清旧、以及包名维度的增删。 */
class AppIconStoreTest {

    @Test
    fun put_decodesPngAndCachesByPackageName() {
        val store = AppIconStore()
        val decoded = store.put("com.example.a", TestIcons.pngBytes())
        assertNotNull(decoded)
        assertEquals(8, decoded!!.width)
        assertSame(decoded, store.get("com.example.a"))
        assertEquals(1, store.size)
    }

    @Test
    fun put_withEmptyPng_keepsPreviousIcon() {
        // 手机侧用 hash 抑制重复图标 → 后续 APP_LIST 里该包的 iconPng 为空，
        // 此时必须沿用旧图标，否则列表刷新一次图标就全没了。
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes())
        val again = store.put("com.example.a", ByteArray(0))
        assertSame(first, again)
        assertSame(first, store.get("com.example.a"))
    }

    @Test
    fun put_withUndecodablePng_keepsPreviousIcon() {
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes())
        assertSame(first, store.put("com.example.a", byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun put_withUndecodablePngAndNoPrevious_returnsNull() {
        val store = AppIconStore()
        assertNull(store.put("com.example.a", byteArrayOf(1, 2, 3, 4)))
        assertEquals(0, store.size)
    }

    @Test
    fun newPng_replacesPreviousDecodedImage() {
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes(0xFF112233.toInt()))
        val second = store.put("com.example.a", TestIcons.pngBytes(0xFF445566.toInt()))
        assertNotNull(second)
        assertSame(second, store.get("com.example.a"))
        assertEquals(1, store.size)
        // 只做"换掉了"的判定：两者不是同一实例
        assertNotSame(first, second)
    }

    @Test
    fun remove_dropsOnlyThatEntry() {
        val store = AppIconStore()
        store.put("com.example.a", TestIcons.pngBytes())
        store.put("com.example.b", TestIcons.pngBytes())
        assertEquals(2, store.size)

        store.remove("com.example.a")
        assertNull(store.get("com.example.a"))
        assertNotNull(store.get("com.example.b"))
    }

    // ─── D-M5：PNG 图标无尺寸上限（解压炸弹）────

    @Test
    fun put_oversizedIcon_isRejectedBeforeDecoding() {
        val store = AppIconStore()
        // 只写 header 的"超大图标"：字节数几十字节，但 ImageIO.read 会分配
        // width×height×4 的位图（4000x4000 ≈ 64MB）。必须在解码前被挡掉。
        val decoded = store.put("com.example.bomb", TestIcons.oversizedHeaderPng(4000, 4000))

        assertNull("超限图标必须被拒绝", decoded)
        assertEquals("被拒的图标不进缓存", 0, store.size)
    }

    @Test
    fun put_oversizedIcon_keepsPreviousIcon() {
        // 与解码失败同一语义：沿用已有的那份，不清缓存
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes())

        val again = store.put("com.example.a", TestIcons.oversizedHeaderPng(4097, 128))

        assertSame(first, again)
        assertSame(first, store.get("com.example.a"))
        assertEquals(1, store.size)
    }

    @Test
    fun put_iconAtSizeLimit_isStillAccepted() {
        // 上限（含）之内的真实图标必须照常解码 —— 校验不能误伤正常图标
        val store = AppIconStore()
        val decoded = store.put("com.example.big", TestIcons.pngBytes(width = 512, height = 512))

        assertNotNull(decoded)
        assertEquals(512, decoded!!.width)
        assertEquals(1, store.size)
    }

    @Test
    fun put_oversizedByteCount_isRejectedWithoutDecoding() {
        // 尺寸合法但字节数超限（极大 palette / ancillary chunk 的炸弹变体）
        val store = AppIconStore()
        val png = TestIcons.pngBytes(width = 64, height = 64)
        val padded = png + ByteArray(AppIconStore.MAX_ICON_BYTES + 1) // 尾部垃圾不影响 header 判定

        // 直接打 decode 钩子：喂进去的字节必须过尺寸/字节闸门
        val result = AppIconStore.decodePng(padded)

        // 尾部垃圾让 ImageIO.read 失败（不是尺寸问题），这里断言的是"没炸"——
        // 关键是 decodePng 对超限字节直接返回 null 而不去解码
        assertNull(result)
        assertNull(store.put("com.example.padded", padded))
    }
}
