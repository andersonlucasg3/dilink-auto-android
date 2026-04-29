package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * Manages Shizuku lifecycle and provides shell-level command execution.
 *
 * Uses the Shizuku AIDL interface ([IShizukuService.newProcess])
 * to create processes with shell (UID 2000) privileges.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var isInstalled: Boolean = false
        private set

    private var listenersRegistered = false

    fun init(context: Context) {
        if (listenersRegistered) return
        listenersRegistered = true

        try {
            isInstalled = try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

            Shizuku.addBinderReceivedListener {
                FileLog.i(TAG, "Shizuku binder received")
                checkPermission()
            }

            Shizuku.addBinderDeadListener {
                isAvailable = false
                FileLog.i(TAG, "Shizuku binder dead")
            }

            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == REQUEST_CODE) {
                    isAvailable = result == PackageManager.PERMISSION_GRANTED
                    FileLog.i(TAG, "Shizuku permission: ${if (isAvailable) "granted" else "denied"}")
                }
            }

            if (isInstalled) checkPermission()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku init failed: ${e.message}")
        }
    }

    private fun checkPermission() {
        isAvailable = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Request Shizuku permission. Only call from an Activity context.
     */
    fun requestPermission() {
        if (!isInstalled) return
        if (isAvailable) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                isAvailable = true
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        }
    }

    /**
     * Open the Shizuku app so the user can manage authorization manually.
     */
    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku: ${e.message}")
        }
    }

    private fun getService(): IShizukuService? {
        return try {
            val binder: IBinder = Shizuku.getBinder()
                ?: return null
            if (!binder.pingBinder()) return null
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Execute a shell command and return stdout + stderr combined.
     * Blocks until the command completes.
     */
    fun execAndWait(command: String): String? {
        if (!isAvailable) return null
        return try {
            val service = getService() ?: return null
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdoutFd: ParcelFileDescriptor = process.outputStream
            val stderrFd: ParcelFileDescriptor = process.errorStream

            val stdout = FileInputStream(stdoutFd.fileDescriptor).bufferedReader().readText()
            val stderr = FileInputStream(stderrFd.fileDescriptor).bufferedReader().readText()
            process.waitFor()

            stdoutFd.close()
            stderrFd.close()

            if (stdout.isNotEmpty()) stdout else stderr
        } catch (e: Exception) {
            FileLog.w(TAG, "Shizuku exec failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a shell command in the background (fire and forget).
     * Appends "&" to the command so sh returns immediately.
     */
    fun execBackground(command: String): Boolean {
        val result = execAndWait("$command &")
        return result != null
    }
}
