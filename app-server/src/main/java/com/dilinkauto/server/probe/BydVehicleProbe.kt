package com.dilinkauto.server.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 车机侧 BYDAuto 车辆数据探针：issue #188 立单，issue #195 增补对照实验。
 *
 * 为什么要在车机里跑：我们自己的车机端包（`com.dilinkauto.server`，正式签名）拿不到
 * signature 级的 `android.permission.BYDAUTO_*`；但 SDK 的权限检查发生在**客户端 getter
 * 第一行**（`mContext.enforceCallingOrSelfPermission(...)`），[BydPermissionContext] 把这层
 * 拦掉之后，服务端会不会二次校验，就是本探针要回答的问题。
 *
 * #195 的现场数据是 `engine speed[0..5]=ok:8191`，而 `statistic soc=55.0`、`ac temp=27`
 * 正常。只靠这一行无法区分三种根因，所以本轮补了五组对照：
 * 1. `ctxStored=`：`getInstance` 之后 SDK 存的还是不是我们那个 wrapper；
 * 2. `permSelf=` / `permReal=`：wrapper 与真实 ApplicationContext 的权限判定并列；
 * 3. `realCtxCall=`：用真实 context 重新实例化并调 `getEngineSpeed()`；
 * 4. `engine coolant=` / `oil=` / `speedWarn=`：同 `BYDAUTO_ENGINE_GET` 权限的其它 getter；
 * 5. `listener=`：事件通道（`AbsBYDAutoEngineListener` 是抽象类，Proxy 不可用，见实现注释）；
 * 最后 `verdict=` 由纯函数 [decideSpeedVerdict] 自动判定，避免人工读日志把根因看反。
 *
 * 输出约定：一行一个 `[BydProbe] key=value`，经 `CarLogWriter` 以 `CAR_LOG` 帧回传手机，
 * 手机端落进 `client.log`。得到结论后本类连同调用点一起删除（见 issue #188 的清理条件）。
 *
 * 设计约束：
 * - 全反射，不依赖 `framework.jar`（`byd_api/readme.txt:3` 明确它不能当普通 jar 依赖）；
 * - 任何异常都必须就地吞掉并记录，绝不能让车机端崩溃；
 * - **必须在 IO 线程执行**：`sleepMs` 内部是阻塞 `Thread.sleep`，调用方不能是
 *   `Dispatchers.Main`（车机会卡 UI）；
 * - **必须可中止**：探针全程没有挂起点，取消协程不会自动打断阻塞循环，所以每一拍前都要
 *   问一次 [shouldAbort]；否则断线后旧探针会跑完并继续往下一个会话回传；
 * - 全程控制在 15 秒内：6 拍 × 500ms + 若干一次性的反射调用；
 * - 反射入口可注入，单测可以在 JVM 上覆盖各条对照路径。
 */
