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
            padIdle = 7, inZero = 0, inMin = 300, inMax = 420, outRmsMax = 812,
        )
        assertEquals(
            "[AudioPlayer] decoded=469 starved=3 underrun=2 maxRecoverMs=45 queue=9 silence=12 " +
                "padIdle=7 inZero=0 inMin=300 inMax=420 outRmsMax=812",
            line,
        )
    }

    @Test
    fun `stat line starts with the greppable prefix`() {
        val line = audioPlayerStatLine(0, 0, 0, 0, 0, 0)
        assertTrue(line.startsWith(contractPrefix))
        assertEquals(
            "[AudioPlayer] decoded=0 starved=0 underrun=0 maxRecoverMs=0 queue=0 silence=0 " +
                "padIdle=0 inZero=0 inMin=0 inMax=0 outRmsMax=0",
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
        // issue #194：三条口径必须同名可 grep —— 输入全零 / 输入字节范围 / 窗口最大 RMS
        assertTrue(line.contains(" inZero="))
        assertTrue(line.contains(" inMin="))
        assertTrue(line.contains(" inMax="))
        assertTrue(line.contains(" outRmsMax="))
    }

    @Test
    fun `silence default argument is zero`() {
        val line = audioPlayerStatLine(10, 0, 0, 0, 1)
        assertEquals(
            "[AudioPlayer] decoded=10 starved=0 underrun=0 maxRecoverMs=0 queue=1 silence=0 " +
                "padIdle=0 inZero=0 inMin=0 inMax=0 outRmsMax=0",
            line,
        )
    }

    @Test
    fun `padIdle default argument is zero`() {
        // 老调用点（不传 padIdle / 诊断字段）仍要产出与原契约等价的尾部，新字段全为 0
        val line = audioPlayerStatLine(1, 2, 3, 4, 5, 6)
        assertTrue(line.endsWith(" silence=6 padIdle=0 inZero=0 inMin=0 inMax=0 outRmsMax=0"))
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

    // ── issue #194：接收侧诊断口径 ──

    @Test
    fun `pcm16Rms decodes little endian signed samples`() {
        // [0x00, 0x01] 小端 = 256；按大端会算成 1 —— 这条直接钉住字节序。
        assertEquals(256, pcm16Rms(byteArrayOf(0x00, 0x01)))
        // [0x01, 0x00] 小端 = 1。
        assertEquals(1, pcm16Rms(byteArrayOf(0x01, 0x00)))
        // -1 = 0xFFFF，平方后 RMS 为 1。
        assertEquals(1, pcm16Rms(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        // 满幅正/负样本：平方后取 RMS，负号不影响幅度。
        assertEquals(32767, pcm16Rms(byteArrayOf(0xFF.toByte(), 0x7F)))
        assertEquals(32768, pcm16Rms(byteArrayOf(0x00, 0x80.toByte())))
    }

    @Test
    fun `pcm16Rms is zero for silence and ignores a trailing odd byte`() {
        assertEquals(0, pcm16Rms(ByteArray(0)))
        assertEquals(0, pcm16Rms(ByteArray(1)))
        assertEquals(0, pcm16Rms(ByteArray(4096)))
        // 最后一个字节不成样本，必须被忽略而不是当 0 拉低均值。
        assertEquals(256, pcm16Rms(byteArrayOf(0x00, 0x01, 0x7F)))
    }

    @Test
    fun `rx diag counts input zeros and output zeros separately`() {
        val diag = AudioRxDiag()
        val silencePayload = ByteArray(320)
        val realPayload = ByteArray(320).also { it[0] = 1 }

        diag.onInput(silencePayload)
        diag.onInput(realPayload)
        diag.onInput(realPayload)
        // 输入里有 1 帧全零，但解码输出是真实 PCM：outZero 不许跟着涨。
        diag.onOutput(realPayload)

        assertEquals(3L, diag.inFrames)
        assertEquals(1L, diag.inZero)
        assertEquals(1L, diag.outFrames)
        assertEquals(0L, diag.outZero)

        // 反过来：全零输出只涨 outZero，不动 inZero（两个口径永不混用）。
        diag.onOutput(ByteArray(320))
        assertEquals(1L, diag.inZero)
        assertEquals(1L, diag.outZero)
        assertEquals(2L, diag.outFrames)
    }

    @Test
    fun `rx diag tracks payload byte range and resets the rms window`() {
        val diag = AudioRxDiag()
        diag.onInput(ByteArray(300))
        diag.onInput(ByteArray(420))
        assertEquals(300, diag.inMinBytes)
        assertEquals(420, diag.inMaxBytes)

        // 小端 256 → 本窗口最大 RMS 256。
        diag.onOutput(byteArrayOf(0x00, 0x01))
        assertEquals(256, diag.windowRmsMax)
        // 取走即复位：不取走会让下一次 2.5s 行继续报旧值。
        assertEquals(256, diag.consumeWindowRmsMax())
        assertEquals(0, diag.windowRmsMax)

        diag.onOutput(ByteArray(2))
        assertEquals(0, diag.consumeWindowRmsMax())
        // 消费窗口值不影响累计字段。
        assertEquals(2L, diag.inFrames)
        assertEquals(300, diag.inMinBytes)
        assertEquals(2L, diag.outFrames)
    }

    @Test
    fun `rx diag reset clears every session counter`() {
        val diag = AudioRxDiag()
        diag.onInput(ByteArray(300))
        diag.onInput(ByteArray(420))
        diag.onOutput(byteArrayOf(0x00, 0x01))
        diag.reset()

        assertEquals(0L, diag.inFrames)
        assertEquals(0L, diag.inZero)
        assertEquals(0, diag.inMinBytes)
        assertEquals(0, diag.inMaxBytes)
        assertEquals(0L, diag.outFrames)
        assertEquals(0L, diag.outZero)
        assertEquals(0, diag.windowRmsMax)
    }

    @Test
    fun `rx diag line is separately greppable`() {
        val line = audioRxDiagLine(
            inFrames = 2554, inZero = 0, inMin = 300, inMax = 420,
            outZero = 2538, outRmsMax = 812,
        )
        assertEquals(
            "AudioDiag rx inFrames=2554 inZero=0 inMin=300 inMax=420 outZero=2538 outRmsMax=812",
            line,
        )
        assertTrue(line.startsWith("AudioDiag rx "))
    }

    // ── issue #206：AudioTrack 缓冲换算 ──

    @Test
    fun `audioTrack buffer stats convert frames to duration and bytes`() {
        // 实车 client.log:108 的实际分配：12288 帧 @48k/2ch = 256ms / 49152B。
        // 旧代码把 1024（AAC 每个 PCM 块的样本数）再乘一次，报成 262144ms / 50331648B。
        val stats = audioTrackBufferStats(frames = 12288, sampleRate = 48000, channelCount = 2)
        assertEquals(12288, stats.frames)
        assertEquals(256L, stats.durationMs)
        assertEquals(49152L, stats.bytes)
    }

    @Test
    fun `audioTrack buffer stats apply channel count but not the 1024-sample block`() {
        // 单声道同帧数：时长不变，字节数减半（再乘 1024 会得到 262144ms / 50331648B）
        val stats = audioTrackBufferStats(frames = 12288, sampleRate = 48000, channelCount = 1)
        assertEquals(256L, stats.durationMs)
        assertEquals(24576L, stats.bytes)
    }

    @Test
    fun `audioTrack buffer line is the frozen log contract`() {
        assertEquals(
            "AudioTrack buffer: 12288 frames ≈ 256ms " +
                "(49152B; requested=49152B/12288 frames, min=8224)",
            audioTrackBufferLine(
                frames = 12288, sampleRate = 48000, channelCount = 2,
                requestedBytes = 49152, minBytes = 8224,
            ),
        )
    }

    @Test
    fun `requested frames use the AudioTrack frame unit not PCM blocks`() {
        // 实车请求 12 个 PCM 块 = 49152B，换算成 AudioTrack 帧（2ch × 2B）就是 12288 帧，
        // 与 `bufferSizeInFrames` 同口径。旧实现打 12（49152/4096），是 1024 口径的指纹。
        val stereo = audioTrackBufferLine(
            frames = 12288, sampleRate = 48000, channelCount = 2,
            requestedBytes = 49152, minBytes = 8224,
        )
        assertTrue(stereo.contains("(49152B; requested=49152B/12288 frames"))

        // 单声道：49152B = 24576 帧（按 PCM 块算会得到 24，差 1024 倍）。
        val mono = audioTrackBufferLine(
            frames = 24576, sampleRate = 48000, channelCount = 1,
            requestedBytes = 49152, minBytes = 4112,
        )
        assertTrue(mono.contains("(49152B; requested=49152B/24576 frames"))
    }
}
