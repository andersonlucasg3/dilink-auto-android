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
 * 反射路径本身要在真机才能验证，这里只钉这两条不变量。
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
