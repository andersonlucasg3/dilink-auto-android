package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the car settings UI's value ⇄ display maths (audit UX-06, UX-02).
 *
 * The car screen previously rendered the bitrate with integer division
 * (`startupBitrate / 1_000_000`), so the 2.5 Mbps preset and the 2 Mbps preset
 * both displayed as "2M" — the user could not tell which one was in effect.
 * These tests pin the label to the stored value, and pin the manual-entry
 * coercion to the shared [VideoConfig] / [VdDeployArgs] bounds.
 */
class SettingsFormatTest {

    @Test
    fun bitrateLabel_distinguishesEveryPreset() {
        val labels = SettingsFormat.BITRATE_PRESETS.map(SettingsFormat::formatBitrateMbps)
        assertEquals(listOf("2M", "2.5M", "3M", "4M", "6M"), labels)
        // The regression that motivated the extraction: 2M and 2.5M must differ.
        assertEquals(5, labels.distinct().size)
    }

    @Test
    fun bitrateLabel_neverRendersWholeValuesWithADecimalPoint() {
        for (bps in SettingsFormat.BITRATE_PRESETS) {
            val label = SettingsFormat.formatBitrateMbps(bps)
            if (bps % 1_000_000 == 0) {
                assertTrue("$bps -> $label must not carry a decimal point", !label.contains('.'))
            }
        }
        assertEquals("4M", SettingsFormat.formatBitrateMbps(VideoConfig.DEFAULT_BITRATE))
    }

    @Test
    fun bitrateLabel_keepsTwoDecimalsWhenTheStoredValueIsOdd() {
        // A hand-edited or legacy config can hold any bps value.
        assertEquals("3.33M", SettingsFormat.formatBitrateMbps(3_333_000))
        assertEquals("2.56M", SettingsFormat.formatBitrateMbps(2_555_000))
        assertEquals("1.05M", SettingsFormat.formatBitrateMbps(1_050_000))
        assertEquals("0.5M", SettingsFormat.formatBitrateMbps(500_000))
    }

    @Test
    fun bitrateLabel_roundsUpAcrossTheWholeBoundary() {
        // 1.999M must not render as "1.99M" *and* as "2M" for 2.0M — one label
        // per whole number, rounding up at the top two decimals.
        assertEquals("2M", SettingsFormat.formatBitrateMbps(1_999_000))
        assertEquals("2M", SettingsFormat.formatBitrateMbps(1_995_000))
        assertEquals("2M", SettingsFormat.formatBitrateMbps(2_000_000))
    }

    @Test
    fun coerceBitrate_clampsToTheUiAcceptedRange() {
        assertEquals(VideoConfig.MIN_BITRATE, SettingsFormat.coerceBitrate(0))
        assertEquals(VideoConfig.MIN_BITRATE, SettingsFormat.coerceBitrate(-1))
        assertEquals(VideoConfig.MIN_BITRATE, SettingsFormat.coerceBitrate(VideoConfig.MIN_BITRATE))
        assertEquals(VideoConfig.MAX_BITRATE, SettingsFormat.coerceBitrate(999_000_000))
        // The manual-entry field produces whole Mbps values; all presets except
        // 2.5M come from it, and none of them may be clamp-rewritten.
        for (mbps in 1..12) {
            assertEquals(mbps * 1_000_000, SettingsFormat.coerceBitrate(mbps * 1_000_000))
        }
        // ...and every preset is inside the accepted range, so selecting a chip
        // never stores a value the field would refuse to show back.
        for (bps in SettingsFormat.BITRATE_PRESETS) {
            assertEquals(bps, SettingsFormat.coerceBitrate(bps))
        }
    }

    @Test
    fun coerceFps_clampsToTheUiAcceptedRange() {
        assertEquals(VideoConfig.MIN_FPS, SettingsFormat.coerceFps(0))
        assertEquals(VideoConfig.MIN_FPS, SettingsFormat.coerceFps(1))
        assertEquals(VideoConfig.MAX_FPS, SettingsFormat.coerceFps(1_000))
        for (fps in SettingsFormat.FPS_PRESETS) {
            assertEquals(fps, SettingsFormat.coerceFps(fps))
        }
    }

    @Test
    fun coerceDpi_rejectsOutOfRangeInsteadOfClamping() {
        // issue #202：设置项的语义是"用户输入是否可用"，越界回落 Auto，不夹到边界。
        // 夹到边界（560 → 480）正是现场 @480dpi 残留值的制造方式。
        assertEquals(SettingsFormat.DPI_AUTO, SettingsFormat.coerceDpi(0))
        for (dpi in listOf(1, 60, 119, 481, 560, 10_000)) {
            assertEquals("dpi=$dpi 应回落 Auto", SettingsFormat.DPI_AUTO, SettingsFormat.coerceDpi(dpi))
        }
        for (dpi in SettingsFormat.DPI_PRESETS) {
            assertEquals(dpi, SettingsFormat.coerceDpi(dpi))
        }
        assertEquals(VdDeployArgs.DPI_OVERRIDE_MIN, SettingsFormat.coerceDpi(VdDeployArgs.DPI_OVERRIDE_MIN))
        assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, SettingsFormat.coerceDpi(VdDeployArgs.DPI_OVERRIDE_MAX))
    }

    @Test
    fun everyDpiPresetIsEitherAutoOrInsideTheOverrideRange() {
        for (dpi in SettingsFormat.DPI_PRESETS) {
            assertTrue(
                "dpi=$dpi must be 0 (Auto) or within 120..480",
                dpi == SettingsFormat.DPI_AUTO || dpi in VdDeployArgs.DPI_OVERRIDE_MIN..VdDeployArgs.DPI_OVERRIDE_MAX
            )
        }
    }

    @Test
    fun manualSeed_isEmptyForEveryPreset() {
        // The empty field means "a chip is selected"; a preset that seeded the
        // field would render both a highlighted chip and a duplicate number.
        for (bps in SettingsFormat.BITRATE_PRESETS) {
            assertEquals("", SettingsFormat.manualSeed(bps, SettingsFormat.BITRATE_PRESETS, 1_000_000))
        }
        for (dpi in SettingsFormat.DPI_PRESETS) {
            assertEquals("", SettingsFormat.manualSeed(dpi, SettingsFormat.DPI_PRESETS))
        }
        for (fps in SettingsFormat.FPS_PRESETS) {
            assertEquals("", SettingsFormat.manualSeed(fps, SettingsFormat.FPS_PRESETS))
        }
    }

    @Test
    fun manualSeed_showsTheStoredValueInTheFieldsOwnUnit() {
        // DPI and FPS fields are typed in the stored unit...
        assertEquals("175", SettingsFormat.manualSeed(175, SettingsFormat.DPI_PRESETS))
        assertEquals("45", SettingsFormat.manualSeed(45, SettingsFormat.FPS_PRESETS))
        // ...the bitrate field is typed in Mbps while the value is stored in bps.
        assertEquals("5", SettingsFormat.manualSeed(5_000_000, SettingsFormat.BITRATE_PRESETS, 1_000_000))
        // A legacy hand-edited value that matches no preset still seeds cleanly,
        // rather than leaking the display label's digits ("3.33M" -> "333").
        assertEquals("3", SettingsFormat.manualSeed(3_333_000, SettingsFormat.BITRATE_PRESETS, 1_000_000))
    }
}
