package com.dilinkauto.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.dilinkauto.client.ui.ShizukuNav
import com.dilinkauto.protocol.VdProbeResult
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Manages Shizuku lifecycle and provides shell-level command execution.
 *
 * Uses the Shizuku AIDL interface ([IShizukuService.newProcess])
 * to create processes with shell (UID 2000) privileges.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
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
                context.packageManager.getPackageInfo(ShizukuNav.SHIZUKU_PACKAGE, 0)
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

    fun checkPermission(): Boolean {
        isAvailable = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        return isAvailable
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
    @Deprecated("Use ShizukuNav.openShizukuApp instead", ReplaceWith("com.dilinkauto.client.ui.ShizukuNav.openShizukuApp(context)"))
    fun openShizukuApp(context: Context) {
        com.dilinkauto.client.ui.ShizukuNav.openShizukuApp(context)
    }

    /**
     * Send the user somewhere they can install Shizuku.
     */
    @Deprecated("Use ShizukuNav.openShizukuStorePage instead", ReplaceWith("com.dilinkauto.client.ui.ShizukuNav.openShizukuStorePage(context)"))
    fun openShizukuStorePage(context: Context) {
        com.dilinkauto.client.ui.ShizukuNav.openShizukuStorePage(context)
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
     * stdout and stderr of one executed command, kept separate (audit A-M6).
     *
     * The previous single merged string made [probeVdServer] decide
     * ALIVE/GONE from text that could equally well have come from stderr —
     * a failed probe that merely printed something read as "alive".
     */
    data class ExecResult(val stdout: String, val stderr: String)

    /**
     * Execute a shell command and return stdout + stderr as an [ExecResult].
     * Blocks until the command completes (or the 30s deadline expires).
     * Returns null when Shizuku is unavailable, the binder is dead, or the
     * call itself fails.
     */
    fun execAndWait(command: String): ExecResult? {
        if (!isAvailable) return null
        var stdoutFd: android.os.ParcelFileDescriptor? = null
        var stderrFd: android.os.ParcelFileDescriptor? = null
        var process: IRemoteProcess? = null
        var completed = false
        return try {
            val service = getService() ?: return null
            process = service.newProcess(arrayOf("sh", "-c", command), null, null)

            // Duplicate FDs — ParcelFileDescriptors from binder transactions
            // can become invalid (EBADF) when the original is garbage collected.
            // IRemoteProcess.getInputStream/getErrorStream return fresh PFDs each
            // call, so dup+close is unnecessary; just hold the references and close
            // them in finally.
            stdoutFd = process.inputStream
            stderrFd = process.errorStream
            // Local non-null aliases for the drain threads: the nullable
            // vars exist only so the finally block can close whatever was
            // actually obtained before an exception.
            val outFd = stdoutFd!!
            val errFd = stderrFd!!

            // Drain stderr on a helper thread so a process that writes more
            // than the pipe buffer to stderr (64KB) can't deadlock on a full pipe
            // while we are still blocked reading stdout.
            //
            // On exit the thread offers its *fully collected* text as a
            // sentinel to this queue (audit A-M6): the caller reads the
            // buffer only after the sentinel arrives, so it can never observe
            // a half-appended StringBuilder, and the FDs are closed only
            // after the drain had its chance to finish — closing earlier
            // could EBADF the reader mid-append.
            val stderrDone = LinkedBlockingQueue<String>()
            val drainThread = Thread({
                val buf = StringBuilder()
                try {
                    FileInputStream(errFd.fileDescriptor).bufferedReader().use { reader ->
                        val c = CharArray(4096)
                        while (true) {
                            val n = reader.read(c)
                            if (n < 0) break
                            buf.append(c, 0, n)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stderrDone.offer(buf.toString())
                }
            }, "ShizukuStderr").apply { isDaemon = true }
            drainThread.start()

            val stdout = try {
                FileInputStream(outFd.fileDescriptor).bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }

            // Cap waitFor so a hung command can't block the caller indefinitely.
            // IRemoteProcess.waitForTimeout returns true if the process exited
            // within the timeout; fall back to destroy() if it did not.
            completed = waitForWithDeadline(process, 30_000L)
            // Bounded wait for the drain sentinel: a reader wedged in a native
            // read (EBADF on a GC'd PFD) must not block the caller forever.
            // On timeout the collected text stays empty rather than torn.
            val stderr = stderrDone.poll(2_000, TimeUnit.MILLISECONDS) ?: ""

            if (!completed) {
                FileLog.w(TAG, "Shizuku exec timed out: $command")
            }
            ExecResult(stdout, stderr)
        } catch (e: Exception) {
            FileLog.w(TAG, "Shizuku exec failed: ${e.message}")
            null
        } finally {
            // FD close happens here — after the drain sentinel was waited for
            // (A-M6) — so the stderr reader can never lose its descriptor
            // mid-append. Each close is best-effort: a dead PFD must not mask
            // the command's real result.
            try { stdoutFd?.close() } catch (_: Exception) {}
            try { stderrFd?.close() } catch (_: Exception) {}
            if (!completed) process?.let { p ->
                try { p.destroy() } catch (_: Exception) {}
            }
        }
    }

    /** Wait for the remote process with a deadline. Returns true if it exited. */
    private fun waitForWithDeadline(process: IRemoteProcess, deadlineMs: Long): Boolean {
        return try {
            process.waitForTimeout(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS.name)
        } catch (_: Exception) { false }
    }

    /**
     * Execute a shell command in the background (fire and forget).
     *
     * The command is wrapped as `sh -c "<cmd>"` and the caller is expected to
     * include its own `&` (the vd-server deploy path builds `setsid ...
     * >>log 2>&1 &` via [com.dilinkauto.protocol.VdDeploy.commandLine] with
     * background=true). We do NOT strip a trailing `&` and re-add one: that
     * earlier form turned `setsid app_process ... &` into `setsid app_process
     * ... & &`, a syntax error in some shells, and stripped a `&` the
     * caller deliberately placed. Passing the command through verbatim lets
     * the caller control backgrounding semantics (setsid, nohup, etc.).
     *
     * @return true when the process was handed to Shizuku, false when Shizuku
     *   is unavailable, the binder is dead, or newProcess threw. Callers must
     *   treat false as "the engine never launched" (audit A-M7: this used to
     *   be Unit, so every caller — including
     *   [com.dilinkauto.client.service.ConnectionService]'s
     *   `VdDeployExecutor.launch` — reported an unconditional success and the
     *   operator only learned of the failure from the 60s accept timeout).
     */
    fun execBackground(command: String): Boolean {
        if (!isAvailable) return false
        return try {
            val service = getService() ?: return false
            service.newProcess(arrayOf("sh", "-c", command.trim()), null, null)
            true
        } catch (e: Exception) {
            FileLog.w(TAG, "Shizuku execBackground failed: ${e.message}")
            false
        }
    }

    /**
     * One liveness probe of the vd-server engine, in the output form
     * ([com.dilinkauto.protocol.VdDeploy.probeCommand]) — Shizuku's
     * `execAndWait` exposes stdout but not the exit code, so this is the
     * Shizuku-side counterpart of the car/desktop exit-code probe.
     *
     * Returns [VdProbeResult.UNKNOWN] when Shizuku is unavailable or the probe
     * itself fails; the deploy sequence treats that as "accept the exit" so a
     * dead transport never stalls deployment. The convergence rules (two
     * consecutive GONE) live in [com.dilinkauto.protocol.vdAwaitExit] — this
     * class intentionally does not implement its own wait loop anymore.
     */
    fun probeVdServer(): VdProbeResult {
        if (!isAvailable) return VdProbeResult.UNKNOWN
        val result = try {
            execAndWait(com.dilinkauto.protocol.VdDeploy.probeCommand)
        } catch (_: Exception) {
            null
        } ?: return VdProbeResult.UNKNOWN
        // stdout only: the probe echoes Y/N to stdout and redirects pkill's
        // own stderr inside the command, so deciding from stderr text would
        // report ALIVE for a probe that merely failed loudly (audit A-M6).
        return if (result.stdout.trim().endsWith("Y")) VdProbeResult.ALIVE else VdProbeResult.GONE
    }
}
