package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import java.lang.reflect.Method

/**
 * Creates the shell-UID VirtualDisplay via framework reflection.
 *
 * Two strategies, in order:
 *  1. [DisplayManagerGlobal] — the hidden singleton that accepts a
 *     [VirtualDisplayConfig] built via its Builder. This is the path that
 *     lets us set the surface and the full flag set (see [VD_FLAGS]); it
 *     works on the AOSP shell runtime.
 *  2. [DisplayManager] fallback — construct the (also hidden) constructor
 *     via [FakeContext] and use the public [DisplayManager.createVirtualDisplay]
 *     with the same flag set.
 *
 * After creation, applies the iPad-like letterbox style (Android 12L+) so
 * portrait apps render at a sensible 16:10 aspect ratio inside the car's
 * landscape viewport, and disables the screen-off timeout / lift / proximity
 * wake so the phone panel doesn't sleep while the car is driving.
 *
 * Extracted from [PipelineServer] to keep the reflection body out of the
 * process lifecycle. The returned [VirtualDisplay] is owned by the caller
 * (released in cleanup).
 */
internal class VirtualDisplayCreator(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val dpi: Int,
    /**
     * 目标刷新率（Hz），issue #153-PA。0（默认）= 不设置，保持旧行为。
     *
     * issue #203：这里以前只在 `VirtualDisplayConfig.Builder` 上反射
     * `setRefreshRate(Float)` —— AOSP 上根本没有这个方法（真名是
     * `setRequestedRefreshRate(float)`，见 [applyRefreshRate]），而且本机 ROM
     * 连 `DisplayManagerGlobal.createVirtualDisplay(VirtualDisplayConfig, …)` 都
     * 不存在（实车日志 `NoSuchMethodException`），于是永远落到
     * `DisplayManager.createVirtualDisplay` 兜底分支，日志固定打
     * `not applied`。现在兜底分支会补一次 `Surface.setFrameRate` 请求，并且
     * **真正的生效值只能看 `GlPipeline` 周期行的 effFps/newFps**。
     */
    private val refreshRate: Float = 0f,
    private val execShell: (String) -> Unit,
    private val execShellOutput: (String) -> String?,
    private val log: (String) -> Unit,
    private val err: (String) -> Unit,
    /**
     * 层树检测探针（issue #19 的回归钉子接缝）。
     *
     * null = 用真实 `dumpsys SurfaceFlinger` 解析；单测注入固定序列，不依赖设备。
     * 返回值语义与 [isDtaAttachedUnderDisplay] 一致：true 健康 / false DTA 孤立 /
     * null 无法判定（按需要修复处理）。
     */
    private val layersAttached: (() -> Boolean?)? = null,
    /**
     * 旋转往返之间的等待。生产用 [Thread.sleep]；单测注入 no-op，避免每例真睡 6s。
     */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private companion object {
        /**
         * VirtualDisplay flags（位值与 AOSP DisplayManager.java 一致）：
         * PUBLIC(1<<0) | OWN_CONTENT_ONLY(1<<3) | SUPPORTS_TOUCH(1<<6) | TRUSTED(1<<10) |
         * OWN_DISPLAY_GROUP(1<<11) | ALWAYS_UNLOCKED(1<<12) | TOUCH_FEEDBACK_DISABLED(1<<13) |
         * OWN_FOCUS(1<<14)
         *
         * ALWAYS_UNLOCKED 是「手机锁屏 → VD 上出现 KEYGUARD_DIALOG(ty=2009) 全屏黑窗 → 投屏
         * 恒黑」的修复：此前误用 0x40(SUPPORTS_TOUCH) 充当 ALWAYS_UNLOCKED(=1<<12)，该位
         * 实际从未设置。AOSP 语义要求该 flag 仅对非默认 display group 的 VD 有效，
         * 前置条件已由 OWN_DISPLAY_GROUP 满足。
         */
        const val VD_FLAGS = 0x1 or 0x8 or (1 shl 6) or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14)
    }

    var displayId: Int = -1
        private set

    /**
     * Create the VirtualDisplay. Does **not** touch system settings — see
     * [configureEnvironment], which the caller must invoke separately *after*
     * snapshotting the originals.
     *
     * Ordering matters and used to be inverted: this method used to call
     * `configureDisplayEnvironment()` itself, but [PipelineServer.createVirtualDisplay]
     * snapshotted the originals *afterwards*, so `settings get system
     * screen_off_timeout` read back the [SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL]
     * this class had just written (visible in vd-server.log as
     * `get ... -> out='2147483647'`). The restore path then treated that as
     * "already the sentinel, nothing to do" and the setting was never put back.
     *
     * The snapshot-before-write order is still the primary defence; the sentinel is
     * additionally rejected by `SessionScreenTimeout.valueToRestore` (S-M10) for the
     * case where the previous session was killed before it could restore anything.
     */
    fun create(surface: Surface): VirtualDisplay? {
        var vd: VirtualDisplay? = createViaDisplayManagerGlobal(surface)
        if (vd == null) vd = createViaDisplayManager(surface)
        if (vd == null) return null
        if (displayId < 0) {
            err("VirtualDisplay created with invalid displayId: $displayId")
            try { vd.release() } catch (_: Exception) {}
            return null
        }
        return vd
    }

    /**
     * Apply the letterbox style + disable screen-off / wake gestures.
     *
     * MUST be called only after `DisplayPowerController.saveCurrentIme()` has
     * snapshotted the current values, otherwise the snapshot captures our own
     * writes and the originals are lost forever.
     */
    fun configureEnvironment() = configureDisplayEnvironment()

    private fun createViaDisplayManagerGlobal(surface: Surface): VirtualDisplay? {
        try {
            log("DisplayManagerGlobal...")
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val cfgClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val bldClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val bldCtor = bldClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            bldCtor.isAccessible = true
            val bld = bldCtor.newInstance("DiLinkAutoVD", displayWidth, displayHeight, dpi)
            bldClass.getDeclaredMethod("setSurface", Surface::class.java).apply { isAccessible = true; invoke(bld, surface) }
            bldClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType).apply { isAccessible = true; invoke(bld, VD_FLAGS) }
            // issue #153-PA：让 VD 的合成节拍与目标 fps 一致，避免内容按物理屏
            // 60/90Hz 反复触发合成。隐藏 Builder 上该方法在部分 ROM 不存在 ——
            // 缺失时只记日志、VD 照常创建，绝不阻断建屏。
            if (refreshRate > 0f) {
                if (applyRefreshRate(bld, bldClass, refreshRate, log = { log(it) })) {
                    log("VD refreshRate=$refreshRate")
                } else {
                    log("VD setRequestedRefreshRate/setRefreshRate unavailable on this ROM — keeping default")
                }
            }
            val cfg = bldClass.getDeclaredMethod("build").apply { isAccessible = true }.invoke(bld)
            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val createVd: Method = try {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, android.os.Handler::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, String::class.java)
            }
            createVd.isAccessible = true
            val vd = (if (createVd.parameterCount == 4) createVd.invoke(dmg, cfg, null, null, "com.android.shell")
                else createVd.invoke(dmg, cfg, null, "com.android.shell")) as? VirtualDisplay
            if (vd != null) {
                displayId = try { vd.display.displayId } catch (_: Exception) { findDisplayId("DiLinkAutoVD") }
                log("VD via DisplayManagerGlobal: id=$displayId")
            }
            return vd
        } catch (e: Exception) { err("DisplayManagerGlobal: ${e.message}"); return null }
    }

    private fun createViaDisplayManager(surface: Surface): VirtualDisplay? {
        try {
            log("DisplayManager...")
            val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true; val dm = ctor.newInstance(FakeContext.get())
            val vd = dm.createVirtualDisplay("DiLinkAutoVD", displayWidth, displayHeight, dpi, surface, VD_FLAGS)
            displayId = try { vd.display.displayId } catch (_: Exception) { findDisplayId("DiLinkAutoVD") }
            log("VD via DisplayManager: id=$displayId")
            if (refreshRate > 0f) {
                // issue #203：公开的 createVirtualDisplay 没有刷新率形参，VD 的显示模式只能
                // 按系统默认值建立（这一步无法更改）。退而求其次，在 VD 输出 Surface 上打
                // 「期望帧率」提示（API 30+ 的 Surface.setFrameRate），由 SurfaceFlinger 在
                // 选拍时参考。
                //
                // 注意：这是**请求**，不是**生效**——该方法返回 void，调用成功不代表真的被
                // 采纳（issue #203 明确要求不能只看调用成功）。真实生效值见 GlPipeline
                // 周期行的 effFps（已发帧/秒）与 newFps（VD 交付新帧/秒）。
                //noinspection NewApi — SDK 判定在 applySurfaceFrameRate 内部完成
                val applied = applySurfaceFrameRate(
                    rate = refreshRate,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    setter = FrameRateSetter { r ->
                        surface.setFrameRate(r, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    },
                    log = { log(it) },
                )
                if (applied) {
                    log("VD refreshRate=$refreshRate requested via Surface.setFrameRate (builder path unavailable, 生效值以 Pipeline effFps/newFps 为准)")
                } else {
                    log("VD refreshRate=$refreshRate NOT applied: no refresh-rate parameter on the DisplayManager fallback and Surface.setFrameRate is unavailable")
                }
            }
            return vd
        } catch (e: Exception) { err("DisplayManager: ${e.message}"); return null }
    }

    /**
     * 检查并修复 Flyme(Android 15) 上偶发的 WM↔SF 层树脱同步（车机黑屏根因）。
     *
     * 该 ROM 新建 VD 时，把 DefaultTaskDisplayArea(DTA) 挂到 display 层树的
     * reparent 事务可能丢失：DTA 停留在 SF 根的 Offscreen Hierarchy 下，
     * display 子树里没有它 → 该 display 合成 0 层 → 输出黑帧。
     * （WM 侧 dumpsys window 却显示 "(organized)"、任务 visible，极具迷惑性。）
     *
     * 检测：解析 dumpsys SurfaceFlinger 层树，DTA 必须出现在
     * `Display <displayId> name=` 子树内。
     * 修复：对该 display 做一次 user-rotation 往返 (lock 1 → lock 0)，
     * 实测可强制 WindowManager 重新提交层树事务、把 DTA 挂回原位。
     * 健康设备上检测通过即返回，零副作用。
     *
     * @param targetDisplayId 要检查/修复的 display。默认为本类创建的那个 VD；
     *   显式传入是为 issue #19 的回归钉子留的接缝（测试里不能写 [displayId] 字段）。
     */
    fun ensureDtaAttached(targetDisplayId: Int = displayId) {
        if (targetDisplayId < 0) { err("DTA check skipped: display id unknown"); return }
        when (isDtaAttachedUnderDisplay(targetDisplayId)) {
            true -> { log("DTA attached — no repair needed"); return }
            false -> log("DTA orphaned in Offscreen Hierarchy — repairing via rotation round-trip")
            null -> log("DTA check inconclusive — applying preventive rotation round-trip")
        }
        repeat(2) { round ->
            execShell("wm user-rotation -d $targetDisplayId lock 1")
            sleepMs(1500)
            execShell("wm user-rotation -d $targetDisplayId lock 0")
            sleepMs(1500)
            if (isDtaAttachedUnderDisplay(targetDisplayId) == true) { log("DTA repair OK (round ${round + 1})"); return }
            err("DTA still orphaned after repair round ${round + 1}")
        }
        err("DTA repair failed after 2 rounds — car may show a black screen")
    }

    /**
     * true  = DTA 在 display 子树内（健康）；
     * false = DTA 孤立在 Offscreen Hierarchy（黑屏状态）；
     * null  = dump 不可用或无法解析（保守起见按需要修复处理）。
     */
    private fun isDtaAttachedUnderDisplay(targetDisplayId: Int): Boolean? {
        val injected = layersAttached
        if (injected != null) return injected()
        val dump = execShellOutput("dumpsys SurfaceFlinger") ?: return null
        val start = dump.indexOf("Display $targetDisplayId name=")
        if (start < 0) return null
        // 区段终点 = 下一个 display 行 / Offscreen Hierarchy 段，取更早者
        val nextDisplay = Regex("Display \\d+ name=").find(dump, start + 1)?.range?.first ?: -1
        val offscreen = dump.indexOf("Offscreen Hierarchy", start)
        val end = listOf(nextDisplay, offscreen).filter { it > start }.minOrNull() ?: dump.length
        return dump.substring(start, end).contains("DefaultTaskDisplayArea")
    }

    /**
     * 锁定虚拟屏方向为横屏，阻止应用把 display 转成竖屏（issue #59）。
     *
     * VD 用 `displayWidth x displayHeight`（宽 > 高）创建，其坐标系下旋转 0 即横屏；
     * 会话期内把「用户旋转」锁到 0 后，应用再声明 `screenOrientation=portrait` 也不能把
     * display 转走 —— 否则 B 站这类锁竖屏的应用会渲染成居中的竖屏小窗（实车证据：
     * `mLastNonFullscreenBounds=Rect(920, 51 - 1641, 1351)`）。
     *
     * 必须放在 [ensureDtaAttached] **之后**：那里的旋转往返（lock 1 → lock 0）需要
     * display 还能自由改方向，先锁死会让往返失效。
     * 复位由 PipelineServer teardown 里的 `wm user-rotation -d <id> free` 负责。
     */
    fun lockLandscapeRotation(targetDisplayId: Int = displayId) {
        if (targetDisplayId < 0) { err("rotation lock skipped: display id unknown"); return }
        execShell("wm user-rotation -d $targetDisplayId lock 0")
        log("Locked VD rotation to 0 (landscape) — portrait apps keep the car's aspect ratio")
    }

    /** Apply the letterbox style + disable screen-off / wake gestures for the session. */
    private fun configureDisplayEnvironment() {
        try {
            execShell("cmd window set-letterbox-style --aspectRatio 1.6 --cornerRadius 24 --horizontalPositionMultiplier 0.5")
            log("Configured iPad-like letterbox style: aspectRatio=1.6, cornerRadius=24")
        } catch (_: Exception) {}
        try {
            // 2147483647 = "永不息屏"哨兵，常量集中在 SessionScreenTimeout（audit S-M10）：
            // DisplayPowerController 读快照/恢复时必须能认出我们自己写的这个值。上次会话
            // 被 SIGKILL / Runtime.halt(1) 异常杀死时，settings 里留着的就是它。
            execShell("settings put system screen_off_timeout ${SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL}"); log("Screen timeout disabled")
            execShell("settings put system lift_wakeup_enabled 0")
            execShell("settings put system proximity_wakeup_enabled 0")
        } catch (_: Exception) {}
    }

    private fun findDisplayId(name: String): Int {
        val out = execShellOutput("dumpsys display 2>/dev/null | grep -A 5 '$name' | grep 'mDisplayId=' | head -1")?.trim() ?: return -1
        return Regex("mDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}

/**
 * 反射给 `VirtualDisplayConfig.Builder` 设刷新率（issue #153-PA / #203）。
 *
 * 抽成顶层函数是为了能纯 JVM 单测：注入一个带 / 不带刷新率 setter
 * 的假 Builder 就能验证调用与容错，不必真的起 `DisplayManagerGlobal`。
 *
 * issue #203 的根因：旧实现只反射 `setRefreshRate(Float)`，但 AOSP
 * `VirtualDisplayConfig.Builder` 上的真实方法名是
 * `setRequestedRefreshRate(float)`（API 33+），所以这条路径在**所有** ROM 上
 * 都会抛 `NoSuchMethodException`、恒返回 false，刷新率配置项从未生效过。
 * 现在按顺序尝试两个名字：先 AOSP 的正式名，再兼容个别 ROM 的改名。
 *
 * @return true = 设置成功（内部日志会写明用的是哪个方法名）；
 *   false = 两个名字都不存在或调用失败（调用方只记日志，VD 创建继续走原路径）。
 */
internal fun applyRefreshRate(
    builder: Any,
    builderClass: Class<*>,
    rate: Float,
    log: (String) -> Unit = {},
): Boolean {
    val failures = mutableListOf<String>()
    for (name in REFRESH_RATE_SETTERS) {
        try {
            val m = builderClass.getDeclaredMethod(name, java.lang.Float.TYPE)
            m.isAccessible = true
            m.invoke(builder, rate)
            log("refresh rate setter: $name")
            return true
        } catch (e: Exception) {
            failures += "$name=${e.javaClass.simpleName}"
        }
    }
    log("setRequestedRefreshRate/setRefreshRate unavailable: ${failures.joinToString(", ")}")
    return false
}

/**
 * `VirtualDisplayConfig.Builder` 上刷新率 setter 的候选方法名，按优先级排列。
 *
 * `setRequestedRefreshRate` 是 AOSP 正式名（API 33+）；`setRefreshRate` 保留给
 * 少数改了名的 ROM —— 历史上这里只写后者，等于从来没生效过（issue #203）。
 */
private val REFRESH_RATE_SETTERS = listOf("setRequestedRefreshRate", "setRefreshRate")

/**
 * 在某个 `Surface` 上设置期望帧率的接缝（issue #203）。
 *
 * 抽成 fun interface 而不是 `(Float) -> Unit`：单测要验证「被调用/未被调用」，
 * 直接传 lambda 会和其他 `(Float) -> Unit` 参数撞类型；同时避免单测里真的造
 * 一个 `Surface`（JVM 单测里 `android.view.Surface` 是空壳）。
 */
internal fun interface FrameRateSetter {
    fun set(rate: Float)
}

/** `Surface.setFrameRate` 的最低 API（Android 11 / R）。 */
internal const val SURFACE_FRAME_RATE_API = 30

/**
 * 在 VD 输出 `Surface` 上请求目标帧率（issue #203）。
 *
 * 只用于 [VirtualDisplayCreator] 的 `DisplayManager.createVirtualDisplay` 兜底分支：
 * 那条公开 API 没有刷新率形参，VD 的显示模式无法在建屏时指定，只能在输出
 * Surface 上打一个期望帧率提示。
 *
 * **返回值只代表「请求已发出」，不代表「已生效」** —— `Surface.setFrameRate`
 * 返回 void。真实生效值由 `GlPipeline` 的 effFps/newFps 观测（issue #203 的要求）。
 *
 * @param sdkInt 由调用方注入（生产传 `Build.VERSION.SDK_INT`），便于纯 JVM 单测
 *   覆盖 API 29（无此方法）与 API 30+ 两条分支。
 */
internal fun applySurfaceFrameRate(
    rate: Float,
    sdkInt: Int,
    setter: FrameRateSetter,
    log: (String) -> Unit = {},
): Boolean {
    if (rate <= 0f) return false
    if (sdkInt < SURFACE_FRAME_RATE_API) {
        log("Surface.setFrameRate unavailable: API $sdkInt < $SURFACE_FRAME_RATE_API")
        return false
    }
    return try {
        setter.set(rate)
        true
    } catch (e: Exception) {
        // 已释放/无效的 Surface 会抛 IllegalArgumentException —— 只能降级，不能带崩建屏。
        log("Surface.setFrameRate failed: ${e.javaClass.simpleName} ${e.message}")
        false
    }
}

