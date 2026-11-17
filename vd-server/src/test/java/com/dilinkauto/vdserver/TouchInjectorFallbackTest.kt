package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TouchInjector.handleTouchFrame —— 归一化坐标缩放 + shell 回退路径。
 *
 * 不调 initInputManager → inputManager==null，injectTouch 走 `input -d` shell 回退分支：
 * DOWN/UP 落一条 tap，MOVE 不落。这是 S-09/坐标缩放在 JVM 上唯一可达的纯逻辑路径。
 */
class TouchInjectorFallbackTest {

    private class CapturingShell : OutputStream() {
        val sb = StringBuilder()
        override fun write(b: ByteArray, off: Int, len: Int) { sb.append(String(b, off, len)) }
        override fun write(b: Int) { sb.append(b.toChar()) }
        fun lines(): List<String> = sb.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun control(type: Byte, payload: ByteArray) = FrameCodec.Frame(Channel.INPUT, type, payload)

    @Test
    fun `touch down scales normalized coords to pixels and shells a tap`() {
        val shell = CapturingShell()
        val injector = TouchInjector(1408, 792, { 7 }, { shell }, { })
        val e = TouchEvent(action = 0, pointerId = 0, x = 0.5f, y = 0.25f, pressure = 1f, timestamp = 0L)
        injector.handleTouchFrame(control(InputMsg.TOUCH_DOWN, e.encode()))
        // 0.5*1408=704, 0.25*792=198
        assertTrue(shell.lines().any { it == "input -d 7 tap 704 198" })
    }

    @Test
    fun `touch up scales and shells a tap`() {
        val shell = CapturingShell()
        val injector = TouchInjector(1408, 792, { 7 }, { shell }, { })
        val e = TouchEvent(action = 2, pointerId = 0, x = 0.5f, y = 0.25f, pressure = 1f, timestamp = 0L)
        injector.handleTouchFrame(control(InputMsg.TOUCH_UP, e.encode()))
        assertTrue(shell.lines().any { it == "input -d 7 tap 704 198" })
    }

    @Test
    fun `touch move does not shell a tap in the fallback path`() {
        val shell = CapturingShell()
        val injector = TouchInjector(1408, 792, { 7 }, { shell }, { })
        val e = TouchEvent(action = 1, pointerId = 0, x = 0.5f, y = 0.25f, pressure = 1f, timestamp = 0L)
        injector.handleTouchFrame(control(InputMsg.TOUCH_MOVE, e.encode()))
        assertTrue("MOVE 在回退路径下不应落 tap", shell.lines().isEmpty())
    }

    @Test
    fun `touch move batch decodes every pointer and shells none`() {
        val shell = CapturingShell()
        val injector = TouchInjector(1408, 792, { 7 }, { shell }, { })
        val batch = TouchMoveBatch(
            listOf(
                TouchEvent(1, 0, 0.1f, 0.1f, 1f, 0L),
                TouchEvent(1, 1, 0.2f, 0.2f, 1f, 0L)
            )
        )
        injector.handleTouchFrame(control(InputMsg.TOUCH_MOVE_BATCH, batch.encode()))
        assertTrue("批量 MOVE 不应落 tap", shell.lines().isEmpty())
    }

    @Test
    fun `down is routed to the live display id`() {
        val shell = CapturingShell()
        val injector = TouchInjector(1408, 792, { 9 }, { shell }, { }) // displayId=9
        val e = TouchEvent(action = 0, pointerId = 0, x = 1f, y = 1f, pressure = 1f, timestamp = 0L)
        injector.handleTouchFrame(control(InputMsg.TOUCH_DOWN, e.encode()))
        assertTrue(shell.lines().any { it == "input -d 9 tap 1408 792" })
    }
}
