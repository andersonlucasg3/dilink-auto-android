package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码器生命周期配对（issue #205）。
 *
 * 现场「建 5 次只停 4 次」无法判断是真泄漏还是日志被清（实际是后者），原因就是没有
 * generation 锚点。这里用假解码器驱动 [releaseCodecQuietly]，钉三件事：
 *  1. 顺序固定「先 stop 再 release」；
 *  2. 每释放一个 generation 就销账一个，创建数与释放数可严格配对；
 *  3. 同一 generation 释放两次必须被识别（返回 false 让上层打 W），而不是静默抹平。
 */
class CodecLifecycleLedgerTest {

    /** 假解码器：只记录调用顺序，不碰 MediaCodec（它是 final 类，无法打桩）。 */
    private class FakeDecoder {
        val calls = mutableListOf<String>()
        var stopThrows = false
        var releaseThrows = false
    }

    private fun release(fake: FakeDecoder, ledger: CodecLifecycleLedger, generation: Int): Boolean =
        releaseCodecQuietly(
            ledger = ledger,
            generation = generation,
            stop = {
                fake.calls += "stop"
                if (fake.stopThrows) throw IllegalStateException("stop")
            },
            release = {
                fake.calls += "release"
                if (fake.releaseThrows) throw IllegalStateException("release")
            },
        )

    @Test
    fun `释放顺序必须是先 stop 再 release`() {
        val ledger = CodecLifecycleLedger()
        val fake = FakeDecoder()
        val generation = ledger.onCreated()

        assertTrue(release(fake, ledger, generation))

        assertEquals(listOf("stop", "release"), fake.calls)
        assertEquals("释放后必须销账", 0, ledger.outstanding())
    }

    @Test
    fun `stop 抛异常也要继续 release 并销账`() {
        val ledger = CodecLifecycleLedger()
        val fake = FakeDecoder().apply { stopThrows = true }
        val generation = ledger.onCreated()

        assertTrue("stop 异常不能挡住销账", release(fake, ledger, generation))

        assertEquals(listOf("stop", "release"), fake.calls)
        assertEquals(0, ledger.outstanding())
    }

    @Test
    fun `release 抛异常也要销账`() {
        val ledger = CodecLifecycleLedger()
        val fake = FakeDecoder().apply { releaseThrows = true }
        val generation = ledger.onCreated()

        assertTrue(release(fake, ledger, generation))

        assertEquals(listOf("stop", "release"), fake.calls)
        assertEquals(0, ledger.outstanding())
    }

    @Test
    fun `generation 单调递增且一次创建只对应一次释放`() {
        val ledger = CodecLifecycleLedger()
        val generations = (1..5).map { ledger.onCreated() }

        assertEquals(listOf(1, 2, 3, 4, 5), generations)
        assertEquals(5, ledger.outstanding())

        // 现场形态：建 5 停 4 → 必须能报出残留的是哪个代号。
        generations.take(4).forEach { release(FakeDecoder(), ledger, it) }
        assertEquals(1, ledger.outstanding())
        assertEquals(listOf(5), ledger.liveGenerations())
    }

    @Test
    fun `同一 generation 释放两次必须被识别`() {
        val ledger = CodecLifecycleLedger()
        val generation = ledger.onCreated()

        assertTrue(release(FakeDecoder(), ledger, generation))
        assertFalse("重复释放必须返回 false（上层据此打 W）", release(FakeDecoder(), ledger, generation))
        assertEquals("重复释放不能把计数压成负数", 0, ledger.outstanding())
    }

    @Test
    fun `未知 generation 释放不算销账`() {
        val ledger = CodecLifecycleLedger()
        ledger.onCreated()

        assertFalse(release(FakeDecoder(), ledger, generation = 99))
        assertEquals("野代号不能影响未释放集合", 1, ledger.outstanding())
    }

    @Test
    fun `全部释放后未释放集合为空`() {
        val ledger = CodecLifecycleLedger()
        val generations = (1..3).map { ledger.onCreated() }
        generations.forEach { release(FakeDecoder(), ledger, it) }

        assertEquals(0, ledger.outstanding())
        assertTrue(ledger.liveGenerations().isEmpty())
    }
}
