package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the VD viewport math, with special weight on the 2026-10-09
 * chopped-keyboard regression: the caller must pass the phone's **real
 * physical** size. Feeding the compat-scaled value (2992 instead of 3192 for
 * the test phone) produced a VD 200px (~6.7%) narrower than the IME, which
 * Chinese ROMs hardcode to the physical width regardless of DPI — the exact
 * bug seen at DPI 120/200/300/400 alike.
 */
class VdDimensionsTest {

    /** Car 1920x1080 @160dpi, no override — the default dev-harness shape. */
    private fun defaultRequest(
        carW: Int = 1920,
        carH: Int = 1080,
        carDpi: Int = 160,
        dpiOverride: Int = 0,
    ) = HandshakeRequest(
        deviceName = "test-car",
        screenWidth = carW,
        screenHeight = carH,
        screenDpi = carDpi,
        appVersionCode = 1,
        dpiOverride = dpiOverride,
    )

    @Test
    fun realPhonePortrait_carLandscape_scalesToTruePhysicalLongEdge() {
        // 2026-10-09 的回归钉：调用方必须传真实物理尺寸而不是兼容缩放值。
        // issue #71 起放大封顶 2560，3192 的机型已经分不出 3192 / 2992（两边都被封顶），
        // 所以这条钉子改用长边 2400 的手机（兼容模式会报 2200 左右）。
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 1080, 2400)
        assertEquals(2400, w)
        assertEquals(1350, h) // 1080 * 2400/1920
        // 车机密度 160 按放大倍数 1.25 缩放成 200（#20）
        assertEquals(200, dpi)
    }

    @Test
    fun landscapePhone_usesLongEdgeAsPhysicalWidthToo() {
        // 3192 长边超过封顶 2560 → 只放大到 2560；1080 * 2560/1920 = 1440（#71）
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 3192, 1368)
        assertEquals(2560, w)
        assertEquals(1440, h)
        // 160 * (2560/1920) = 213，仍落在 120..320 带内且 maxSafe=320 不截断
        assertEquals(213, dpi)
    }

    @Test
    fun carWiderThanPhone_neverScalesDown() {
        // Car 2560x1440 vs phone landscape 2400x1080: the anti-crop rule only
        // scales UP; the VD stays at the car viewport.
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 2560, carH = 1440), 2400, 1080
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
        assertEquals(160, dpi)
    }

    @Test
    fun portraitCar_landscapePhone_crossOrientationUsesPhoneShortEdge() {
        // Car 720x1280 (portrait) vs phone 2160x1080 (landscape): orientation
        // mismatch resolves the phone's "width" to its short edge 1080.
        // Scale 1.5 is float-exact: 720->1080, 1280->1920.
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 720, carH = 1280), 2160, 1080
        )
        assertEquals(1080, w)
        assertEquals(1920, h)
        // 放大 1.5 倍后车机密度 160 -> 240（#20）
        assertEquals(240, dpi)
    }

    @Test
    fun portraitCar_portraitPhone_scalesUpByExactFactor() {
        // 1620/1080 = 1.5 exactly: no float rounding ambiguity.
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1080, carH = 1920), 1620, 3120
        )
        assertEquals(1620, w)
        assertEquals(2880, h)
    }

    @Test
    fun oddCarViewport_isEvenAlignedBeforeScaling() {
        // 1921x1081 evens to 1920x1080 first, then follows the standard path (封顶 2560).
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1921, carH = 1081), 1368, 3192
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
    }

    @Test
    fun dpiOverride_bypassesAutoButNeverChangesSize() {
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(dpiOverride = 300), 1368, 3192
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
        assertEquals(300, dpi)
    }

    @Test
    fun dpiOverride_beyondBoundsIsClampedTo480() {
        val (_, _, dpi) = VdDimensions.compute(
            defaultRequest(dpiOverride = 999), 1368, 3192
        )
        assertEquals(480, dpi)
    }

    @Test
    fun carSmallViewport_reportedDpiScalesWithVdScale() {
        // 实车现场（#20 复现 / #71 发烫）：车机 1204x660 @160dpi，手机真实 1368x3192。
        // 封顶前 VD 会放大 2.6512 倍到 3192x1748（每帧 5.58MP，实测持续发烫）；
        // issue #71 封顶 2560 → 2560x1402（-36% 合成像素），车机密度同步放大到 340，
        // 落出 120..320 带后由 golden 公式给出 302。
        // 显式钉在兼容档：issue #71 的 2560 上限必须原样保留（PB 默认档另测）。
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1368, 3192, VdAreaTier.COMPAT_2560
        )
        assertEquals(2560, w)
        assertEquals(1402, h)
        assertEquals(302, dpi)
    }

    @Test
    fun upscaleIsCappedAtMaxUpscaledWidth() {
        // 手机物理长边远超上限时，VD 只到 [VdDimensions.MAX_UPSCALED_WIDTH]。
        // 代价：输入法按物理宽渲染，会在这里被裁掉 3200-2560 = 640px（约 20%），
        // 这是 issue #71 明确接受的取舍（发烫 vs 键盘）。兼容档必须保持这条不变。
        val (w, _, _) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1440, 3200, VdAreaTier.COMPAT_2560
        )
        assertEquals(VdDimensions.MAX_UPSCALED_WIDTH, w)
    }

    @Test
    fun carDpiOutsideAutoBand_fallsBackToGoldenDpi() {
        // 480 放大后是 640，落出 [120,320] → golden = 1440*0.52*160/385 = 311，
        // maxSafe = 1440*80/360 = 320，所以 golden 值原样保留。
        val (_, _, dpi) = VdDimensions.compute(
            defaultRequest(carDpi = 480), 1368, 3192
        )
        assertEquals(311, dpi)
    }

    @Test
    fun imeWidthInvariant_holdsUpToTheUpscaleCap() {
        // anti-crop 的本意：VD 长边 >= 手机物理长边（IME 按物理宽渲染）。
        // issue #71 起放大封顶 2560，超过上限的机型必然裁掉一部分键盘 ——
        // 这里钉住「上限以内仍然满足」，超上限的行为由 upscaleIsCappedAtMaxUpscaledWidth 覆盖。
        val fixtures = listOf(
            Triple(1368, 3192, 3192L), // portrait phone, long edge 3192
            Triple(3192, 1368, 3192L), // landscape phone, long edge 3192
        )
        for ((pw, ph, longEdge) in fixtures) {
            val (w, _, _) = VdDimensions.compute(
                defaultRequest(), pw, ph, VdAreaTier.COMPAT_2560
            )
            val required = minOf(longEdge, VdDimensions.MAX_UPSCALED_WIDTH.toLong())
            assertTrue(
                "VD width $w must be >= $required (phone ${pw}x$ph, cap ${VdDimensions.MAX_UPSCALED_WIDTH})",
                w >= required
            )
        }
    }

    // ─── issue #153 分节 PB：VD 面积静态档位 ───

    @Test
    fun autoTier_capsAtCarWidthTimes1_6_andShrinksRealCarCompositeArea() {
        // 实车 1204x660@160dpi + 手机物理 1368x3192：
        // cap = max(1204*8/5=1926, 1600) = 1926 → 1926x1054 @234dpi，约 2.03MP
        // （兼容档 2560x1402 = 3.59MP，故 -43.4%）。
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1368, 3192, VdAreaTier.AUTO
        )
        assertEquals(1926, w)
        assertEquals(1054, h)
        assertEquals(234, dpi)
        assertEquals(2.030004, w.toLong() * h / 1_000_000.0, 0.000001)
    }

    @Test
    fun compatTier_preservesIssue71LegacyCapOnSameInput() {
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1368, 3192, VdAreaTier.COMPAT_2560
        )
        assertEquals(2560, w)
        assertEquals(1402, h)
        assertEquals(302, dpi)
    }

    @Test
    fun tierCap_isTableDrivenAndNeverExceedsHardCap() {
        val cases = listOf(
            0 to 1600,      // 非法输入也落到下限
            -5 to 1600,
            720 to 1600,    // 1.6x=1152 < 1600 下限
            1000 to 1600,   // 1.6x=1600 恰好等于下限
            1204 to 1926,   // 实车
            1205 to 1928,   // tierCap 本身不做偶数对齐；compute 传的是已对齐的宽
            1600 to 2560,   // 1.6x=2560
            1920 to 2560,   // 1.6x=3072 被硬上限截住
            2560 to 2560,
        )
        for ((carWidth, expected) in cases) {
            assertEquals(
                "AUTO cap for car $carWidth", expected,
                VdDimensions.tierCap(carWidth, VdAreaTier.AUTO)
            )
            assertEquals(
                "COMPAT cap for car $carWidth", VdDimensions.MAX_UPSCALED_WIDTH,
                VdDimensions.tierCap(carWidth, VdAreaTier.COMPAT_2560)
            )
        }
    }

    @Test
    fun autoTier_neverScalesDownAWideCarViewport() {
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 2560, carH = 1440), 2400, 1080, VdAreaTier.AUTO
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
        assertEquals(160, dpi)
    }

    @Test
    fun autoTier_minimumWidthFloorAppliesToNarrowCar() {
        // 车机 720x1280（竖屏）+ 手机 1600x3200（竖屏）：同向 → phonePhysicalWidth = 1600，
        // AUTO cap 由 1600 下限决定；若下限失效会退化成 720*1.6 = 1152。
        val (w, _, _) = VdDimensions.compute(
            defaultRequest(carW = 720, carH = 1280), 1600, 3200, VdAreaTier.AUTO
        )
        assertEquals(1600, w)
    }

    @Test
    fun autoTier_keepsOutputEvenAligned() {
        // 奇数车机视口先偶数对齐成 1204x660，因此 cap 仍是 1926 而不是 1928。
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1205, carH = 661), 1368, 3192, VdAreaTier.AUTO
        )
        assertEquals(1926, w)
        assertEquals(1054, h)
        assertEquals(0, w and 1)
        assertEquals(0, h and 1)
    }

    @Test
    fun dpiOverrideIsTierIndependent() {
        for (tier in listOf(VdAreaTier.AUTO, VdAreaTier.COMPAT_2560)) {
            val (_, _, dpi) = VdDimensions.compute(
                defaultRequest(carW = 1204, carH = 660, dpiOverride = 300),
                1368, 3192, tier
            )
            assertEquals(300, dpi)
        }
    }

    @Test
    fun unknownTierFallsBackToAuto() {
        for (raw in listOf(-1, 2, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(VdAreaTier.AUTO, VdAreaTier.normalize(raw))
            assertEquals(
                VdDimensions.tierCap(1204, VdAreaTier.AUTO),
                VdDimensions.tierCap(1204, raw)
            )
        }
        val (w, _, _) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1368, 3192, 99
        )
        assertEquals(1926, w)
    }

    @Test
    fun imeWidthInvariant_autoTierUsesItsOwnCap() {
        // 默认档把上限压小，但「VD 宽 >= min(手机物理长边, 该档上限)」仍必须成立。
        val fixtures = listOf(Triple(1368, 3192, 3192L), Triple(3192, 1368, 3192L))
        for ((pw, ph, longEdge) in fixtures) {
            val (w, _, _) = VdDimensions.compute(
                defaultRequest(carW = 1204, carH = 660), pw, ph, VdAreaTier.AUTO
            )
            val required = minOf(longEdge, VdDimensions.tierCap(1204, VdAreaTier.AUTO).toLong())
            assertTrue("VD width $w must be >= $required (phone ${pw}x$ph)", w >= required)
        }
    }

    @Test
    fun mpixPerSec_isFullSpeedTheoreticalThroughput() {
        // 精确值；口径是「按 fps 满速」，静止短路后实际合成拍数远低于 fps，
        // 温升归因要看 idleSkip/ticks/frames 增长率，不能只看这个数。
        assertEquals(107.6736, VdDimensions.mpixPerSec(2560, 1402, 30), 0.000001)
        assertEquals(60.90012, VdDimensions.mpixPerSec(1926, 1054, 30), 0.000001)
        assertEquals(0.0, VdDimensions.mpixPerSec(0, 0, 30), 0.000001)
    }
}
