package com.dilinkauto.client

import com.dilinkauto.protocol.CONNECTION_METHOD_ROOT
import com.dilinkauto.protocol.CONNECTION_METHOD_SHIZUKU
import com.dilinkauto.protocol.CONNECTION_METHOD_USB_ADB

/**
 * Single decision point for privileged command execution on the phone.
 *
 * Root (su) is preferred — UID 0, no Shizuku/ADB dependency in steady state.
 * Shizuku (shell, UID 2000) is the fallback for non-rooted devices.
 * When neither is available, the car deploys the daemon over ADB instead.
 */
object PrivilegeRouter {

    val isAvailable: Boolean
        get() = RootManager.isAvailable || ShizukuManager.isAvailable

    val displayName: String
        get() = when {
            RootManager.isAvailable -> "ROOT"
            ShizukuManager.isAvailable -> "SHIZUKU"
            else -> "USB_ADB"
        }

    val connectionMethod: Byte
        get() = when {
            RootManager.isAvailable -> CONNECTION_METHOD_ROOT
            ShizukuManager.isAvailable -> CONNECTION_METHOD_SHIZUKU
            else -> CONNECTION_METHOD_USB_ADB
        }

    /**
     * Execute a command with the best available privilege level.
     * Returns null when no privileged backend is available.
     */
    fun execAndWait(command: String): String? = when {
        RootManager.isAvailable -> RootManager.execAndWait(command)
        ShizukuManager.isAvailable -> ShizukuManager.execAndWait(command)
        else -> null
    }
}
