package com.dilinkauto.client.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Step + permission-polling state for [OnboardingScreen] (audit R3-SRP-05).
 *
 * Owns which step is showing, the permission re-check trigger, and the
 * post-settings polling loop so the screen keeps only string/icon wiring and
 * rendering. Saveable — a rotation keeps the user's place in the flow.
 */
@Stable
internal class OnboardingState {

    var currentStep by mutableIntStateOf(0)
        private set

    /** Bumped whenever permissions may have changed; drives re-reads + auto-advance. */
    var refreshKey by mutableIntStateOf(0)
        private set

    fun next() { currentStep++ }

    /** Call from the ON_RESUME observer — re-check permissions instantly. */
    fun recheckPermissions() { refreshKey++ }

    /**
     * After sending the user to a settings screen, poll the step's permission
     * (up to ~9s) and bump [refreshKey] as soon as it is granted. Polls the
     * system API directly so it bypasses any caching.
     */
    fun pollPermission(context: Context, scope: CoroutineScope, stepIndex: Int) {
        scope.launch {
            for (i in 0..30) {
                delay(300)
                val granted = when (stepIndex) {
                    1 -> PermissionChecker.hasAllFilesAccess()
                    2 -> PermissionChecker.hasBatteryExemption(context, context.packageName)
                    3 -> PermissionChecker.hasAccessibility(context, context.packageName)
                    else -> true
                }
                if (granted) {
                    refreshKey++
                    break
                }
            }
        }
    }

    companion object {
        val Saver: Saver<OnboardingState, Any> = listSaver(
            save = { listOf(it.currentStep, it.refreshKey) },
            restore = { OnboardingState().apply { currentStep = it[0]; refreshKey = it[1] } }
        )
    }
}
