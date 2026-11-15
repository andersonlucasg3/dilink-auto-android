package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.MainActivity
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restores the phone's physical display and IME state after the VD server
 * tears down.
 *
 * The VD server powers off the physical panel directly via
 * `DisplayControl.setDisplayPowerMode(0)` — a deeper off than PowerManager
 * can recover from. Its cleanup() runs on the engine's **normal exit paths**
 * (CMD_STOP / socket 断开 / watchdog 消费停止哨兵). 注意 SIGTERM 不是一条
 * 正常路径：**ART 的 app_process 收 SIGTERM 直接终止、不跑 shutdown hook**
 * （2026-10-10 MI 9 实测），所以本类先用 [VdDeploy.gracefulStopCommand]
 * 的哨兵文件请求引擎优雅退出。When the lifecycle channel breaks so CMD_STOP
 * never arrives, or the process hangs in a native futex and even the sentinel
 * times out, cleanup() may still be skipped and the panel stays off →
 * phone is unusable. PowerManager wakeUp/wake-locks cannot reverse this;
 * only `setDisplayPowerMode(2)` / `cmd display power-reset` can, which needs
 * shell privileges (Shizuku).
 *
 * Layers (tried in order, each independent) — 具体执行与判定见
 * [DisplayRestoreSequence]：
 *  1. Shizuku: graceful-stop (sentinel + wait + force kill) + `cmd display
 *     power-reset` + IME restore.
 *  2. PowerManager.wakeUp() via reflection — system-level wake.
 *  3. Launch MainActivity with FLAG_TURN_SCREEN_ON — WindowManager triggers display on.
 *  4. WakeLock with ACQUIRE_CAUSES_WAKEUP — framework-level.
 *
 * ── Why this class owns its own CoroutineScope ──
 * The previous version ran on the Service's `serviceScope`, which
 * `ConnectionService.onDestroy()` cancels. When the user hit "stop" (or the
 * system reclaimed the Service) the restore coroutine was cancelled *between*
 * the `pkill` and the `cmd display power-reset`, leaving the physical panel off
 * with nothing left to turn it back on:
 *
 *     Force-waking physical display          x3
 *     Shizuku display restore failed: Job was cancelled  x3
 *
 * It also had no single-flight guard, so one disconnect could fire it 9 times
 * (7 `cleanupSession()` call sites, two of them on network callbacks), each one
 * racing a `pkill` against the previous restore. [restoreScope] is therefore a
 * process-lifetime scope that no Service lifecycle can cancel, and [inFlight]
 * collapses concurrent requests into one.
 *
 * @param savedIme the IME id cached at handshake time, or null to fall back to
 *                 the persisted `saved_default_ime` pref. The caller clears its
 *                 in-memory copy after invoking [restore] since the value is
 *                 captured here.
 */
