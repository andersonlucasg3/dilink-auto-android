package com.dilinkauto.server.service

import com.dilinkauto.protocol.DimAlign

/**
 * Car-side viewport math (audit R3-SRP-01 item 11) — the pure geometry that used
 * to sit in `CarConnectionService.companion`. Extracted so it is unit-testable
 * and the service carries no display layout responsibility.
 *
 * The virtual display leaves room for the persistent nav-bar strip at the
 * bottom/right edge; dimensions are even-aligned because the H.264 encoder
 * requires even width/height.
 */
internal object CarViewport {

    /** Target nav-bar strip width in dp (fixed on a car head unit). */
    const val NAV_BAR_TARGET_DP = 76f

    /**
     * Even-aligned `(width, height)` for the virtual display, with the nav-bar
     * strip removed from the long edge.
     */
    fun size(widthPx: Int, heightPx: Int, density: Float): Pair<Int, Int> {
        val isLandscape = widthPx > heightPx
        val navBarPx = navBarWidthPx(density, if (isLandscape) widthPx else heightPx)
        val viewportWidth = if (isLandscape) widthPx - navBarPx else widthPx
        val viewportHeight = if (isLandscape) heightPx else heightPx - navBarPx
        return Pair(DimAlign.even(viewportWidth), DimAlign.even(viewportHeight))
    }

    /**
     * Nav-bar strip width for [screenWidthPx]. The viewport is
     * `screenWidthPx - navBarPx`, so the bar is the only value we may nudge to
     * land on an even viewport — it must be widened, not narrowed. Rounds the
     * offset UP, unlike [DimAlign.even].
     */
    fun navBarWidthPx(density: Float, screenWidthPx: Int): Int {
        val targetPx = (NAV_BAR_TARGET_DP * density).toInt()
        return DimAlign.offsetForEvenRemainder(screenWidthPx, targetPx)
    }
}
