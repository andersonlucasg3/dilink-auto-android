package com.dilinkauto.server.adb

import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import java.io.File

/**
 * Controls the phone via direct ADB connection using dadb.
 * Connects to the phone's adbd (wireless debugging) on the given host:port.
 *
 * Used to:
 * - Launch third-party apps on the phone's virtual display
 * - Inject input events (tap, swipe, back) on the virtual display
 *
 * These operations require shell-level privileges, which ADB provides.
 */
class RemoteAdbController(
    private val phoneHost: String,
    private val adbPort: Int = 5555,
    private val virtualDisplayId: Int,
    private val keyDir: File
) {
    private var dadb: Dadb? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isConnected = false
        private set

    /**
     * Connects to the phone's ADB daemon.
     * Must be called from a background thread.
     */
    fun connect(): Boolean {
        return try {
            val keyPair = getOrCreateKeyPair()
            Log.i(TAG, "Connecting to phone ADB at $phoneHost:$adbPort...")
            dadb = Dadb.create(phoneHost, adbPort, keyPair)
            isConnected = true
            Log.i(TAG, "ADB connected to phone, virtualDisplayId=$virtualDisplayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect ADB to $phoneHost:$adbPort", e)
            isConnected = false
            false
        }
    }

    /**
     * Launches an app on the virtual display via ADB shell.
     */
    fun launchApp(packageName: String): Boolean {
        // First: resolve the launch activity via package manager
        val resolveResult = shellOutput(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName"
        )

        // Parse the component name (last line of output, format: package/activity)
        val component = resolveResult?.lines()
            ?.lastOrNull { it.contains("/") }
            ?.trim()

        return if (component != null) {
            shell(
                "am start --display $virtualDisplayId -n $component",
                "launchApp($packageName → $component)"
            )
        } else {
            // Fallback: try am start with package name only
            Log.w(TAG, "Could not resolve activity for $packageName, trying direct launch")
            shell(
                "am start --display $virtualDisplayId " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER " +
                    "-n $packageName",
                "launchApp($packageName) direct"
            )
        }
    }

    /**
     * Sends a tap event to the virtual display.
     */
    fun tap(x: Int, y: Int): Boolean {
        return shell(
            "input -d $virtualDisplayId tap $x $y",
            "tap($x, $y)"
        )
    }

    /**
     * Sends a swipe event to the virtual display.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return shell(
            "input -d $virtualDisplayId swipe $x1 $y1 $x2 $y2 $durationMs",
            "swipe"
        )
    }

    /**
     * Sends a BACK key event to the virtual display.
     */
    fun back(): Boolean {
        return shell(
            "input -d $virtualDisplayId keyevent 4",
            "back"
        )
    }

    /**
     * Sends a HOME key event to the virtual display.
     */
    fun home(): Boolean {
        return shell(
            "input -d $virtualDisplayId keyevent 3",
            "home"
        )
    }

    /**
     * Launches an app asynchronously (non-blocking from the caller's thread).
     */
    fun launchAppAsync(packageName: String) {
        scope.launch { launchApp(packageName) }
    }

    fun tapAsync(x: Int, y: Int) {
        scope.launch { tap(x, y) }
    }

    fun swipeAsync(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        scope.launch { swipe(x1, y1, x2, y2, durationMs) }
    }

    fun backAsync() {
        scope.launch { back() }
    }

    fun homeAsync() {
        scope.launch { home() }
    }

    /**
     * Pushes a file to the phone via ADB.
     */
    fun pushFile(inputStream: java.io.InputStream, remotePath: String) {
        val connection = dadb ?: throw IllegalStateException("Not connected")
        val tempFile = File.createTempFile("push", ".tmp", keyDir)
        try {
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }
            connection.push(tempFile, remotePath)
            Log.i(TAG, "Pushed ${tempFile.length()} bytes to $remotePath")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Runs a shell command without waiting for it to complete.
     * Used for starting long-running processes like the VD server.
     */
    fun shellAsync(command: String) {
        scope.launch {
            try {
                val connection = dadb ?: return@launch
                Log.d(TAG, "shellAsync: $command")
                // This will block until the process exits, but it's in a coroutine
                connection.shell(command)
                Log.d(TAG, "shellAsync finished: $command")
            } catch (e: Exception) {
                Log.e(TAG, "shellAsync error", e)
            }
        }
    }

    fun shellOutput(command: String): String? {
        val connection = dadb ?: return null
        return try {
            val result = connection.shell(command)
            if (result.exitCode == 0) result.allOutput else null
        } catch (e: Exception) {
            Log.e(TAG, "shellOutput($command) exception", e)
            null
        }
    }

    private fun shell(command: String, label: String): Boolean {
        val connection = dadb ?: run {
            Log.w(TAG, "$label: not connected")
            return false
        }

        return try {
            val result = connection.shell(command)
            if (result.exitCode != 0) {
                Log.w(TAG, "$label failed (exit=${result.exitCode}): ${result.allOutput}")
            } else {
                Log.d(TAG, "$label OK")
            }
            result.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "$label exception", e)
            isConnected = false
            false
        }
    }

    private fun getOrCreateKeyPair(): AdbKeyPair {
        val privateKeyFile = File(keyDir, "adbkey")
        val publicKeyFile = File(keyDir, "adbkey.pub")

        return if (privateKeyFile.exists() && publicKeyFile.exists()) {
            Log.d(TAG, "Using existing ADB key pair")
            AdbKeyPair.read(privateKeyFile, publicKeyFile)
        } else {
            Log.i(TAG, "Generating new ADB key pair")
            keyDir.mkdirs()
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
            AdbKeyPair.read(privateKeyFile, publicKeyFile)
        }
    }

    fun disconnect() {
        scope.cancel()
        try {
            dadb?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ADB connection", e)
        }
        dadb = null
        isConnected = false
        Log.i(TAG, "ADB disconnected")
    }

    companion object {
        private const val TAG = "RemoteAdbController"
    }
}
