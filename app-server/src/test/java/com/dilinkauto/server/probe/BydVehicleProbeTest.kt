package com.dilinkauto.server.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #188 探针的回归网。
 *
 * 探针跑在车机服务进程里，唯一不可接受的行为是**抛异常**（会把连接流程带崩）；
 * 其次是日志格式被改坏（用户在手机上靠 `[BydProbe]` 前缀 grep）。
 *
 * 残留的不覆盖点：`app-server` 没有 Robolectric，`ContextWrapper(null)` 的父类方法在
 * mockable android.jar 上返回默认值（`0` 恰好等于 `PERMISSION_GRANTED`），所以
 * [BydPermissionContext] 四个 override 的「未放行分支」在 JVM 上不可区分。判定逻辑
 * 本身由 [shouldGrantBydPermission] 的单测钉死，override 的接线靠代码评审兜底。
 */
class BydVehicleProbeTest {

    @Test
    fun `missing device class is reported and never throws`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { null },
            sleepMs = { },
        ).run()

        assertTrue("必须记录 start：$lines", lines.any { it.contains("start pkg=") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
        assertTrue("类缺失要逐设备记下来：$lines", lines.count { it.contains("class=missing") } >= 4)
    }

    @Test
    fun `class present but no context is reported without throwing`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { Any::class.java },
            sleepMs = { },
        ).run()

        assertTrue("类可用时必须先报 class=ok：$lines", lines.any { it.contains("dev engine class=ok") })
        assertTrue("没有 Context 时只报 no-context，不得抛异常：$lines", lines.any { it.contains("dev engine instance=no-context") })
    }

    /** 主路径：`getInstance` 成功 + getter 返回真实值，这套格式就是用户明天要读的。 */
    @Test
    fun `successful getInstance and getters render ok values`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { FakeDevice::class.java },
            sleepMs = { },
        ).run()

        assertTrue("必须报实例化成功：$lines", lines.any { it.contains("dev engine instance=ok") })
        assertTrue("转速要按 6 拍输出：$lines", lines.count { it.contains("engine speed[") } == 6)
        assertTrue("转速取值格式：$lines", lines.any { it.contains("engine speed[0]=ok:1234") })
        assertTrue("SOC 取值格式：$lines", lines.any { it.contains("statistic soc=ok:86.0") })
        assertTrue("速度必须标 raw：$lines", lines.any { it.contains("speed raw=ok:55.0") })
        assertTrue("空调取值格式：$lines", lines.any { it.contains("ac temp(area=1)=ok:22") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
    }

    /** `getInstance` 返回 null 与「实例化成功但 getter 全挂」是两件事，不能让日志看起来一样。 */
    @Test
    fun `getInstance returning null is reported as null`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { NullDevice::class.java },
            sleepMs = { },
        ).run()

        assertTrue("必须区分 null：$lines", lines.any { it.contains("dev engine instance=null") })
        assertFalse("不得误报成功：$lines", lines.any { it.contains("dev engine instance=ok") })
    }

    /** 服务端二次校验失败就长这样：异常剥到最内层 cause，一行不抛。 */
    @Test
    fun `getInstance throwing reports the innermost cause`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { ThrowingDevice::class.java },
            sleepMs = { },
        ).run()

        val err = lines.firstOrNull { it.contains("dev engine instance=err") }
        assertTrue("必须记录实例化异常：$lines", err != null)
        assertTrue("异常类型与信息要可读：$err", err!!.contains("SecurityException") && err.contains("server denied"))
        assertTrue("异常之后仍要走完其它设备：$lines", lines.any { it.contains("dev ac instance=err") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
    }

    /**
     * 断线可中止：探针没有挂起点，取消协程不会打断阻塞循环，必须靠 [BydVehicleProbe] 自己
     * 每拍前问一次；否则旧探针会跑完并往下一个会话继续回传。
     */
    @Test
    fun `abort stops the sampling loop and says where it stopped`() {
        var sleeps = 0
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { FakeDevice::class.java },
            sleepMs = { sleeps++ },
            shouldAbort = { sleeps >= 2 },
        ).run()

        assertEquals("断线后不得继续采样：$lines", 2, lines.count { Regex("""engine speed\[\d+]=""").containsMatchIn(it) })
        assertTrue("必须写清停在哪一步：$lines", lines.any { it.contains("aborted at engine speed[2]") })
        assertFalse("中止后不得再打 done：$lines", lines.any { it.contains("done") })
        assertFalse("中止后不得再碰其它设备：$lines", lines.any { it.contains("dev statistic") })
    }

    /**
     * 前缀判定的假绿面：前缀写错一个字母，SDK 所有 getter 都会抛 `SecurityException`，
     * 实车日志看起来与「服务端拒绝」一模一样（这正是探针要回答的问题），必须先钉死。
     */
    @Test
    fun `only BYDAUTO permissions are granted by the wrapper`() {
        assertTrue(
            "engine getter 的权限必须放行，否则探针结论全部无效",
            shouldGrantBydPermission("android.permission.BYDAUTO_ENGINE_GET"),
        )
        assertTrue(
            "AC/statistic 等其它 BYDAUTO 权限也必须放行",
            shouldGrantBydPermission("android.permission.BYDAUTO_AC_COMMON"),
        )
        assertFalse(
            "非 BYDAUTO 权限一律不得放行，否则 wrapper 越权",
            shouldGrantBydPermission("android.permission.CAMERA"),
        )
        assertFalse("null 不得放行", shouldGrantBydPermission(null))
        assertFalse("空串不得放行", shouldGrantBydPermission(""))
    }

    @Test
    fun `every line carries the grep-able tag`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { null },
            sleepMs = { },
        ).run()

        assertEquals("探针日志必须以 TAG 开头，否则手机端 grep 失效", "[BydProbe]", BydVehicleProbe.TAG)
        assertTrue("所有行都要带 TAG：$lines", lines.all { it.startsWith(BydVehicleProbe.TAG) })
    }

    /**
     * #195 对照 1：`getInstance` 之后 SDK 里存的是不是我们传入的 wrapper。
     * 如果被换成 ApplicationContext，wrapper 的作用范围就不覆盖 getter 的权限检查。
     */
    @Test
    fun `stored context field is reported as wrapper`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("必须逐字段打印并认出 wrapper：$lines", lines.any { it.contains("ctxStored=wrapper=true field=mContext") })
        assertTrue("必须给出聚合结论：$lines", lines.any { it.contains("ctxStored=state=wrapper") })
    }

    /** 反向对照：字段里存的不是 wrapper 时必须报 false，否则「context 被换掉」这条永远看不出来。 */
    @Test
    fun `stored context field that is not our wrapper is reported as false`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, SwappedContextDevice::class.java) })

        assertTrue("字段存在但不是 wrapper 时必须报 false：$lines", lines.any { it.contains("ctxStored=wrapper=false field=mContext") })
        assertTrue("聚合结论不能是 wrapper：$lines", lines.any { it.contains("ctxStored=state=not-wrapper") })
        assertFalse("不得误报成 wrapper：$lines", lines.any { it.contains("ctxStored=wrapper=true") })
    }

    /**
     * #195 对照 1 的假阳面：父类/无关 Context 字段混进来时，「任一字段是 wrapper」会
     * 让 context-swapped 分支永远不可达。必须单独报 mixed 并让判定退化为 unclear。
     */
    @Test
    fun `mixed context fields are reported as mixed`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, MixedContextDevice::class.java) })

        assertTrue("必须逐字段打印：$lines", lines.any { it.contains("ctxStored=wrapper=true field=mContext") })
        assertTrue("必须逐字段打印：$lines", lines.any { it.contains("ctxStored=wrapper=false field=mOther") })
        assertTrue("混合必须单独成态：$lines", lines.any { it.contains("ctxStored=state=mixed") })
        assertTrue("mixed 不得给出强结论：$lines", lines.any { it.contains("verdict=unclear") })
    }

    /**
     * #195 对照 2：wrapper 的 `checkSelfPermission` 不能代表 SDK 实际入口，
     * 必须把 `checkCallingOrSelfPermission` 的结果并列打出来。
     */
    @Test
    fun `permission contrast prints wrapper self check and sdk entry`() {
        val lines = runProbe()

        assertTrue("必须打印 wrapper 视角：$lines", lines.any { it.contains("permSelf=") && it.contains("checkCallingOrSelf=") })
        assertTrue("必须打印真实 context 视角：$lines", lines.any { it.contains("permReal=") })
    }

    /**
     * #195 对照 3：真实 ApplicationContext 重新实例化并调用 getter。
     * 这一行是「应用侧校验只被 wrapper 绕过」与「服务端拒绝」的分水岭。
     */
    @Test
    fun `real context call is classified as sentinel ok`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("真实 context 调用必须单独成行：$lines", lines.any { it.contains("realCtxCall=ok:8191(sentinel)") })
    }

    /** 真实 context 被客户端权限挡住时要把 SecurityException 打出来，而不是只写 err。 */
    @Test
    fun `real context call reports security exception`() {
        val lines = runProbe(
            classLookup = { name -> deviceClassFor(name, RealDeniedDevice::class.java) },
            realContext = android.content.ContextWrapper(null),
        )

        assertTrue("必须记录 SecurityException：$lines", lines.any { it.contains("realCtxCall=err:SecurityException:") })
    }

    /**
     * #195 对照 4：同权限的其它 getter。若冷却液/机油/超速警告也全是哨兵，
     * 说明不是单个 feature 的问题，更支持「服务端按 uid 拒绝」。
     */
    @Test
    fun `same permission getters are printed for comparison`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("必须读冷却液：$lines", lines.any { it.contains("engine coolant=ok:8191") })
        assertTrue("必须读机油：$lines", lines.any { it.contains("oil=ok:65535") })
        assertTrue("必须读超速警告：$lines", lines.any { it.contains("speedWarn=ok:8191") })
    }

    /**
     * #195 对照 5：`AbsBYDAutoEngineListener` 是抽象类，Proxy 不可用；
     * 找不到具体 Listener 时必须显式 skip，不能硬造一个假实现让结论失真。
     */
    @Test
    fun `abstract listener class is skipped explicitly`() {
        val lines = runProbe()

        assertTrue("抽象类必须报 skip：$lines", lines.any { it.contains("listener=skip(abstract-class)") })
    }

    /** 全哨兵 + wrapper 保留 + 真实 context 被 SecurityException 拒绝 → 服务端拒绝。 */
    @Test
    fun `sentinel readings with denied real context conclude server permission`() {
        val lines = runProbe(
            classLookup = { name -> deviceClassFor(name, RealDeniedDevice::class.java) },
            realContext = android.content.ContextWrapper(null),
        )

        assertTrue("必须自动给出结论：$lines", lines.any { it.contains("verdict=server-permission") })
    }

    /**
     * 墙钟守卫：某次反射卡住时不能把整段车机会话拖死。到点要留一行 deadline 并收尾，
     * 不能静默消失（否则会被误读成「读不到」）。
     */
    @Test
    fun `wall clock budget stops the probe and still wraps up`() {
        var now = 0L
        val lines = runProbe(clockMs = { now += 5_000; now })

        assertTrue("到点必须留证据：$lines", lines.any { it.contains("deadline reached — skip remaining") })
        assertTrue("收尾要给结论：$lines", lines.any { it.contains("verdict=") })
        assertTrue("收尾要写 done：$lines", lines.any { it.contains("done") })
    }

    /**
     * 判定纯函数的分支表。#195 的结论只能靠日志自动化得出，
     * 人眼看「8191 + SecurityException」很容易把两种根因看反。
     */
    @Test
    fun `verdict rules cover every documented outcome`() {
        val allSentinel = listOf(8191, 8191, 65535, 8191)

        assertEquals(
            "真实 context 被 SecurityException 拒绝 + wrapper 保留 → 服务端拒绝",
            "server-permission",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "ctx 归属读不到但真实 context 被拒 → 仍可归到服务端拒绝",
            "server-permission",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.UNKNOWN, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "真实 context 也拿哨兵但没被拒 → 无法排除引擎未就绪",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.OK_SENTINEL, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "SDK 存的不是 wrapper 且真实 context 不被拒 → 先修 wrapper 传递",
            "context-swapped",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.NOT_WRAPPER, RealContextCall.OK_SENTINEL, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "Context 字段混合 → 归属有歧义，不下结论",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.MIXED, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "仅注册 listener 不够，必须真的回调出真实值",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.SKIPPED, allSentinel, ListenerState.REGISTERED)),
        )
        assertEquals(
            "事件通道真的回调出真实值 → 需要 listener",
            "listener-required",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.SKIPPED, allSentinel, ListenerState.REGISTERED_REAL_VALUE)),
        )
        assertEquals(
            "出现真实值 → 未复现",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, listOf(1234, 8191, 8191, 8191), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "读数缺失 → 证据不足",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, emptyList(), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "部分 getter 抛异常 → 证据混杂",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, listOf(8191, null, 8191, 8191), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "既不知道 ctx 归属、也没有真实 context 对照 → 不能下结论",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.UNKNOWN, RealContextCall.SKIPPED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
    }

    /** 统一构造探针，默认把 listener 类映射为抽象类，模拟实车上的 `AbsBYDAutoEngineListener`。 */
    private fun runProbe(
        classLookup: (String) -> Class<*>? = { name -> deviceClassFor(name, FakeDevice::class.java) },
        context: android.content.Context? = android.content.ContextWrapper(null),
        realContext: android.content.Context? = null,
        clockMs: () -> Long = { 0L },
    ): List<String> {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = context,
            sink = { lines += it },
            classLookup = classLookup,
            sleepMs = { },
            // 生产默认取 applicationContext；mockable android.jar 上取不到，回落到 probe context。
            realContext = realContext ?: context,
            clockMs = clockMs,
        ).run()
        return lines
    }

    /** listener 抽象类走单独的类，设备类由各用例指定。 */
    private fun deviceClassFor(name: String, device: Class<*>): Class<*>? =
        if (name.endsWith("AbsBYDAutoEngineListener")) AbstractEngineListener::class.java else device
}

