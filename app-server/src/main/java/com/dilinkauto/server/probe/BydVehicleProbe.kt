package com.dilinkauto.server.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * issue #188 的一次性探针：在车机上验证「非平台签名的 app 能否读到 BYDAuto 车辆数据」。
 *
 * 为什么要在车机里跑：我们自己的车机端包（`com.dilinkauto.server`，正式签名）拿不到
 * signature 级的 `android.permission.BYDAUTO_*`；但 SDK 的权限检查发生在**客户端 getter
 * 第一行**（`mContext.enforceCallingOrSelfPermission(...)`），[BydPermissionContext] 把这层
 * 拦掉之后，服务端会不会二次校验，就是本探针要回答的问题。
 *
 * 输出约定：一行一个 `[BydProbe] key=value`，经 `CarLogWriter` 以 `CAR_LOG` 帧回传手机，
 * 手机端落进 `client.log`。得到结论后本类连同调用点一起删除（见 issue #188 的清理条件）。
 *
 * 设计约束：
 * - 全反射，不依赖 `framework.jar`（`byd_api/readme.txt:3` 明确它不能当普通 jar 依赖）；
 * - 任何异常都必须就地吞掉并记录，绝不能让车机端崩溃；
 * - **必须在 IO 线程执行**：`sleepMs` 内部是阻塞 `Thread.sleep`，调用方不能是
 *   `Dispatchers.Main`（车机会卡 UI 3 秒）；
 * - 反射入口可注入，单测可以在 JVM 上覆盖「类缺失 / 类存在但调用失败」两条路径。
 */
internal class BydVehicleProbe(
    private val context: Context?,
    private val sink: (String) -> Unit,
    /** 类查找接缝：生产走 [Class.forName]，单测注入假的类。 */
    private val classLookup: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() },
    /** 采样间隔的接缝，单测传 0 避免真睡眠。 */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** 单次会话最多采几拍转速（500ms 一拍 → 约 3 秒）。 */
    private val speedSamples = 6

    fun run() {
        emit("start pkg=${context?.packageName ?: "-"} uid=${runCatching { context?.applicationInfo?.uid }.getOrNull() ?: -1} sdk=${Build.VERSION.SDK_INT} debug=${com.dilinkauto.server.BuildConfig.DEBUG}")
        reportPermission()
        probeEngine()
        probeStatistic()
        probeSpeed()
        probeAc()
        emit("done")
    }

    /** 权限现状：signature 级权限在非平台签名包里应当是 DENIED。 */
    private fun reportPermission() {
        val ctx = context ?: return emit("perm engine_get=no-context")
        val granted = runCatching {
            ctx.checkSelfPermission(ENGINE_GET_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrElse { false }
        emit("perm $ENGINE_GET_PERMISSION=${if (granted) "GRANTED" else "DENIED"}")
    }

    /** 主角：发动机转速。`getEngineSpeed()` → `super.get(1012, 0x14400012)`。 */
    private fun probeEngine() {
        val device = obtainDevice(ENGINE_DEVICE, "engine") ?: return
        for (i in 0 until speedSamples) {
            emit("engine speed[$i]=${callInt(device, "getEngineSpeed")}")
            if (i != speedSamples - 1) sleepMs(SAMPLE_INTERVAL_MS)
        }
        emit("engine power=${callInt(device, "getEnginePower")}")
    }

    /** 对照组：TripLogger 在这条路径上读到过真实值（SOC 86.0），用来区分「全挂」还是「只有 engine 不行」。 */
    private fun probeStatistic() {
        val device = obtainDevice(STATISTIC_DEVICE, "statistic") ?: return
        emit("statistic soc=${callDouble(device, "getElecPercentageValue")}")
    }

    private fun probeSpeed() {
        val device = obtainDevice(SPEED_DEVICE, "speed") ?: return
        emit("speed kmh=${callDouble(device, "getCurrentSpeed")}")
    }

    /** 更高门槛的对照：AC 设备初始化要求 `BYDAUTO_AC_COMMON`，比 engine 的 `_GET` 更严。 */
    private fun probeAc() {
        val device = obtainDevice(AC_DEVICE, "ac") ?: return
        emit("ac temp(area=1)=${callInt(device, "getTemprature", 1)}")
    }

    /**
     * `Class.forName` → `getInstance(Context)`。实例化失败时把异常链记下来，
     * 这是「服务端是否校验」最直接的证据。
     */
    private fun obtainDevice(className: String, label: String): Any? {
        val cls = classLookup(className)
        if (cls == null) {
            emit("dev $label class=missing($className)")
            return null
        }
        emit("dev $label class=ok")
        val ctx = context
        if (ctx == null) {
            emit("dev $label instance=no-context")
            return null
        }
        return try {
            val factory = cls.getMethod("getInstance", Context::class.java)
            val instance = factory.invoke(null, BydPermissionContext(ctx))
            emit("dev $label instance=ok")
            instance
        } catch (e: Throwable) {
            emit("dev $label instance=err ${describe(e)}")
            null
        }
    }

    private fun callInt(target: Any, method: String, arg: Int? = null): String =
        invoke(target, method, arg, Int::class.javaPrimitiveType)

    private fun callDouble(target: Any, method: String): String =
        invoke(target, method, null, null)

    private fun invoke(target: Any, method: String, arg: Int?, argType: Class<*>?): String = try {
        val m = if (arg == null) target.javaClass.getMethod(method) else target.javaClass.getMethod(method, argType)
        val value = if (arg == null) m.invoke(target) else m.invoke(target, arg)
        "ok:$value"
    } catch (e: Throwable) {
        "err:${describe(e)}"
    }

    /** 把反射调用里的异常剥到最内层（`InvocationTargetException.cause` 才是真相）。 */
    private fun describe(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = root.message?.replace('\n', ' ')?.take(160) ?: "-"
        return "${root.javaClass.simpleName}:$msg"
    }

    /** 同时写 logcat 与手机端回传通道；任何一路失败都不影响另一路。 */
    private fun emit(line: String) {
        runCatching { Log.i(TAG, line) }
        runCatching { sink("$TAG $line") }
    }

    companion object {
        const val TAG = "[BydProbe]"

        /**
         * 探针总开关。**本轮临时打开**：得出 #188 结论后连同本文件与调用点一起删除。
         */
        const val ENABLED = true

        private const val SAMPLE_INTERVAL_MS = 500L
        private const val ENGINE_GET_PERMISSION = "android.permission.BYDAUTO_ENGINE_GET"
        private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
        private const val STATISTIC_DEVICE = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
        private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val AC_DEVICE = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    }
}

/**
 * 绕过 BYDAUTO 客户端权限检查的 ContextWrapper（借自 `BYD-dev/TripLogger` 验证过的做法）。
 *
 * 只影响**客户端**的 `checkPermission` / `enforce*`；服务端 binder 侧的校验不受影响，
 * 这正是探针要区分的东西 —— 如果调用仍然抛 `SecurityException`，说明校验在服务端。
 */
internal class BydPermissionContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission.startsWith(BYDAUTO_PREFIX)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (permission.startsWith(BYDAUTO_PREFIX)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!permission.startsWith(BYDAUTO_PREFIX)) super.enforcePermission(permission, pid, uid, message)
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!permission.startsWith(BYDAUTO_PREFIX)) super.enforceCallingOrSelfPermission(permission, message)
    }

    private companion object {
        const val BYDAUTO_PREFIX = "android.permission.BYDAUTO_"
    }
}
