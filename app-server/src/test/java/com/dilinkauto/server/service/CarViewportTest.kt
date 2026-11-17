package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the car-side viewport geometry extracted from `CarConnectionService`
 * (audit R3-SRP-01 item 11).
 *
 * [CarViewport.navBarWidthPx] deliberately rounds the nav-bar offset *up*
 * (`DimAlign.offsetForEvenRemainder`) — the opposite direction from
 * `DimAlign.even`. That divergence was flagged as the hardest-to-detect one in
 * `docs/audit-srp-dry.md` (DRY-1); these cases pin it down.
 */
class CarViewportTest {

    /** density 1.0 makes the target bar width exactly `NAV_BAR_TARGET_DP` = 76px. */
    private val unitDensity = 1f

    @Test
    fun navBarWidensUpwardsWhenRemainderIsOdd() {
        // 1281 - 76 = 1205 (odd) -> the bar must be widened to 77 so the
        // viewport lands on 1204, never narrowed to 75.
        assertEquals(77, CarViewport.navBarWidthPx(unitDensity, 1281))
    }

    @Test
    fun navBarIsUntouchedWhenRemainderIsEven() {
        // 1280 - 76 = 1204 (even) -> no nudge.
        assertEquals(76, CarViewport.navBarWidthPx(unitDensity, 1280))
    }

    @Test
    fun navBarScalesWithDensity() {
        // (76 * 1.5).toInt() = 114; 1281 - 114 = 1167 (odd) -> widens to 115.
        assertEquals(115, CarViewport.navBarWidthPx(1.5f, 1281))
    }

    @Test
    fun landscapeTrimsTheWidthOnly() {
        // navBar = 77 (odd remainder widens up); 1281 - 77 = 1204 stays even,
        // 721 floors to 720.
        assertEquals(1204 to 720, CarViewport.size(1281, 721, unitDensity))
    }

    @Test
    fun portraitTrimsTheHeightOnly() {
        assertEquals(720 to 1204, CarViewport.size(721, 1281, unitDensity))
    }

    @Test
    fun bothDimensionsAreAlwaysEven() {
        for (w in 1080..1300 step 7) {
            for (h in 700..1300 step 11) {
                val (vw, vh) = CarViewport.size(w, h, 1.375f)
                assertTrue("width $vw for ${w}x$h", vw % 2 == 0)
                assertTrue("height $vh for ${w}x$h", vh % 2 == 0)
            }
        }
    }
}
