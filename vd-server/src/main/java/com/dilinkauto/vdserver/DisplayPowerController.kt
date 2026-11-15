package com.dilinkauto.vdserver

import com.dilinkauto.protocol.ShellVerdict
import java.io.OutputStream

/**
 * Powers the phone's physical display on/off and configures the IME policy on
 * the VirtualDisplay, via reflection into the framework's DisplayControl class
 * (shell-only; the VD server runs as shell via app_process).
 *
 * Also persists and restores the original IME so the VD's linkpc IME doesn't
 * outlive the session. The IME-restore path is used by [PipelineServer.cleanup].
 *
 * Extracted from [PipelineServer] to keep display/IME framework reflection
 * out of the pipeline body.
 */
internal class DisplayPowerController(
    private val shellProvider: () -> OutputStream?,
    private val execShellOutput: (String) -> String?,
    private val logErr: (String) -> Unit,
    /**
     * 反射调用（`DisplayControl.setDisplayPowerMode`）的注入口，**仅供单测**记录调用序列；
     * 生产为 null，走 [applyDisplayControlPower]。
     */
    private val reflectionPowerOverride: ((Boolean) -> Boolean)? = null,
    /** `screen_off_timeout` 原值存档（跨进程；见 [TimeoutSnapshotFile]，2026-10-17 S19 实证新增）。 */
    private val snapshotFile: TimeoutSnapshotFile = TimeoutSnapshotFile(),
) {
    private var displayControlClass: Class<*>? = null
    private var displayControlLoaded = false

    /** `cmd display help` 的能力：null = 未探测；见 [powerResetCapability]。 */
    private var powerResetCapabilityCached: PowerResetCapability? = null

    /**
     * 本会话是否**尝试过**深熄屏（issue #66）。
     *
     * 记「尝试」而不是「成功」：反射调用可能在已经生效之后才抛异常
     * （现场就是 `setDisplayPowerMode failed: null` 而屏幕确实黑了）。
     * **只用于日志**：收尾点亮不因它短路，因为面板状态是整机的、可能来自上个被 kill 的进程。
     */
    @Volatile
    private var deepOffAttempted = false

    /** [canDeepPowerOff] 的进程内结论：null = 未探测。 */
    private var deepOffRecoverable: Boolean? = null

    /** `cmd display` 对 `power-reset` 的支持程度（三态，见 [powerResetCapability]）。 */
    private enum class PowerResetCapability { SUPPORTED, UNSUPPORTED, UNKNOWN }

    var savedDefaultIme: String? = null
        private set

    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null

    /** Persist the original IME and screen settings so they can be restored on teardown. */
    fun saveCurrentIme() {
        try {
            val cur = execShellOutput("settings get secure default_input_method")?.trim()
            if (com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(cur)) {
                savedDefaultIme = cur
                PipeLog.log("Saved original IME: $savedDefaultIme")
            }
        } catch (_: Exception) {}
        try {
            // 2026-10-17 S19 实证：读到哨兵（上个会话被 kill）时先用文件存档修正，再落盘本次原值——
            // 纯内存快照在 SIGKILL 路径会丢，导致用户原值被 fallback 60000 永久覆盖（vd-server.log 行 148→205）。
            val cur = execShellOutput("settings get system screen_off_timeout")?.trim()
            val effective = SessionScreenTimeout.effectiveSnapshot(cur, snapshotFile.read())
            savedScreenOffTimeout = effective
            snapshotFile.write(effective)
            savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")?.trim() ?: "1"
            savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")?.trim() ?: "1"
        } catch (_: Exception) {}
    }

    /** Set the IME policy on display [id] to fall back to the physical IME (0). */
    fun setDisplayImePolicy(id: Int) {
        try { val wm = Class.forName("android.view.IWindowManager\$Stub").getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, "window")); wm.javaClass.getDeclaredMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, 0) } catch (_: Exception) {}
    }

    /**
     * Power the physical panel on ([on]=true) or off ([on]=false). Uses
     * DisplayControl reflection; falls back to `cmd display power-reset/off`
     * (API 29+) when the reflection path does not fully succeed. On API < 29 the
     * shell fallback is a no-op and the panel cannot be restored here — surfaced
     * via [logErr].
     *
     * 2026-10-27（issue #23）：
     *  - 反射路径改为返回布尔，**类加载失败也算失败**并进入 shell 兜底。旧实现
     *    在 `DisplayControl` 加载失败时 `displayControlClass` 为 null 后直接返回，
     *    shell 兜底根本不会执行 —— 这是「两层都死」的另一条路径。
     *  - `power-reset` 不再 fire-and-forget：命令带退出码标记，按
     *    [ShellVerdict] 判定，失败打 E 并带原始输出（Flyme 现场是
     *    `Unknown command: power-reset`，旧实现完全看不见）。
     *  - 该 ROM 是否支持 `power-reset` 用 `cmd display help` 探测一次并缓存；
     *    探测失败（拿不到 help）时仍然尝试，避免把可用 ROM 误判为不支持。
     *
     * 2026-11-15（issue #66）：
     *  - 熄屏前先证明「熄得掉也点得亮」，见 [canDeepPowerOff]。本 ROM 的
     *    `cmd display` 既没有 `power-off` 也没有 `power-reset`，屏幕上真正变黑的是
     *    反射那次「抛异常前已生效」的深熄屏 —— 闸门必须加在反射**之前**，
     *    加在 [shellPowerOff] 前面拦不住。
     *  - 点亮（[restorePanel]）**无条件执行**：熄屏标志是本进程的、面板状态是整机的，
     *    上个进程被 kill 掉一样会把面板留在深熄状态，收尾不能因为本进程没熄过就跳过。
     */
    fun powerOffPanel() {
        if (!canDeepPowerOff()) {
            logErr(
                "skip deep power-off: no recoverable path on this ROM " +
                    "(DisplayControl unusable and `cmd display help` has no power-reset) — leaving the panel on"
            )
            return
        }
        deepOffAttempted = true
        if (reflectPower(false)) return
        shellPowerOff()
    }

    /**
     * 点亮物理面板。收尾、看门狗强清、车机侧「手动亮屏」都走这里，**不做条件短路**：
     * 面板可能被上一个被 kill 的会话留在深熄状态，见类头 2026-11-15 说明。
     */
    fun restorePanel() {
        if (!deepOffAttempted) {
            PipeLog.log("panel restore: no deep power-off this session — attempting anyway (a killed session may have left the panel off)")
        }
        if (reflectPower(true)) return
        shellPowerOn()
    }

    /**
     * 熄屏是否**可逆**：只有确定能把面板再点亮，才允许深熄屏（issue #66）。
     *
     * 任一条件成立即视为可逆：
     *  1. `DisplayControl.setDisplayPowerMode(2)` 调用成功 —— 同一个方法也能把面板点亮；
     *  2. `cmd display help` **明确**列出 `power-reset`（[shellPowerOn] 的兜底路径）。
     *
     * help 拿不到时按不可逆处理：宁可投屏期间屏幕常亮，也不要把面板熄成单向门。
     * 结论按进程缓存（[deepOffRecoverable]）：熄屏可被车机侧手动重复下发，不能每次都
     * 先做一次「探测性点亮」，否则面板本来就黑时会被点亮、再熄掉，产生可见闪屏。
     */
    private fun canDeepPowerOff(): Boolean {
        deepOffRecoverable?.let { return it }
        val recoverable = reflectPower(true) || powerResetCapability() == PowerResetCapability.SUPPORTED
        deepOffRecoverable = recoverable
        return recoverable
    }

    /** 反射路径的统一入口：[reflectionPowerOverride] 供单测注入调用序列。 */
    private fun reflectPower(on: Boolean): Boolean =
        reflectionPowerOverride?.invoke(on) ?: applyDisplayControlPower(on)

    /** 反射路径：完全成功返回 true；任何一步失败（含类加载）返回 false。 */
    private fun applyDisplayControlPower(on: Boolean): Boolean {
        try {
            if (!displayControlLoaded) { try { displayControlClass = Class.forName("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { try { val clf = Class.forName("dalvik.system.DelegateLastClassLoader").getDeclaredConstructor(String::class.java, String::class.java, ClassLoader::class.java); clf.isAccessible = true; displayControlClass = (clf.newInstance("/system/framework/services.jar", null, ClassLoader.getSystemClassLoader()) as ClassLoader).loadClass("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { logErr("DisplayControl load failed") } } }
        } catch (_: Exception) {
            logErr("DisplayControl load failed: unexpected error")
        }
        val cls = displayControlClass ?: return false
        return try {
            val gid = cls.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
            val ids = gid.invoke(null) as LongArray
            if (ids.isEmpty()) {
                logErr("DisplayControl returned no physical displays")
                return false
            }
            val sp = cls.getDeclaredMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            for (id in ids) {
                sp.invoke(
                    null,
                    cls.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, id),
                    if (on) 2 else 0
                )
            }
            true
        } catch (e: Exception) {
            // issue #66：Flyme 现场只有 `failed: null`，看不出 NPE 出在 token 为 null
            // 还是框架调用内部；补上异常类型与 cause，后续现场可直接定位。
            val cause = e.cause?.let { " (cause=${it.javaClass.simpleName}: ${it.message})" } ?: ""
            logErr("DisplayControl setDisplayPowerMode failed: ${e.javaClass.simpleName}: ${e.message}$cause")
            false
        }
    }

    /**
     * Shell 兜底点亮：`cmd display power-reset ID`（API 29+）。
     *
     * 2026-10-10 实机修正：这里原来写的是 `power-on` —— Android 15 实机
     * `cmd display help` 证明该子命令**不存在**（只有 power-off 和 power-reset
     * "Turn the display power to a state the display supposed to have"）。
     * 2026-10-27 Flyme 现场（issue #23）：连 `power-reset` 也不存在，
     * 返回 `Unknown command: power-reset`。
     */
    private fun shellPowerOn() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            logErr("DisplayControl reflection failed on API < 29; shell fallback (cmd display power) is API 29+ and will NOT restore the panel")
        }
        if (!powerResetSupported()) {
            logErr("cmd display power-reset is not supported on this ROM — physical panel may stay off")
            return
        }
        val out = execShellOutput(ShellVerdict.wrap(POWER_RESET_COMMAND))
        if (ShellVerdict.succeeded(out)) {
            PipeLog.log("Physical display restored via power-reset ($POWER_RESET_COMMAND)")
        } else {
            logErr("power-reset did not take effect: ${ShellVerdict.detail(out)}")
        }
    }

    /** Shell 兜底熄屏：`cmd display power-off 0`（fire-and-forget，失败不影响清理流程）。 */
    private fun shellPowerOff() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            logErr("DisplayControl reflection failed on API < 29; shell fallback (cmd display power) is API 29+ and will NOT power the panel off")
        }
        try { execShell("cmd display power-off 0") } catch (_: Exception) {}
    }

    /**
     * 该 ROM 的 `cmd display` 对 `power-reset` 的支持程度，探测一次并缓存。
     *
     * 三态（issue #66）：help 拿不到时是 [PowerResetCapability.UNKNOWN] 且**不缓存**。
     * 点亮链（[powerResetSupported]）对 UNKNOWN 仍然尝试，免得把能用的 ROM 误跳过；
     * 熄屏闸门（[canDeepPowerOff]）对 UNKNOWN 按不可逆处理。
     */
    private fun powerResetCapability(): PowerResetCapability {
        powerResetCapabilityCached?.let { return it }
        val help = execShellOutput("cmd display help")?.trim().orEmpty()
        val capability = when {
            help.isEmpty() -> PowerResetCapability.UNKNOWN
            help.contains("power-reset") -> PowerResetCapability.SUPPORTED
            else -> PowerResetCapability.UNSUPPORTED
        }
        if (capability == PowerResetCapability.UNKNOWN) return capability
        powerResetCapabilityCached = capability
        if (capability == PowerResetCapability.UNSUPPORTED) {
            logErr("cmd display help has no power-reset on this ROM — skipping shell fallback")
        }
        return capability
    }

    /** 点亮链的 fail-open 视图：UNKNOWN（help 拿不到）仍然尝试。 */
    private fun powerResetSupported(): Boolean =
        powerResetCapability() != PowerResetCapability.UNSUPPORTED

    /**
     * Restore the previously-saved IME and screen settings.
     *
     * Runs unconditionally on teardown (the caller re-powers the panel
     * separately) and is idempotent. Each setting is restored from the snapshot
     * taken in [saveCurrentIme].
     *
     * S-M10: `screen_off_timeout` is special. When the previous session was killed
     * abnormally (SIGKILL / `Runtime.halt(1)` — both reachable, see the watchdog and
     * the car's teardown), the setting still holds the sentinel we wrote, so the
     * snapshot is *our own sentinel* rather than the user's value. Restoring it would
     * keep the phone awake forever and be inherited by every later session. The
     * sentinel is therefore treated as "no valid snapshot" and replaced with the
     * documented default (60s) instead — see [SessionScreenTimeout].
     *
     * 2026-10-17（S19 真机实证）起：原值另有文件存档 [snapshotFile]，恢复时**优先**
     * 读它——kill 路径（快照仅存内存会丢）下个会话也能恢复真实原值而非 60s。
     * 存档缺失/损坏时退化为上述 S-M10 语义（哨兵 → 默认值）。
     */
    fun restoreIme() {
        val ime = savedDefaultIme
        if (ime != null && com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(ime)) {
            try {
                com.dilinkauto.protocol.ImeRestore.imeRestoreCommands(ime).forEach { execShell(it) }
                PipeLog.log("Restored original IME: $ime")
            } catch (_: Exception) {}
        }
        val timeout = snapshotFile.read() ?: savedScreenOffTimeout
        restoreSetting("screen_off_timeout", SessionScreenTimeout.valueToRestore(timeout))
        if (SessionScreenTimeout.isSentinel(timeout)) {
            PipeLog.log("screen_off_timeout snapshot was our own sentinel (previous session was killed) — falling back to ${SessionScreenTimeout.DEFAULT_TIMEOUT_MS}ms")
        }
        restoreSetting("lift_wakeup_enabled", savedLiftWakeup)
        restoreSetting("proximity_wakeup_enabled", savedProximityWakeup)
    }

    /**
     * Write [value] back to `system.[key]` when it is a plausible snapshot.
     *
     * "Plausible" = non-blank and not a `settings get` miss marker (`null` /
     * `undefined`), and numeric keys must hold a positive number. A failed
     * snapshot yields one of those markers; writing them back would leave the
     * device in a worse state than leaving the current value alone.
     */
    private fun restoreSetting(key: String, value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty() || v.equals("null", true) || v.equals("undefined", true)) return
        val numeric = v.toLongOrNull()
        if (numeric != null && numeric <= 0L) return
        try { execShell("settings put system $key $v") } catch (_: Exception) {}
    }

    private fun execShell(cmd: String) = ShellExec.execShell(shellProvider(), cmd)

    companion object {
        /** 点亮物理屏的子命令；退出码由 [ShellVerdict] 判定。 */
        const val POWER_RESET_COMMAND = "cmd display power-reset 0"
    }
}
