package com.dilinkauto.desktop.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedGate 单测：这是"先喂 CONFIG"和"重建后跳过 P 帧等 IDR"两条解码约定的唯一实现点，
 * 也是 Phase 1b 最容易回归的逻辑。
 */
class FeedGateTest {

    /** 用字符串模拟字节，方便断言写入顺序。 */
    private fun gate(initialConfig: String? = null, writeResult: () -> Boolean = { true }) =
        object {
            val out = mutableListOf<String>()
            val g = FeedGate(
                write = { bytes ->
                    val ok = writeResult()
                    if (ok) out.add(String(bytes))
                    ok
                },
                initialConfig = initialConfig?.toByteArray(),
            )
        }

    @Test
    fun `见到 CONFIG 之前的所有帧都被丢弃`() {
        val t = gate()
        t.g.beginStream()
        assertFalse(t.g.onFrame("P1".toByteArray(), isKeyFrame = false))
        assertFalse(t.g.onFrame("I1".toByteArray(), isKeyFrame = true))
        assertTrue(t.out.isEmpty())
        assertEquals(2L, t.g.droppedPreConfig)
    }

    @Test
    fun `CONFIG 到达后写入，随后 IDR 放行`() {
        val t = gate()
        t.g.beginStream()
        assertTrue(t.g.onConfig("CFG".toByteArray()))
        assertFalse(t.g.onFrame("P1".toByteArray(), isKeyFrame = false)) // 等 IDR
        assertTrue(t.g.onFrame("I1".toByteArray(), isKeyFrame = true))
        assertTrue(t.g.onFrame("P2".toByteArray(), isKeyFrame = false)) // IDR 之后 P 帧正常
        assertEquals(listOf("CFG", "I1", "P2"), t.out)
    }

    @Test
    fun `beginStream 先重放缓存的 CONFIG 再放行 IDR`() {
        val t = gate(initialConfig = "CFG")
        t.g.beginStream()
        assertEquals(listOf("CFG"), t.out) // 重建时 CONFIG 必须排在第一个
        assertTrue(t.g.onFrame("I1".toByteArray(), isKeyFrame = true))
        assertEquals(listOf("CFG", "I1"), t.out)
    }

    @Test
    fun `重建后丢弃 P 帧直到 IDR，并累计跳过计数`() {
        val t = gate()
        t.g.onConfig("CFG".toByteArray())
        t.g.beginStream()
        assertFalse(t.g.onFrame("P1".toByteArray(), isKeyFrame = false))
        assertFalse(t.g.onFrame("P2".toByteArray(), isKeyFrame = false))
        assertTrue(t.g.onFrame("I1".toByteArray(), isKeyFrame = true))
        assertEquals(2L, t.g.skippedPFrames)
        assertEquals(listOf("CFG", "CFG", "I1"), t.out)
    }

    @Test
    fun `会话中途的新 CONFIG 直接透传并更新缓存`() {
        val t = gate()
        t.g.beginStream()
        t.g.onConfig("CFG1".toByteArray())
        t.g.onFrame("I1".toByteArray(), isKeyFrame = true)
        assertTrue(t.g.onConfig("CFG2".toByteArray()))
        assertEquals(listOf("CFG1", "I1", "CFG2"), t.out)
        assertEquals("CFG2", String(t.g.config!!))
    }

    @Test
    fun `管道已关闭时 CONFIG 不算已发送，帧继续被丢弃`() {
        val t = gate(writeResult = { false })
        t.g.beginStream()
        assertFalse(t.g.onConfig("CFG".toByteArray()))
        assertFalse(t.g.onFrame("I1".toByteArray(), isKeyFrame = true))
        assertTrue(t.out.isEmpty())
        assertEquals(1L, t.g.droppedPreConfig) // CONFIG 没写进去，IDR 也被网关挡住
    }
}