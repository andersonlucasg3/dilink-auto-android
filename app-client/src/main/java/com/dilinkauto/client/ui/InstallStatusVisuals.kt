package com.dilinkauto.client.ui

import androidx.compose.ui.graphics.Color
import com.dilinkauto.client.service.InstallStatus

/**
 * Install-status → color mapping shared by the onboarding car-setup section
 * and the main-screen install card (audit R3-DRY-10). `InstallStatus.parse`
 * was already shared; this closes the last duplicated step (the 绿/红/橙 + icon
 * mapping), so the two surfaces can no longer drift apart.
 */
object InstallStatusVisuals {

    /** Done / success (green). */
    val DoneColor = Color(0xFF4CAF50)

    /** Hard failure (red). */
    val ErrorColor = Color(0xFFEF5350)

    /** Needs user attention, or in progress (amber). */
    val AttentionColor = Color(0xFFFFA726)

    /**
     * Status color for text/status rows: green when done, red on error,
     * amber when attention is needed or the install is running. Null when idle
     * — callers pick their own neutral for that case.
     */
    fun statusColor(status: InstallStatus): Color? = when (status) {
        InstallStatus.DONE -> DoneColor
        InstallStatus.ERROR -> ErrorColor
        InstallStatus.AUTH_NEEDED -> AttentionColor
        else -> if (status.isInProgress) AttentionColor else null
    }
}
