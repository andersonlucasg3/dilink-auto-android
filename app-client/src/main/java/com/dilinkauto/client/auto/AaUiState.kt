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
}