internal class BydVehicleProbe(
    private val context: Context?,
    private val sink: (String) -> Unit,
    /** 类查找接缝：生产走 [Class.forName]，单测注入假的类。 */
    private val classLookup: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() },
    /** 采样间隔的接缝，单测传 0 避免真睡眠。 */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    /** 中止判定接缝：会话在采样途中被取消时返回 true，生产侧接协程 `isActive`。 */
    private val shouldAbort: () -> Boolean = { false },
    /**
     * #195 对照 3 用的「真实 context」。生产取 ApplicationContext，和传进 `getInstance`
     * 的 wrapper 区分开；单测在 mockable android.jar 上拿不到 applicationContext，
     * 会回落到调用方给的 context。
     */
    private val realContext: Context? = context?.let { runCatching { it.applicationContext }.getOrNull() } ?: context,
) {
    /** 单次会话最多采几拍转速（500ms 一拍 → 约 3 秒）。 */
    private val speedSamples = 6

    fun run() {
        emit("start pkg=${context?.packageName ?: "-"} uid=${runCatching { context?.applicationInfo?.uid }.getOrNull() ?: -1} sdk=${Build.VERSION.SDK_INT} debug=${com.dilinkauto.server.BuildConfig.DEBUG}")
        emit("note rpm 8191/65535 = invalid sentinel; speed raw unit varies by model")
        if (abortedAt("start")) return
        reportPermission()
        val evidence = EngineEvidence()
        // 每个 probe 返回 false 表示会话已结束；`verdict` 与 `done` 只在全部跑完时才出现，
        // 否则日志读者会把「断线截断」当成「跑完了但读不到」。
        if (!probeEngine(evidence)) return
        if (!probeStatistic()) return
        if (!probeSpeed()) return
        if (!probeAc()) return
        emitVerdict(evidence)
        emit("done")
    }

    /** 会话已结束就停下，并把停在哪一步记下来（避免把「没跑完」误读成「读不到」）。 */
    private fun abortedAt(where: String): Boolean {
        if (!shouldAbort()) return false
        emit("aborted at $where")
        return true
    }

    /**
     * 权限现状。三条并列，缺一就会误判：
     * - `perm`：原始 service context 的 `checkSelfPermission`（保持 #188 的既有格式）；
     * - `permSelf`：wrapper 的 `checkSelfPermission`（没被 override，仍走 base）与
     *   `checkCallingOrSelfPermission`（被 override，SDK 实际调用的是这个入口）；
     * - `permReal`：真实 ApplicationContext 的 `checkSelfPermission`。
     */
    private fun reportPermission() {
        val ctx = context ?: return emit("perm engine_get=no-context")
        emit("perm $ENGINE_GET_PERMISSION=${granted(ctx)}")
        val wrapper = BydPermissionContext(ctx)
        emit("permSelf=${granted(wrapper)} checkCallingOrSelf=${callingOrSelf(wrapper)}")
        emit("permReal=${granted(realContext ?: ctx)}")
    }

    private fun granted(ctx: Context): String = runCatching {
        if (ctx.checkSelfPermission(ENGINE_GET_PERMISSION) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
    }.getOrElse { "err:${describe(it)}" }

    private fun callingOrSelf(ctx: Context): String = runCatching {
        if (ctx.checkCallingOrSelfPermission(ENGINE_GET_PERMISSION) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
    }.getOrElse { "err:${describe(it)}" }

    /** 主角：发动机转速。`getEngineSpeed()` → `super.get(1012, 0x14400012)`。 */
    private fun probeEngine(evidence: EngineEvidence): Boolean {
        val device = obtainDevice(ENGINE_DEVICE, "engine")
        // 对照 1：SDK 内部存的 context 是否还是我们的 wrapper（被换掉则 wrapper 失效）。
        evidence.storedContextIsWrapper = probeStoredContext(device)
        if (device == null) {
            evidence.listener = probeListener(null)
            return true
        }
        for (i in 0 until speedSamples) {
            if (abortedAt("engine speed[$i]")) return false
            val raw = callInt(device, "getEngineSpeed")
            emit("engine speed[$i]=$raw")
            // 只用第一拍参与判定，避免 6 个重复样本掩盖其它 getter 的差异。
            if (i == 0) evidence.readings += parseOkInt(raw)
            if (i != speedSamples - 1) sleepMs(SAMPLE_INTERVAL_MS)
        }
        if (abortedAt("engine power")) return false
        emit("engine power=${callInt(device, "getEnginePower")}")

        // 对照 3：真实 context 走同一条 getter。抛 SecurityException 说明客户端检查只被
        // wrapper 绕过；仍返回哨兵说明 binder 之后也没有真实数据。
        if (abortedAt("engine real ctx")) return false
        evidence.realContextCall = probeRealContextCall()

        // 对照 4：同权限的其它 getter。全哨兵 → 不是单个 feature 的问题。
        if (abortedAt("engine coolant")) return false
        val coolant = callInt(device, "getEngineCoolantLevel")
        emit("engine coolant=$coolant")
        evidence.readings += parseOkInt(coolant)

        if (abortedAt("engine oil")) return false
        val oil = callInt(device, "getOilLevel")
        emit("oil=$oil")
        evidence.readings += parseOkInt(oil)

        if (abortedAt("engine speed warn")) return false
        val warn = callInt(device, "getEngineSpeedWarningValue")
        emit("speedWarn=$warn")
        evidence.readings += parseOkInt(warn)

        // 对照 5：事件通道。
        evidence.listener = probeListener(device)
        return true
    }

    /**
     * #195 对照 1：反射遍历设备对象及其父类的非静态 `Context` 字段，逐个读值并判断是否
     * 还是 [BydPermissionContext]。
     *
     * 返回 `true` = 至少有一个字段仍是我们传进去的 wrapper；`false` = 读到了字段但都不是；
     * `null` = 没有可读的 Context 字段（无法判断，判定函数会退化为 `unclear`）。
     */
    private fun probeStoredContext(device: Any?): Boolean? {
        if (device == null) {
            emit("ctxStored=skip(no-instance)")
            return null
        }
        val fields = mutableListOf<Field>()
        var cls: Class<*>? = device.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && Context::class.java.isAssignableFrom(field.type)) {
                    fields += field
                }
            }
            cls = cls.superclass
        }
        if (fields.isEmpty()) {
            emit("ctxStored=skip(no-context-field)")
            return null
        }
        var readable = 0
        var sawWrapper = false
        for (field in fields) {
            val read = runCatching {
                field.isAccessible = true
                field.get(device)
            }
            if (read.isFailure) {
                emit("ctxStored=err field=${field.name} ${describe(read.exceptionOrNull()!!)}")
                continue
            }
            readable++
            val value = read.getOrNull()
            val isWrapper = value is BydPermissionContext
            if (isWrapper) sawWrapper = true
            emit("ctxStored=wrapper=$isWrapper field=${field.name} ctx=${value?.javaClass?.simpleName ?: "null"}")
        }
        if (readable == 0) {
            emit("ctxStored=skip(unreadable-field)")
            return null
        }
        return sawWrapper
    }

    /** #195 对照 3：用真实 context（非 wrapper）重新实例化并调用转速 getter。 */
    private fun probeRealContextCall(): RealContextCall {
        val real = realContext
        if (real == null) {
            emit("realCtxCall=skip(no-context)")
            return RealContextCall.SKIPPED
        }
        val cls = classLookup(ENGINE_DEVICE)
        if (cls == null) {
            emit("realCtxCall=skip(no-class)")
            return RealContextCall.SKIPPED
        }
        return try {
            val instance = cls.getMethod("getInstance", Context::class.java).invoke(null, real)
            if (instance == null) {
                emit("realCtxCall=skip(null-instance)")
                RealContextCall.SKIPPED
            } else {
                val raw = instance.javaClass.getMethod("getEngineSpeed").invoke(instance)
                val value = raw as? Int
                if (value != null && value in BYD_SENTINEL_VALUES) {
                    emit("realCtxCall=ok:$raw(sentinel)")
                    RealContextCall.OK_SENTINEL
                } else {
                    emit("realCtxCall=ok:$raw")
                    RealContextCall.OK_REAL
                }
            }
        } catch (e: Throwable) {
            val root = rootCause(e)
            emit("realCtxCall=err:${describe(e)}")
            if (root is SecurityException) RealContextCall.DENIED else RealContextCall.ERROR
        }
    }

    /**
     * #195 对照 5：事件通道。
     *
     * `AbsBYDAutoEngineListener` 是**抽象类**而不是接口（`temp.java:287-293`），
     * `java.lang.reflect.Proxy` 只能代理接口，硬造子类需要 DexMaker 或编译期依赖
     * car-lib —— 两者都超出本轮临时探针的范围，所以这里只显式 skip 并留证据，
     * 不用假实现污染结论。
     */
    private fun probeListener(device: Any?): ListenerState {
        val cls = classLookup(LISTENER_CLASS)
        if (cls == null) {
            emit("listener=skip(no-class($LISTENER_CLASS))")
            return ListenerState.NOT_ATTEMPTED
        }
        if (Modifier.isAbstract(cls.modifiers)) {
            emit("listener=skip(abstract-class)")
            return ListenerState.SKIP_ABSTRACT
        }
        if (device == null) {
            emit("listener=skip(no-instance)")
            return ListenerState.NOT_ATTEMPTED
        }
        return runCatching {
            val listener = cls.getDeclaredConstructor().newInstance()
            device.javaClass.getMethod("registerListener", cls).invoke(device, listener)
            emit("listener=registered")
            ListenerState.REGISTERED
        }.getOrElse {
            emit("listener=err:${describe(it)}")
            ListenerState.ERROR
        }
    }

    /** 把判定所需的全部对照结果汇总成一行，方便明天上车直接 grep `verdict=`。 */
    private fun emitVerdict(evidence: EngineEvidence) {
        val snapshot = evidence.snapshot()
        val sentinel = snapshot.engineReadings.count { it != null && it in BYD_SENTINEL_VALUES }
        emit(
            "verdict=${decideSpeedVerdict(snapshot)} ctxStored=${snapshot.storedContextIsWrapper ?: "unknown"} " +
                "realCtx=${snapshot.realContextCall.tag} sentinel=$sentinel/${snapshot.engineReadings.size} " +
                "listener=${snapshot.listener.tag}",
        )
    }

    /** 对照组：TripLogger 在这条路径上读到过真实值（SOC 86.0），用来区分「全挂」还是「只有 engine 不行」。 */
    private fun probeStatistic(): Boolean {
        if (abortedAt("statistic")) return false
        val device = obtainDevice(STATISTIC_DEVICE, "statistic") ?: return true
        emit("statistic soc=${callDouble(device, "getElecPercentageValue")}")
        return true
    }

    private fun probeSpeed(): Boolean {
        if (abortedAt("speed")) return false
        val device = obtainDevice(SPEED_DEVICE, "speed") ?: return true
        // 标签写 raw：`getCurrentSpeed()` 的原始单位随车型/固件而异，别写成 km/h 误导判读。
        emit("speed raw=${callDouble(device, "getCurrentSpeed")}")
        return true
    }

    /** 更高门槛的对照：AC 设备初始化要求 `BYDAUTO_AC_COMMON`，比 engine 的 `_GET` 更严。 */
    private fun probeAc(): Boolean {
        if (abortedAt("ac")) return false
        val device = obtainDevice(AC_DEVICE, "ac") ?: return true
        emit("ac temp(area=1)=${callInt(device, "getTemprature", 1)}")
        return true
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
            // getInstance 可能返回 null：那与「实例化成功但 getter 全挂」是两件事，必须区分。
            emit("dev $label instance=${if (instance == null) "null" else "ok"}")
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

    /** 只取 `ok:` 后面的整数值参与判定；`err:` 与不可解析的值都按「读数缺失」处理。 */
    private fun parseOkInt(raw: String): Int? = raw.removePrefix("ok:").toIntOrNull()

    /** 把反射调用里的异常剥到最内层（`InvocationTargetException.cause` 才是真相）。 */
    private fun describe(e: Throwable): String {
        val root = rootCause(e)
        val msg = root.message?.replace('\n', ' ')?.take(160) ?: "-"
        return "${root.javaClass.simpleName}:$msg"
    }

    private fun rootCause(e: Throwable): Throwable {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root
    }

    /** 同时写 logcat 与手机端回传通道；任何一路失败都不影响另一路。 */
    private fun emit(line: String) {
        runCatching { Log.i(TAG, line) }
        runCatching { sink("$TAG $line") }
    }

    /** 判定用的可变累积器，跑完再拍成不可变的 [BydSpeedEvidence] 交给纯函数。 */
    private class EngineEvidence {
        var storedContextIsWrapper: Boolean? = null
        var realContextCall: RealContextCall = RealContextCall.SKIPPED
        var listener: ListenerState = ListenerState.NOT_ATTEMPTED
        val readings = mutableListOf<Int?>()

        fun snapshot(): BydSpeedEvidence =
            BydSpeedEvidence(storedContextIsWrapper, realContextCall, readings.toList(), listener)
    }

    companion object {
        const val TAG = "[BydProbe]"

        /**
         * 探针总开关。**本轮临时打开**：得出 #188/#195 结论后连同本文件与调用点一起删除。
         */
        const val ENABLED = true

        private const val SAMPLE_INTERVAL_MS = 500L
        private const val ENGINE_GET_PERMISSION = "android.permission.BYDAUTO_ENGINE_GET"
        private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
        private const val LISTENER_CLASS = "android.hardware.bydauto.engine.AbsBYDAutoEngineListener"
        private const val STATISTIC_DEVICE = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
        private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val AC_DEVICE = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    }
}

