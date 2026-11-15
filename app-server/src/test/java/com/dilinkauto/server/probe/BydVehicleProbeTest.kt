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
