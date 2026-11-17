package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the three *distinct* even-alignment behaviours that were previously
 * five inline copies across four modules (docs/audit-srp-dry.md DRY-1).
 *
 * These are deliberately NOT merged into one behaviour: the historical call
 * sites disagree, and the divergence is load-bearing for encoded command lines.
 * This test exists so the divergence can never change silently again.
 */
class DimAlignTest {

    @Test
    fun even_clearsLowBit() {
        assertEquals(1280, DimAlign.even(1281))
        assertEquals(1280, DimAlign.even(1280))
        assertEquals(720, DimAlign.even(721))
    }

    @Test
    fun even_canReturnZero_whichIsWhyEncodersMustUseEvenMin2() {
        // Historical "clear the low bit" behaviour, preserved on purpose.
        assertEquals(0, DimAlign.even(0))
        assertEquals(0, DimAlign.even(1))
    }

    @Test
    fun evenMin2_matchesLegacyDesktopEvenAlignExactly() {
        // Same five assertions DesktopConnectionServiceTest locks on
        // HandshakeFactory.evenAlign, which now delegates here.
        assertEquals(1280, DimAlign.evenMin2(1281))
        assertEquals(720, DimAlign.evenMin2(721))
        assertEquals(1280, DimAlign.evenMin2(1280))
        assertEquals(2, DimAlign.evenMin2(1))
        assertEquals(2, DimAlign.evenMin2(0))
    }

    @Test
    fun evenMin2_neverBelowTwo_evenForNegatives() {
        // The legacy implementation used Kotlin's remainder, which is negative
        // for negative operands: (-1 % 2) == -1, so it floored -1 to -2 and then
        // clamped to 2. That exact path must be preserved.
        assertEquals(2, DimAlign.evenMin2(-1))
        assertEquals(2, DimAlign.evenMin2(-2))
        assertEquals(2, DimAlign.evenMin2(-3))
    }

    @Test
    fun evenMin2_isAlwaysEvenAndAtLeastTwo() {
        for (v in -10..210) {
            val aligned = DimAlign.evenMin2(v)
            assertTrue("evenMin2($v)=$aligned must be even", aligned % 2 == 0)
            assertTrue("evenMin2($v)=$aligned must be >= 2", aligned >= 2)
        }
    }

    @Test
    fun even_isAlwaysEven() {
        for (v in 0..210) {
            assertTrue("even($v) must be even", DimAlign.even(v) % 2 == 0)
        }
    }

    @Test
    fun offsetForEvenRemainder_widensTheOffsetByOneWhenNeeded() {
        // base - offset must be even. 1280 - 152 = 1128 (even) -> unchanged.
        assertEquals(152, DimAlign.offsetForEvenRemainder(1280, 152))
        // 1281 - 152 = 1129 (odd) -> widen the bar by 1, viewport becomes 1128.
        assertEquals(153, DimAlign.offsetForEvenRemainder(1281, 152))
    }

    @Test
    fun offsetForEvenRemainder_alwaysYieldsAnEvenRemainder() {
        for (base in 0..200) {
            for (offset in 0..40) {
                val adjusted = DimAlign.offsetForEvenRemainder(base, offset)
                val remainder = base - adjusted
                assertEquals(
                    "base=$base offset=$offset adjusted=$adjusted remainder must be even",
                    0,
                    remainder % 2
                )
                assertTrue("offset must not shrink: $offset -> $adjusted", adjusted >= offset)
                assertTrue("offset must move by at most 1: $offset -> $adjusted", adjusted <= offset + 1)
            }
        }
    }

    @Test
    fun offsetForEvenRemainder_roundsUp_whichIsTheOppositeOfEven() {
        // This is why the nav-bar variant cannot be expressed via even():
        // it adjusts the *offset*, and must round it UP to shrink the remainder.
        assertEquals(153, DimAlign.offsetForEvenRemainder(1281, 152))
        // even() applied to the remainder instead would have shrunk the viewport,
        // which the nav bar is not allowed to do.
        assertEquals(1128, DimAlign.even(1281 - 152))
        assertEquals(1128, 1281 - DimAlign.offsetForEvenRemainder(1281, 152))
    }
}