/** `realCtxCall` 的分类，写日志时用短 tag，判定时用枚举。 */
internal enum class RealContextCall(val tag: String) {
    /** 真实 context 也拿到了非哨兵值。 */
    OK_REAL("ok-real"),

    /** 真实 context 没抛异常，但拿到的仍是哨兵（8191 / 65535）。 */
    OK_SENTINEL("ok-sentinel"),

    /** 真实 context 被客户端权限检查挡住（`SecurityException`）。 */
    DENIED("denied"),

    /** 其它异常（类缺失、反射失败等）。 */
    ERROR("error"),

    /** 没有可用 context / 类，本项没跑。 */
    SKIPPED("skipped"),
}

/** 事件通道（listener）的尝试结果。 */
internal enum class ListenerState(val tag: String) {
    NOT_ATTEMPTED("not-attempted"),
    SKIP_ABSTRACT("skip(abstract-class)"),
    REGISTERED("registered"),
    ERROR("error"),
}

/**
 * #195 判定所需的证据快照。字段顺序与探针输出顺序一致，便于回看日志。
 *
 * @param storedContextIsWrapper `ctxStored` 的结论：`true`=SDK 仍持有我们的 wrapper；
 *   `false`=被换成别的 context；`null`=没有可读字段。
 * @param realContextCall 真实 context 的对照调用分类。
 * @param engineReadings 同 `BYDAUTO_ENGINE_GET` 权限的 getter 读数（转速首拍 + 冷却液 + 机油 + 超速警告）；
 *   `null` 表示该 getter 抛异常。
 * @param listener 事件通道的尝试结果。
 */
