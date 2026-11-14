package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #153-PA / 候选 B 的纯 JVM 契约测试。
 *
 * `VirtualDisplayConfig.Builder` 是隐藏类，不能在 JVM 单测里真的创建 VD；
 * 所以把 `setRefreshRate` 的反射调用抽成 [applyRefreshRate]，用假 Builder
 * 验证「方法存在时确实被调用」「方法缺失时只返回 false 且不抛」。
 */
class VirtualDisplayRefreshRateTest {

    private class FakeBuilder {
        var rate: Float = -1f
            private set

        fun setRefreshRate(r: Float) {
            rate = r
        }
    }

    private class BuilderWithoutRefreshRate

    private class ThrowingBuilder {
        fun setRefreshRate(r: Float) {
            throw IllegalStateException("setRefreshRate($r) rejected by ROM")
        }
    }

    @Test
    fun `applies refresh rate when builder exposes setRefreshRate`() {
        val builder = FakeBuilder()
        assertTrue(applyRefreshRate(builder, builder.javaClass, 30f))
        assertEquals(30f, builder.rate, 0f)
    }

    @Test
    fun `missing setRefreshRate returns false and logs instead of throwing`() {
        val logs = mutableListOf<String>()
        val result = applyRefreshRate(
            BuilderWithoutRefreshRate(),
            BuilderWithoutRefreshRate::class.java,
            30f,
        ) { logs += it }
        assertFalse(result)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("NoSuchMethodException"))
    }

    @Test
    fun `builder throwing is contained and reported as unsupported`() {
        // 反射调用抛异常时同样只记日志返回 false，绝不能把建屏流程带崩。
        val logs = mutableListOf<String>()
        val result = applyRefreshRate(ThrowingBuilder(), ThrowingBuilder::class.java, 30f) { logs += it }
        assertFalse(result)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("setRefreshRate unavailable"))
    }

    @Test
    fun `creator keeps default refresh rate for existing callers`() {
        // 既有单测与调用方都用命名参数，新增带默认值的 refreshRate 不能破坏构造。
        val legacy = VirtualDisplayCreator(
            displayWidth = 1920,
            displayHeight = 1080,
            dpi = 160,
            execShell = {},
            execShellOutput = { null },
            log = {},
            err = {},
        )
        val explicit = VirtualDisplayCreator(
            displayWidth = 1920,
            displayHeight = 1080,
            dpi = 160,
            refreshRate = 30f,
            execShell = {},
            execShellOutput = { null },
            log = {},
            err = {},
        )
        assertEquals(-1, legacy.displayId)
        assertEquals(-1, explicit.displayId)
    }
}
