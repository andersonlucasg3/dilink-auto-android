package com.dilinkauto.client.auto

import android.graphics.Rect
import androidx.compose.runtime.mutableStateOf

/**
 * Shared AA-session UI metrics. The MirrorScreen (SurfaceCallback) publishes
 * the host's stable area (surface rect NOT covered by host chrome, e.g. the
 * action strip); the VD launcher reads it to inset its content so nothing
 * renders under the nav rail or the strip button.
 */
object AaUiState {

    /** Host-reported stable area in surface pixels; null when unknown. */
    val stableArea = mutableStateOf<Rect?>(null)

    /** Nav rail width in dp — launcher content starts after it. */
    const val RAIL_WIDTH_DP = 74

    /**
     * Single observer for stable-area changes. The nav-rail service registers
     * here to reposition itself when the host's chrome geometry changes.
     * Only one consumer is expected — a list of listeners would be overkill.
     */
    var onStableAreaChanged: ((Rect) -> Unit)? = null

    /**
     * Navbar button bounds in VD1 pixels: maps button IDs (e.g. "home", "back",
     * "notifications", "recent:com.example.app") to their on-screen rectangles.
     * Populated by PersistentNavBar via onGloballyPositioned; consumed by
     * MirrorScreen for hit-test routing.
     */
    val navbarBounds = mutableStateOf<Map<String, Rect>?>(null)

    /** Current active app on VD2, or null if on home grid. */
    val activeApp = mutableStateOf<String?>(null)

    /** VD2 display ID, -1 if not created. */
    val vd2DisplayId = mutableStateOf(-1)

    /**
     * Called by MirrorScreen when a navbar button is pressed on the AA surface.
     * The DiLinkLauncher registers here to keep its UI in sync with AA input.
     * Action values: "home", "back", "notifications", "recent:<pkg>".
     * MirrorScreen already executes the daemon action directly for speed;
     * this callback is purely for VD1 UI sync.
     */
    var onNavAction: ((String) -> Unit)? = null
}
