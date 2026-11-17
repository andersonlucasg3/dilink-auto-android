package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 的"先红后绿"钉子（A 段：参数单一来源 + 越界 DPI 回落）。
 *
 * 这两个断言**在未修复的 base 上必须是红的**，且红的方式与 issue 现场一致：
 *  - `dpiOverride=480` 被原样采用（现场 `client.log:1630` → `VD: 1926x1054 @480dpi`）；
 *  - 设置页把越界输入 560 夹成协议上界 480（这就是 480 的来源：残留值看起来合法）。
 *
 * 修复后：480 超过本视口安全上限（1926x1054 → 234dpi），回落到自动标定值；
 * 越界输入回落 Auto（0），不再制造"合法但荒谬"的边界值。
 */
class Issue202RedFirstTest {

    private fun request(dpiOverride: Int) = HandshakeRequest(
        deviceName = "test-car",
        screenWidth = 1204,
        screenHeight = 660,
        screenDpi = 160,
        appVersionCode = 1,
        dpiOverride = dpiOverride,
    )

    @Test
    fun `override 480 on the 1926x1054 vd must not be honoured verbatim`() {
        // 现场：车机 1204x660 @160dpi，手机真实 1368x3192，默认档 → VD 1926x1054。
        val (w, h, dpi) = VdDimensions.compute(request(dpiOverride = 480), 1368, 3192, VdAreaTier.AUTO)
        assertEquals(1926, w)
        assertEquals(1054, h)
        // 本视口安全上限 = 1054*80/360 = 234；480 会被压成 ~140dp 逻辑宽的细条。
        assertNotEquals("480 超过本视口安全上限，不能原样生效", 480, dpi)
        assertEquals(VideoConfig.maxSafeDpi(w, h), dpi)
    }

    @Test
    fun `an out-of-range settings dpi falls back to Auto instead of the boundary`() {
        // 用户手滑输入 560（= 手机自身密度）曾被夹成 480 并持久化。
        assertEquals(SettingsFormat.DPI_AUTO, SettingsFormat.coerceDpi(560))
        assertEquals(SettingsFormat.DPI_AUTO, SettingsFormat.coerceDpi(50))
        // 合法值原样保留。
        assertEquals(240, SettingsFormat.coerceDpi(240))
        assertEquals(SettingsFormat.DPI_AUTO, SettingsFormat.coerceDpi(SettingsFormat.DPI_AUTO))
        assertTrue(VdDeployArgs.DPI_OVERRIDE_MIN < VdDeployArgs.DPI_OVERRIDE_MAX)
    }
}
