package com.dilinkauto.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root (UID 0) command execution via su.
 *
 * KernelSU/Magisk grant su per-app. On devices with isolated mount namespaces
 * (common on OEM ROMs like BYD DiLink, MIUI/HyperOS), `su -c` runs in a
 * different mount namespace from the system — commands appear to succeed but
 * do not affect the real files.
 *
 * This manager probes multiple su variants and selects the first that works:
 *   1. `su --mount-master -c` — forces the master mount namespace (Magisk/KSU)
 *   2. `su -M -c`             — short form, some builds
 *   3. `su -c`                 — fallback for environments without namespace isolation
 *
 * API mirrors [ShizukuManager] so [PrivilegeRouter] can treat both backends
 * interchangeably (root is a superset of shell).
 */
object RootManager {

    private const val TAG = "RootManager"

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** The su command line that was detected as working. */
    @Volatile
    private var suArgs: List<String> = listOf("su", "--mount-master", "-c")

    /** Observable probe result: null while probing, true/false once decided. */
    private val _isAvailableFlow = MutableStateFlow<Boolean?>(null)
    val isAvailableFlow: StateFlow<Boolean?> = _isAvailableFlow

    @Volatile
    private var checked = false

    /**
     * One-time async su probe. The probe runs on a background thread because
     * su may block briefly (e.g. Magisk-style grant prompt); the result is
     * cached for the process lifetime — grant changes are picked up on restart.
     */
    @Synchronized
    fun init() {
        if (checked) return
        checked = true
        thread(name = "RootProbe") {
            val result = probeSu()
            isAvailable = result
            _isAvailableFlow.value = result
            FileLog.i(TAG, "Root available: $result")
        }
    }

    private fun probeSu(): Boolean {
        val candidates = listOf(
            listOf("su", "--mount-master", "-c"),
            listOf("su", "-M", "-c"),
            listOf("su", "-c")
        )
        for (args in candidates) {
            try {
                val process = ProcessBuilder(*args.toTypedArray(), "id -u")
                    .redirectErrorStream(true)
                    .start()
                // waitFor before reading: a blocked su (grant prompt) must hit the
                // timeout instead of hanging readText forever.
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    FileLog.w(TAG, "su candidate timed out: ${args.joinToString(" ")}")
                    continue
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (output == "0") {
                    suArgs = args
                    FileLog.i(TAG, "Root OK via: ${args.joinToString(" ")}")
                    return true
                }
                FileLog.w(TAG, "su candidate returned '$output': ${args.joinToString(" ")}")
            } catch (e: Exception) {
                FileLog.w(TAG, "su candidate failed (${args.joinToString(" ")}): ${e.message}")
            }
        }
        FileLog.w(TAG, "No working su variant found")
        return false
    }

    /**
     * Execute a command as root, blocking until it completes.
     * Returns the combined stdout+stderr, or null when unavailable/empty.
     */
    fun execAndWait(command: String): String? {
        if (!isAvailable) return null
        return try {
            val process = ProcessBuilder(*suArgs.toTypedArray(), command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.ifEmpty { null }
        } catch (e: Exception) {
            FileLog.w(TAG, "su exec failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a command as root, returning (exitCode, stdout, stderr).
     * On timeout the process is destroyed and exit code -1 is returned.
     * Never throws.
     */
    fun execFull(command: String, timeoutSec: Int = 15): Triple<Int, String, String> {
        if (!isAvailable) return Triple(-1, "", "root not available")
        return try {
            val process = ProcessBuilder(*suArgs.toTypedArray(), command).start()
            var stdout = ""
            var stderr = ""
            val outThread = Thread {
                stdout = process.inputStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }
            val errThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }
            val deadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
            var exitCode: Int? = null
            while (System.currentTimeMillis() < deadlineMs) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            outThread.join(1000)
            errThread.join(1000)
            if (exitCode == null) {
                process.destroyForcibly()
                FileLog.w(TAG, "su command timed out after ${timeoutSec}s: $command")
                Triple(-1, stdout, stderr)
            } else {
                Triple(exitCode, stdout, stderr)
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "su execFull failed: ${e.message}")
            Triple(-1, "", e.message ?: "")
        }
    }
}
