package com.dilinkauto.client

import com.dilinkauto.protocol.CONNECTION_METHOD_ROOT
import com.dilinkauto.protocol.CONNECTION_METHOD_USB_ADB

/**
 * Single decision point for privileged command execution on the phone.
 *
 * Root (su) is the only privileged backend — UID 0, no external elevation
 * service needed and no ADB dependency. When root is unavailable the car
 * deploys the daemon over ADB instead: that case is reported as
 * [CONNECTION_METHOD_USB_ADB] on the handshake (label only — no privileged
 * execution happens here).
 */
object PrivilegeRouter {

    val isAvailable: Boolean
        get() = RootManager.isAvailable

    val displayName: String
        get() = if (RootManager.isAvailable) "ROOT" else "USB_ADB"

    val connectionMethod: Byte
        get() = if (RootManager.isAvailable) CONNECTION_METHOD_ROOT else CONNECTION_METHOD_USB_ADB

    /**
     * Execute a command with the best available privilege level.
     * Returns null when no privileged backend is available.
     */
    fun execAndWait(command: String): String? =
        if (RootManager.isAvailable) RootManager.execAndWait(command) else null
}
