package com.dilinkauto.server.adb

import android.util.Log
import com.dilinkauto.protocol.adb.TcpAdbConnection
import java.io.File

/**
 * Controls the phone via a persistent TCP ADB connection.
 * Uses TcpAdbConnection which keeps a single socket open for all commands.
 */
class RemoteAdbController(
    private val phoneHost: String,
    private val adbPort: Int = 5555,
    private val virtualDisplayId: Int,
    private val keyDir: File
) {
    private var adb: TcpAdbConnection? = null

    @Volatile
    var isConnected = false
        private set

    fun connect(): Boolean {
        return try {
            Log.i(TAG, "Connecting to phone ADB at $phoneHost:$adbPort...")
            adb = TcpAdbConnection(phoneHost, adbPort, keyDir)
            if (adb!!.connect()) {
                isConnected = true
                Log.i(TAG, "ADB connected to phone, virtualDisplayId=$virtualDisplayId")
                true
            } else {
                isConnected = false
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect ADB to $phoneHost:$adbPort", e)
            isConnected = false
            false
        }
    }

    fun shell(command: String): Boolean {
        val connection = adb ?: return false
        return try {
            connection.shell(command) == 0
        } catch (e: Exception) {
            Log.e(TAG, "$command exception", e)
            isConnected = false
            false
        }
    }

    fun shellBackground(command: String): Int {
        val connection = adb ?: return -1
        return try {
            connection.shellBackground(command)
        } catch (e: Exception) {
            Log.e(TAG, "shellBackground error", e)
            isConnected = false
            -1
        }
    }

    fun shellNoWait(command: String): Boolean {
        val connection = adb ?: return false
        return try {
            connection.shellNoWait(command)
        } catch (e: Exception) {
            Log.e(TAG, "shellNoWait error", e)
            isConnected = false
            false
        }
    }

    fun disconnect() {
        try { adb?.close() } catch (_: Exception) {}
        adb = null
        isConnected = false
    }

    companion object {
        private const val TAG = "RemoteAdbController"
    }
}
