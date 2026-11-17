package com.dilinkauto.server.adb

import android.util.Log
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.adb.TcpAdbConnection
import java.io.File

/**
 * Controls the phone via a persistent TCP ADB connection.
 * Uses TcpAdbConnection which keeps a single socket open for all commands.
 *
 * ### Transport-failure contract (2026-10-17, S05 live root-cause fix)
 *
 * [TcpAdbConnection.shell] / [shellBackground] swallow every transport error
 * internally and report it as `-1` — they never throw. The old implementation
 * here only cleared [isConnected] in its own `catch`, so a half-open socket
 * (WiFi flap on the phone side) kept `isConnected == true` forever. The state
 * machine then kept reusing the dead controller (`Dev mode: TCP ADB still
 * connected`) and every deploy failed within ~150 ms
 * (`VD server failed to start`), looping with backoff indefinitely.
 *
 * Rule: `-1` from the protocol layer **is** a transport failure (0 means "stream
 * closed cleanly", regardless of the command's own exit code). Every command
 * method therefore clears [isConnected] on failure, which lets
 * `CarConnectionService.handleDisconnect` / `startTcpAdbTrack` rebuild the
 * controller instead of reusing it.
 */
class RemoteAdbController(
    private val phoneHost: String,
    private val adbPort: Int = Ports.ADB_PORT,
    private val virtualDisplayId: Int,
    private val keyDir: File,
    /** Test seam: unit tests inject a scripted fake; production builds the real one. */
    private val connectionFactory: (String, Int, File) -> TcpAdbConnection =
        { h, p, k -> TcpAdbConnection(h, p, k) }
) {
    private var adb: TcpAdbConnection? = null

    @Volatile
    var isConnected = false
        private set

    fun connect(): Boolean {
        return try {
            Log.i(TAG, "Connecting to phone ADB at $phoneHost:$adbPort...")
            adb = connectionFactory(phoneHost, adbPort, keyDir)
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
            val rc = connection.shell(command)
            // -1 = transport failure (see class doc). 0 = stream closed cleanly.
            if (rc != 0) isConnected = false
            rc == 0
        } catch (e: Exception) {
            Log.e(TAG, "$command exception", e)
            isConnected = false
            false
        }
    }

    fun shellBackground(command: String): Int {
        val connection = adb ?: return -1
        return try {
            val id = connection.shellBackground(command)
            // -1 = socket null / OPEN rejected / exception — all transport-level.
            if (id < 0) isConnected = false
            id
        } catch (e: Exception) {
            Log.e(TAG, "shellBackground error", e)
            isConnected = false
            -1
        }
    }

    fun shellNoWait(command: String): Boolean {
        val connection = adb ?: return false
        return try {
            val ok = connection.shellNoWait(command)
            if (!ok) isConnected = false
            ok
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