/**
 * 假设备：`static getInstance(Context)` 与 getter 签名都照抄 SDK，用来在 JVM 上跑通
 * 反射主路径（getter 名字/参数写错会立刻变成 `err:NoSuchMethodException`）。
 */
internal class FakeDevice {
    fun getEngineSpeed(): Int = 1234
    fun getEnginePower(): Int = 42
    fun getElecPercentageValue(): Double = 86.0
    fun getCurrentSpeed(): Double = 55.0
    fun getTemprature(area: Int): Int = 22

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): FakeDevice = FakeDevice()
    }
}

internal class NullDevice {
    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): NullDevice? = null
    }
}

/** 模拟「客户端权限被绕过、服务端仍然校验」的结局。 */
internal class ThrowingDevice {
    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): ThrowingDevice? =
            throw SecurityException("client check bypassed, server denied")
    }
}

/**
 * #195 假设备：像 SDH 一样把传入的 context 原样存进实例字段，
 * 并且所有同权限 getter 都返回哨兵值 —— 复现实车上的 `getEngineSpeed()=8191`。
 */
internal class StoredContextDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 65535
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 27

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): StoredContextDevice = StoredContextDevice(context)
    }
}

/** #195 假设备：`getInstance` 把 wrapper 换成别的 context，用来验证 `ctxStored=false` 分支。 */
internal class SwappedContextDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): SwappedContextDevice =
            SwappedContextDevice(android.content.ContextWrapper(context))
    }
}

/**
 * #195 假设备：同时存在两个 Context 字段，一个保留 wrapper、一个被换成普通 Context，
 * 用来锁住「任一字段是 wrapper 就算成功」的假阳面。
 */
internal class MixedContextDevice(
    private val mContext: android.content.Context,
    private val mOther: android.content.Context,
) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): MixedContextDevice =
            MixedContextDevice(context, android.content.ContextWrapper(context))
    }
}

/**
 * #195 假设备：只有 wrapper 才放行，真实 context 直接抛 `SecurityException` ——
 * 复现「客户端权限检查只被 wrapper 绕过」的那一半。
 */
internal class RealDeniedDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): RealDeniedDevice? {
            if (context !is BydPermissionContext) throw SecurityException("real context denied")
            return RealDeniedDevice(context)
        }
    }
}

/** 模拟 `AbsBYDAutoEngineListener`：抽象类，`java.lang.reflect.Proxy` 无法代理。 */
internal abstract class AbstractEngineListener
