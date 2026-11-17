package com.dilinkauto.protocol

/**
 * Cross-module SharedPreferences contract.
 *
 * Holds the shared prefs file name and the keys that are read/written from
 * more than one file. A typo in a key string silently breaks the link
 * between writer and reader (e.g. IME restore reading a key that was never
 * written); collecting them here makes the contract explicit.
 *
 * Module-local keys that never cross a file boundary stay where they are.
 */
object AppPrefs {
    /** Shared prefs file name used by both the phone and car apps. */
    const val FILE_NAME = "dilinkauto"

    // Phone-side IME save/restore (ConnectionService writes, PhoneDisplayRestorer reads)
    const val SAVED_DEFAULT_IME = "saved_default_ime"

    // Phone-side log toggle (SettingsScreen toggles, FileLog + ConnectionService read)
    const val LOG_ENABLED = "log_enabled"
    const val LOG_ENABLED_USER_SET = "log_enabled_user_set"

    // Car-side session/settings state (CarConnectionService reads+writes).
    // The desktop app mirrors these same key names in DesktopSettings for
    // config parity — keep both ends resolving to these constants.
    const val USER_DISCONNECTED = "user_disconnected"
    const val DEV_MODE = "dev_mode"
    const val STARTUP_DPI = "startup_dpi"
    const val STARTUP_FPS = "startup_fps"
    const val STARTUP_BITRATE = "startup_bitrate"
    const val DEV_PHONE_IP = "dev_phone_ip"

    // Car-side VD composite-area tier (issue #153 PB): 0 = AUTO, 1 = COMPAT_2560.
    // Read when building the handshake request, so a change applies on the next
    // connect / rotation re-handshake, never in the middle of a live VD.
    const val VD_AREA_TIER = "vd_area_tier"

    // Car-side audio mirroring toggle (issue #72, Refs #13). Written by the
    // settings switch, read when building the handshake request and by the S3
    // audio-channel gate. Default is ON (issue #13 decision 3).
    const val AUDIO_ENABLED = "audio_enabled"
}