internal class PhoneDisplayRestorer(
    private val appContext: Context
) {
    /**
     * Process-lifetime scope. Deliberately NOT the Service's scope: the whole
     * point of this class is that it must finish even when the Service is dying.
     */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single-flight guard: collapse concurrent restore requests into one run. */
    private val inFlight = AtomicBoolean(false)

    @android.annotation.SuppressLint("BlockedPrivateApi")
    fun restore(savedIme: String?, sessionToken: String? = null) {
        if (!inFlight.compareAndSet(false, true)) {
            FileLog.d(TAG, "Restore already in flight — skipping duplicate request")
            return
        }
        restoreScope.launch {
            // NonCancellable: once we start killing the engine we must run the
            // power-reset too. A cancellation in the middle leaves the panel off.
            withContext(NonCancellable) {
                try {
                    restoreInternal(savedIme, sessionToken)
                } finally {
                    inFlight.set(false)
                }
            }
        }
    }

    private suspend fun restoreInternal(savedIme: String?, sessionToken: String?) {
        try {
            FileLog.i(TAG, "Force-waking physical display")

            // Shizuku 优雅停止三步（协议见 VdDeploy 类头）：ART 的 app_process 收
            // SIGTERM 直接终止、**不跑 shutdown hook**（2026-10-10 MI 9 实测：
            // 进程 1 秒内消失、cleanup 零执行），所以先用哨兵文件请引擎自己
            // 走正常退出路径、把 cleanup 完整跑完（面板/letterbox/IME/
            // screen_off_timeout 恢复 + VD 释放），再轮询等它退出；超时才
            // -9 兜底（强杀路径接受 cleanup 被跳过的代价）。
            //
            // issue #180 第三版：哨兵必须写**本次会话**那一份（token 来自部署时的
            // 计划）。回读设备上的 `vd-server.session` 会在"新引擎先启动、旧会话后
            // 收尾"时读到新 token，把新会话杀掉。
            if (ShizukuManager.isAvailable) {
                try {
                    val sentinel = VdDeploy.stopRequestPath(sessionToken)
                    FileLog.d(TAG, "Graceful stop sentinel: $sentinel")
                    ShizukuManager.execAndWait(VdDeploy.gracefulStopCommand(sessionToken))
                    var engineExited = false
                    val engineDeadline = System.currentTimeMillis() + ENGINE_EXIT_WAIT_MS
                    while (System.currentTimeMillis() < engineDeadline) {
                        if (ShizukuManager.probeVdServer() != VdProbeResult.ALIVE) {
                            engineExited = true
                            break
                        }
                        delay(300)
                    }
                    if (!engineExited) {
                        FileLog.w(TAG, "VD server did not exit within ${ENGINE_EXIT_WAIT_MS}ms — force kill")
                        ShizukuManager.execAndWait(VdDeploy.killCommandForce)
                    }
                } catch (e: Exception) {
                    FileLog.w(TAG, "Shizuku display restore failed: ${e.message}")
                }
            } else {
                FileLog.w(TAG, "Shizuku unavailable — relying on framework wake layers only")
            }

            val targetIme = savedIme
                ?: appContext.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
                    .getString(AppPrefs.SAVED_DEFAULT_IME, null)

            val report = sequence.run(targetIme)
            if (report.anyDisplayLayerOk) {
                FileLog.i(TAG, "物理屏恢复汇总：${report.describe()}")
            } else {
                FileLog.w(TAG, "物理屏恢复失败：所有层均未生效（${report.describe()}）")
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "forceWakeScreen error: ${e.message}")
        }
    }

    /** 第 1/2/4 层的真实实现：判定与日志由 [DisplayRestoreSequence] 负责。 */
    private val sequence = DisplayRestoreSequence(
        shell = { cmd ->
            ShizukuManager.execAndWait(cmd)?.let { ShellOutcome(it.stdout, it.stderr) }
        },
        wakeUpFn = { wakeUpViaReflection() },
        launchActivityFn = { launchMainActivityForWake() },
        wakeLockFn = { acquireRestoreWakeLock() },
        log = fileLogAdapter,
    )

    /** Layer 2: PowerManager.wakeUp() — direct system call. */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun wakeUpViaReflection() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeUp = PowerManager::class.java.getDeclaredMethod(
            "wakeUp", Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java
        )
        wakeUp.invoke(pm, SystemClock.uptimeMillis(), 5 /* WAKE_REASON_APPLICATION */, "DiLink:restore")
    }

    /** Layer 3: Launch MainActivity with FLAG_TURN_SCREEN_ON. */
    private fun launchMainActivityForWake() {
        // Direct reference, not Class.forName (audit A-L19): the class
        // lives in the same module, so the reflection only bought a
        // runtime "class not found" surface with no benefit.
        val intent = Intent(appContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(0x10000000) // FLAG_TURN_SCREEN_ON
        appContext.startActivity(intent)
    }

    /** Layer 4: WakeLock with ACQUIRE_CAUSES_WAKEUP. */
    @Suppress("DEPRECATION")
    private fun acquireRestoreWakeLock() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE
        val wl = pm.newWakeLock(flags, "DiLink:display:restore2")
        try {
            wl.acquire(3000)
        } finally {
            // try/finally + isHeld (audit A-L20): acquire() throws when
            // the framework refuses (e.g. the timeout arg is invalid on a
            // vendor ROM), and releasing a lock that failed to acquire —
            // or that the 3s timeout already auto-released — throws
            // RuntimeException from here, skipping nothing else in this
            // method only because the restore is already done.
            if (wl.isHeld) wl.release()
        }
    }

    companion object {
        private const val TAG = "PhoneDisplayRestorer"

        /** FileLog 适配器：恢复序列只依赖日志接缝，不直接依赖 Android。 */
        private val fileLogAdapter = object : RestoreLog {
            override fun i(msg: String) { FileLog.i(TAG, msg) }
            override fun w(msg: String) { FileLog.w(TAG, msg) }
            override fun d(msg: String) { FileLog.d(TAG, msg) }
        }

        /**
         * 等引擎优雅退出的上限（哨兵文件 → 完整 cleanup → 退出）。
         * 与 `VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS` 同量级，且必须是同一个数：
         * MI 9 实测（2026-10-10）从哨兵到**进程真的消失**要 6.1~6.3 秒（早先
         * "2~4 秒"的说法只看到了 cleanup 内部的 shell 命令）。12 秒 = 约 2 倍余量。
         */
        private const val ENGINE_EXIT_WAIT_MS = 12_000L
    }
}
