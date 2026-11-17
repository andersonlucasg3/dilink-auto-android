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

    /**
     * issue #203：AOSP `VirtualDisplayConfig.Builder` 的真实方法名是
     * `setRequestedRefreshRate(float)`（API 33+）。旧实现只反射 `setRefreshRate`，
     * 该方法在 AOSP 上不存在，导致刷新率配置项从引入起就恒为 false。
     */
    private class FakeRequestedRefreshRateBuilder {
        var rate: Float = -1f
            private set

        fun setRequestedRefreshRate(r: Float) {
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
    fun `applies refresh rate through AOSP setRequestedRefreshRate`() {
        val builder = FakeRequestedRefreshRateBuilder()
        assertTrue(
            "AOSP 的 Builder 只有 setRequestedRefreshRate，必须优先走这条路径",
            applyRefreshRate(builder, builder.javaClass, 30f)
        )
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

    // ── issue #203：兜底分支的 Surface.setFrameRate 请求 ──

    @Test
    fun `surface frame rate is requested on API 30 and above`() {
        val applied = mutableListOf<Float>()
        val ok = applySurfaceFrameRate(
            rate = 30f,
            sdkInt = 30,
            setter = FrameRateSetter { applied += it },
        )
        assertTrue(ok)
        assertEquals(listOf(30f), applied)
    }

    @Test
    fun `surface frame rate is skipped below API 30`() {
        val applied = mutableListOf<Float>()
        val logs = mutableListOf<String>()
        val ok = applySurfaceFrameRate(
            rate = 30f,
            sdkInt = 29,
            setter = FrameRateSetter { applied += it },
            log = { logs += it },
        )
        assertFalse(ok)
        assertTrue("API 29 上绝不能真的调用（会 NoSuchMethodError）", applied.isEmpty())
        assertTrue(logs.single().contains("API 29"))
    }

    @Test
    fun `surface rejection is downgraded to a log line`() {
        val logs = mutableListOf<String>()
        val ok = applySurfaceFrameRate(
            rate = 30f,
            sdkInt = 34,
            setter = FrameRateSetter { throw IllegalArgumentException("Surface is not valid") },
            log = { logs += it },
        )
        assertFalse(ok)
        assertTrue(logs.single().contains("Surface.setFrameRate failed"))
    }

    @Test
    fun `non positive rate is never sent to the surface`() {
        val applied = mutableListOf<Float>()
        assertFalse(applySurfaceFrameRate(0f, 34, FrameRateSetter { applied += it }))
        assertTrue(applied.isEmpty())
    }

    /**
     * 源码钉子（issue #203）：兜底分支必须真的在 VD 的输出 Surface 上请求帧率。
     *
     * `create()` 依赖 `DisplayManager`（JVM 单测里是空壳），行为无法直接跑；
     * 这条断言保证「调用点 + FIXED_SOURCE 兼容性标志 + AOSP 方法名」三者不会被
     * 后人顺手删掉 —— 删掉就会红。
     */
    @Test
    fun `DisplayManager fallback calls Surface setFrameRate with fixed source compatibility`() {
        val src = sourceOf("vd-server/src/main/java/com/dilinkauto/vdserver/VirtualDisplayCreator.kt")
        assertTrue(
            "兜底分支必须调用 applySurfaceFrameRate",
            src.contains("applySurfaceFrameRate("),
        )
        assertTrue(
            "必须用 FIXED_SOURCE：虚拟屏内容不是可变帧率的视频源",
            src.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"),
        )
        assertTrue(
            "必须真的发到 VD 的输出 Surface 上",
            src.contains("surface.setFrameRate("),
        )
        assertTrue(
            "AOSP 的 Builder 方法名是 setRequestedRefreshRate，候选表里不能丢",
            src.contains("listOf(\"setRequestedRefreshRate\", \"setRefreshRate\")"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceOf(relativePath: String): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${java.io.File(".").absolutePath}")
    }
}