internal data class BydSpeedEvidence(
    val storedContextIsWrapper: Boolean?,
    val realContextCall: RealContextCall,
    val engineReadings: List<Int?>,
    val listener: ListenerState,
)

/** 车型上报的无效哨兵值：8191（转速无效）、65535（链路错误 / 从未取到）。 */
internal val BYD_SENTINEL_VALUES = setOf(8191, 65535)

internal const val VERDICT_SERVER_PERMISSION = "server-permission"
internal const val VERDICT_CONTEXT_SWAPPED = "context-swapped"
internal const val VERDICT_LISTENER_REQUIRED = "listener-required"
internal const val VERDICT_UNCLEAR = "unclear"

/**
 * #195 的定性规则（纯函数，单测钉死）。
 *
 * 优先级说明：
 * 1. 真实 context **也**返回哨兵 → 与 wrapper 无关，只能是服务端不给数据；
 * 2. SDK 存的不是 wrapper → 先怀疑 wrapper 传递失效，其它结论都不可信；
 * 3. 同步 getter 全哨兵但事件通道能注册 → 需要沿 `onEngineSpeedChanged` 继续验证；
 * 4. wrapper 被保留、读数全哨兵 → 客户端检查确实被绕过，binder 之后仍然拿哨兵 = 服务端拒绝；
 * 5. 真实 context 被 `SecurityException` 挡住而其它证据不足时，也归到服务端拒绝；
 * 6. 其余（未复现、读数缺失、混合值）一律 `unclear`，不硬下结论。
 */
