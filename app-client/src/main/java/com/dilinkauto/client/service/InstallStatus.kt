package com.dilinkauto.client.service

import com.dilinkauto.client.R

/**
 * Classification of the car-app install status string produced by
 * [ConnectionService.installCarApp].
 *
 * The status is a free-form string surfaced to the UI; the UI needs to know
 * whether to show a spinner, a success check, an error, or an auth-needed
 * retry. Parsing is centralized here so [OnboardingScreen], [CarInstallCard],
 * and [InstallStatusCard] agree on the classification instead of each
 * re-implementing the `status.contains(...)` chain.
 *
 * Stage labels ([stageKeywords]) are exposed as `Pair<String, Int>` (keyword,
 * string-resource id) so callers can resolve them in a @Composable context
 * with `stringResource`. The keyword is matched case-insensitively against
 * the status string to find the current stage index.
 */
enum class InstallStatus {
    IDLE,
    SEARCHING,
    CONNECTING,
    CHECKING,
    PUSHING,
    INSTALLING,
    LAUNCHING,
    AUTH_NEEDED,
    DONE,
    ERROR;

    val isInProgress: Boolean
        get() = this == SEARCHING || this == CONNECTING || this == CHECKING ||
            this == PUSHING || this == INSTALLING || this == LAUNCHING

    val isTerminal: Boolean
        get() = this == DONE || this == ERROR || this == AUTH_NEEDED

    companion object {
        /**
         * Install stages in execution order, paired with their display string
         * resource. Used by the stage-progress UIs to render the checklist.
         */
        val stageKeywords: List<Pair<String, Int>> = listOf(
            "Searching" to R.string.car_install_status_searching,
            "Connecting" to R.string.car_install_status_connecting,
            "Checking" to R.string.car_install_status_checking_version,
            "Push" to R.string.car_install_status_pushing,
            "Install" to R.string.car_install_status_installing_stage,
            "Launching" to R.string.car_install_status_launching
        )

        fun parse(status: String): InstallStatus {
            if (status.isEmpty()) return IDLE
            fun has(kw: String) = status.contains(kw, ignoreCase = true)
            return when {
                has("Success") || has("installed") || has("up-to-date") -> DONE
                has("Authorization") -> AUTH_NEEDED
                has("Error") || has("Failed") || has("not found") -> ERROR
                has("Searching") -> SEARCHING
                has("Connecting") -> CONNECTING
                has("Checking") -> CHECKING
                has("Push") -> PUSHING
                has("Install") -> INSTALLING
                has("Launching") -> LAUNCHING
                else -> IDLE
            }
        }

        /** Index into [stageKeywords] for the current stage, or -1 if no stage matches. */
        fun stageIndex(status: String): Int =
            stageKeywords.indexOfLast { status.contains(it.first, ignoreCase = true) }
    }
}
