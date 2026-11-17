package com.dilinkauto.protocol

/**
 * Cross-module launch targets — the component strings used in `am start`
 * commands to launch one module's MainActivity from another.
 *
 * The phone-side service launches the car app (after installing it via ADB),
 * and the car-side service launches the phone app (after deploying the VD
 * server). Hardcoding these strings in the launching module silently breaks
 * if the target's applicationId changes; collecting them here makes the
 * cross-module contract explicit.
 */
object AppTargets {
    /** Car app's MainActivity component, launched from the phone after install. */
    const val CAR_MAIN_ACTIVITY = "com.dilinkauto.server/.MainActivity"

    /** Phone app's MainActivity component, launched from the car after VD deploy. */
    const val PHONE_MAIN_ACTIVITY = "com.dilinkauto.client/.MainActivity"
}