internal fun decideSpeedVerdict(evidence: BydSpeedEvidence): String {
    val readings = evidence.engineReadings
    // 没有读数或某个 getter 抛异常：证据链断了，不能定性。
    if (readings.isEmpty() || readings.any { it == null }) return VERDICT_UNCLEAR
    // 出现非哨兵值：本轮的哨兵现象没有复现（或混合），不下结论。
    if (readings.any { it != null && it !in BYD_SENTINEL_VALUES }) return VERDICT_UNCLEAR

    if (evidence.realContextCall == RealContextCall.OK_SENTINEL) return VERDICT_SERVER_PERMISSION
    if (evidence.storedContextIsWrapper == false) return VERDICT_CONTEXT_SWAPPED
    if (evidence.listener == ListenerState.REGISTERED) return VERDICT_LISTENER_REQUIRED
    if (evidence.storedContextIsWrapper == true) return VERDICT_SERVER_PERMISSION
    if (evidence.realContextCall == RealContextCall.DENIED) return VERDICT_SERVER_PERMISSION
    return VERDICT_UNCLEAR
}

/**
 * 是否应该对这条权限放行（只放 BYDAUTO_ 前缀的客户端检查）。
 *
 * 抽成顶层纯函数的唯一目的：`BYDAUTO_PREFIX` 写错一个字母，所有 getter 会抛
 * `SecurityException`，而实车上看起来和「服务端拒绝」一模一样 —— 必须有单测钉住。
 */
internal fun shouldGrantBydPermission(permission: String?): Boolean =
    permission != null && permission.startsWith(BYDAUTO_PERMISSION_PREFIX)

private const val BYDAUTO_PERMISSION_PREFIX = "android.permission.BYDAUTO_"

/**
 * 绕过 BYDAUTO 客户端权限检查的 ContextWrapper（借自 `BYD-dev/TripLogger` 验证过的做法）。
 *
 * 只影响**客户端**的 `checkPermission` / `enforce*`；服务端 binder 侧的校验不受影响，
 * 这正是探针要区分的东西 —— 如果调用仍然抛 `SecurityException`，说明校验在服务端。
 *
 * 四个入口都要覆盖（`checkPermission` / `checkCallingOrSelfPermission` / `enforce*`），
 * 只拦一个的话 SDK 换个调用点就失效。注意 `checkSelfPermission` 没有 override：
 * SDK 实际用的是 `enforceCallingOrSelfPermission`，`checkSelfPermission` 直通 base，
 * 所以 `permSelf` 与 `permReal` 可能相同，真正的差异在 `permSelf` 行里的 `checkCallingOrSelf`。
 *
 * 参数声明为不可空：SDK stub 上这些参数标了 `@NonNull`，Kotlin 不接受可空覆盖签名
 * （`'checkPermission' overrides nothing`）。放行判定本身在 [shouldGrantBydPermission]
 * 里对 null 做了保护，供非反射调用方复用。
 */
internal class BydPermissionContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforcePermission(permission, pid, uid, message)
        }
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}
