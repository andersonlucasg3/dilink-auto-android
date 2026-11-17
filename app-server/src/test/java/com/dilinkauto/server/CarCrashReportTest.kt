package com.dilinkauto.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarCrashReport.build 单测：崩溃报告的格式契约。
 * CarCrashHandler 把这段文本写进 crash-pending.log，CarConnectionService.onCreate 再逐行转发给手机，
 * 所以头部/时间戳/线程/堆栈/Process State 分节一旦漂移，手机侧消费者会看不懂 —— 此前零覆盖。
 * android.os.Process.myPid()/myUid() 在 stub 下返回 0，故只断言标签不断言值。
 *
 * 注意：不要测 CarCrashHandler.uncaughtException（会 System.exit 杀掉测试 JVM）。
 */
class CarCrashReportTest {

    @Test
    fun `build starts with header and US timestamp`() {
        val r = CarCrashReport.build(Thread("worker"), IllegalStateException("boom"))
        assertTrue(r.startsWith("=== DiLink Auto Car Crash Report "))
        assertTrue(
            "US 时间戳格式须保持",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ===""").containsMatchIn(r)
        )
    }

    @Test
    fun `build records thread name exception and frames`() {
        val r = CarCrashReport.build(Thread("worker"), IllegalStateException("boom"))
        assertTrue(r.contains("thread=worker"))
        assertTrue(r.contains("java.lang.IllegalStateException: boom"))
        assertTrue("至少一帧", r.contains("\tat "))
    }

    @Test
    fun `build includes process state section`() {
        val r = CarCrashReport.build(Thread("t"), RuntimeException("x"))
        assertTrue(r.contains("── Process State ──"))
        assertTrue(r.contains("pid="))
        assertTrue(r.contains("uid="))
    }

    @Test
    fun `build includes cause chain`() {
        val r = CarCrashReport.build(Thread("t"), Exception("outer", Exception("inner")))
        assertTrue(r.contains("Caused by:"))
    }

    @Test
    fun `build includes suppressed exceptions`() {
        val e = IllegalStateException("primary")
        e.addSuppressed(RuntimeException("side"))
        val r = CarCrashReport.build(Thread("t"), e)
        assertTrue(r.contains("Suppressed:"))
    }

    @Test
    fun `build handles a message less throwable`() {
        val r = CarCrashReport.build(Thread("t"), RuntimeException())
        assertTrue(r.contains("java.lang.RuntimeException"))
    }

    @Test
    fun `build body is deterministic apart from the timestamp line`() {
        fun once() = CarCrashReport.build(Thread("t"), IllegalStateException("boom"))
            .lines().filterNot { it.startsWith("=== DiLink Auto Car Crash Report ") }
            .joinToString("\n")
        // 除了时间戳头之外，堆栈行号可能因调用点略有差异；比对两段主体是否稳定
        assertEquals(once(), once())
    }
}
