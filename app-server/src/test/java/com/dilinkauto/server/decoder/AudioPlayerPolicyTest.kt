package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住车机端音频播放的日志契约与纯算法（issue #73 分节 PB、issue #84 / Refs #79）。
 *
 * 为什么只测这一件事：`AudioPlayer` 的其余部分全是 `MediaCodec` / `AudioTrack` I/O，
 * 在无 Robolectric 的纯 JVM 单测里跑不起来（框架类返回默认值）。而**日志行格式是
 * S6 场景断言的硬契约**（`scripts/lib/asserts.sh` 的 `countF` 按固定串 `grep -F`），
 * 一旦漂移就会让 S6 永远「未命中」—— 所以把它抽成纯函数在这里用字面量钉住。
 *
 * 期望串逐字写出，不由被测函数反推。
 */
class AudioPlayerPolicyTest {

    /** 契约里 S6 会 `grep -cF` 的固定前缀。 */
    private val contractPrefix = "[AudioPlayer] decoded="

    @Test
    fun `stat line matches the frozen S6 grep contract`() {
        val line = audioPlayerStatLine(
            decoded = 469, starved = 3, underrun = 2, maxRecoverMs = 45, queue = 9, silence = 12,
            padIdle = 7,
        )
        assertEquals(
            "[AudioPlayer] decoded=469 starved=3 underrun=2 maxRecoverMs=45 queue=9 silence=12 padIdle=7",
            line,
        )
    }

    @Test
    fun `stat line starts with the greppable prefix`() {
        val line = audioPlayerStatLine(0, 0, 0, 0, 0, 0)
        assertTrue(line.startsWith(contractPrefix))
        assertEquals(
            "[AudioPlayer] decoded=0 starved=0 underrun=0 maxRecoverMs=0 queue=0 silence=0 padIdle=0",
            line,
        )
    }

    @Test
    fun `field names are stable and space separated`() {
        val line = audioPlayerStatLine(1, 2, 3, 4, 5, 6, 7)
        // 逐字段核对：字段名一旦改名，S6 的 assert 会静默失配
        assertTrue(line.contains(" decoded="))
        assertTrue(line.contains(" starved="))
        assertTrue(line.contains(" underrun="))
        assertTrue(line.contains(" maxRecoverMs="))
        assertTrue(line.contains(" queue="))
        assertTrue(line.contains(" silence="))
        // issue #164 新增的归因字段，必须钉住：没有它下次还是分不清 starved / 空输出拍
        assertTrue(line.contains(" padIdle="))
    }

    @Test
    fun `silence default argument is zero`() {
        val line = audioPlayerStatLine(10, 0, 0, 0, 1)
        assertEquals(
            "[AudioPlayer] decoded=10 starved=0 underrun=0 maxRecoverMs=0 queue=1 silence=0 padIdle=0",
            line,
        )
    }

    @Test
    fun `padIdle default argument is zero`() {
        // 老调用点（不传 padIdle）仍要产出与原契约等价的尾部，只多一个 padIdle=0
        val line = audioPlayerStatLine(1, 2, 3, 4, 5, 6)
        assertTrue(line.endsWith(" silence=6 padIdle=0"))
    }

    @Test
    fun `isPcmSilent correctly identifies all zeros buffer`() {
        assertTrue(isPcmSilent(ByteArray(0)))
        assertTrue(isPcmSilent(ByteArray(1024)))
        assertTrue(isPcmSilent(ByteArray(4096)))
    }

    @Test
    fun `isPcmSilent detects non zero samples at various positions`() {
        val buf = ByteArray(1024)
        buf[0] = 1
        assertFalse(isPcmSilent(buf))

        val bufMid = ByteArray(1024)
        bufMid[512] = -1
        assertFalse(isPcmSilent(bufMid))

        val bufEnd = ByteArray(1024)
        bufEnd[1023] = 0x80.toByte()
        assertFalse(isPcmSilent(bufEnd))
    }
}
