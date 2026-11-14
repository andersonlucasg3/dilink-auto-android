package com.dilinkauto.vdserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 S2 采集编码管线的纯逻辑契约（issue #70 / Refs #13）。
 *
 * 被测的是 [AudioSpec] / [AudioPacing] / [ptsUsForFrame] / [silenceFrame] 这几个可以在
 * 纯 JVM 上跑的函数。`AudioPipeline` 本身的 `AudioRecord` + `MediaCodec` 部分依赖
 * Android 框架与真机 REMOTE_SUBMIX，只能在 S3/S6 的真机链路里验证 —— 那部分不属于
 * 本测试类的覆盖范围，不在注释里假装覆盖了。
 *
 * 节拍相关的断言直接对应 #13 的两条风险：
 * - R10：静音期必须补帧（不补就 88 星饿 / 706 ms 恢复）；
 * - R13：补帧节拍必须 21.333 ms / 46.875 fps，且落后时不能一次性灌爆队列。
 */
class AudioPipelineTest {

    private val second = 1_000_000_000L

    // ── AudioSpec ──

    @Test
    fun defaultSpecMatchesTheMeasuredParameters() {
        val spec = AudioSpec.DEFAULT
        assertEquals(48_000, spec.sampleRate)
        assertEquals(2, spec.channelCount)
        assertEquals(128_000, spec.bitrate)
        assertEquals(1024, spec.samplesPerFrame)
    }

    @Test
    fun derivedValuesFollowSamplesPerFrame() {
        val spec = AudioSpec.DEFAULT
        // 1024 samples * 2ch * 2B
        assertEquals(4096, spec.frameBytes)
        // 1024 / 48000 s = 21.333... ms
        assertEquals(21_333_333L, spec.frameDurationNanos)
        // 48000 / 1024 —— 这就是 46.875 fps 的来源
        assertEquals(46.875, spec.framesPerSecond, 1e-9)
    }

    @Test
    fun monoHalvesTheFrameBytes() {
        val mono = AudioSpec(channelCount = 1)
        assertEquals(2048, mono.frameBytes)
        assertEquals(21_333_333L, mono.frameDurationNanos)
    }

    @Test
    fun nonFortyEightKiloSampleRateIsNotTheTwentyOneMillisecondCadence() {
        // 44.1 kHz 下 1024 samples 是 23.220 ms —— 所以「1024 samples」不是普适的
        // 21.333 ms，节拍必须从 sampleRate 算，不能硬编码。
        val spec = AudioSpec(sampleRate = 44_100)
        assertEquals(23_219_954L, spec.frameDurationNanos)
    }

    @Test
    fun specRejectsInvalidParameters() {
        assertThrows<IllegalArgumentException> { AudioSpec(sampleRate = 0) }
        assertThrows<IllegalArgumentException> { AudioSpec(sampleRate = -48_000) }
        assertThrows<IllegalArgumentException> { AudioSpec(channelCount = 0) }
        assertThrows<IllegalArgumentException> { AudioSpec(channelCount = 3) }
        assertThrows<IllegalArgumentException> { AudioSpec(bitrate = 0) }
        assertThrows<IllegalArgumentException> { AudioSpec(bitrate = -1) }
        assertThrows<IllegalArgumentException> { AudioSpec(samplesPerFrame = 0) }
    }

    // ── AudioPacing ──

    @Test
    fun firstCallOnlyEstablishesTheBaseline() {
        val pacing = AudioPacing(frameDurationNanos = second)
        // 还没收到任何真实数据时不能抢先补帧。
        assertEquals(0, pacing.silenceFramesDue(0L))
        assertEquals(1, pacing.silenceFramesDue(second))
    }

    @Test
    fun nothingIsDueBeforeTheIntervalElapses() {
        val pacing = AudioPacing(frameDurationNanos = second)
        pacing.silenceFramesDue(0L)
        assertEquals(0, pacing.silenceFramesDue(second - 1))
        assertEquals(1, pacing.silenceFramesDue(second))
    }

    @Test
    fun realDataPushesTheDeadlineForward() {
        val pacing = AudioPacing(frameDurationNanos = second)
        pacing.onRealData(5L * second)
        assertEquals(0, pacing.silenceFramesDue(5L * second))
        assertEquals(1, pacing.silenceFramesDue(6L * second))
    }

    @Test
    fun continuousRealDataNeverTriggersSilence() {
        // 正常的 46.875 fps 采集：每一拍都有真实数据，补帧一次都不该发生。
        val spec = AudioSpec.DEFAULT
        val pacing = AudioPacing(spec.frameDurationNanos)
        var now = 0L
        var silence = 0
        repeat(1000) {
            now += spec.frameDurationNanos
            pacing.onRealData(now)
            silence += pacing.silenceFramesDue(now)
        }
        assertEquals(0, silence)
    }

    @Test
    fun catchUpIsCappedSoABlockedReadCannotFloodTheEncoder() {
        // 落后 10 拍（例如 read 阻塞了 213 ms）：只补封顶的 2 帧。
        val pacing = AudioPacing(frameDurationNanos = second, maxCatchUpFrames = 2)
        pacing.onRealData(0L)
        assertEquals(2, pacing.silenceFramesDue(10L * second))
        // 同一次落后不会被补第二遍：已经对齐到当前时刻之后的第一拍。
        assertEquals(0, pacing.silenceFramesDue(10L * second))
    }

    @Test
    fun catchingUpWithinTheCapEmitsExactlyTheMissedBeats() {
        val pacing = AudioPacing(frameDurationNanos = second, maxCatchUpFrames = 5)
        pacing.onRealData(0L)
        // 到期时刻是 +1s，现在 +3s ⇒ 3 拍到期，未超封顶。
        assertEquals(3, pacing.silenceFramesDue(3L * second))
    }

    @Test
    fun maxCatchUpFramesIsConfigurable() {
        val pacing = AudioPacing(frameDurationNanos = second, maxCatchUpFrames = 1)
        pacing.onRealData(0L)
        assertEquals(1, pacing.silenceFramesDue(7L * second))
    }

    @Test
    fun pacingRejectsInvalidConfiguration() {
        assertThrows<IllegalArgumentException> { AudioPacing(frameDurationNanos = 0L) }
        assertThrows<IllegalArgumentException> { AudioPacing(frameDurationNanos = -1L) }
        assertThrows<IllegalArgumentException> {
            AudioPacing(frameDurationNanos = second, maxCatchUpFrames = 0)
        }
    }

    // ── 静音帧 ──

    @Test
    fun silenceFrameIsAFullFrameOfZeros() {
        val spec = AudioSpec.DEFAULT
        val frame = silenceFrame(spec)
        assertEquals(spec.frameBytes, frame.size)
        assertArrayEquals(ByteArray(spec.frameBytes), frame)
    }

    @Test
    fun silenceFrameFollowsTheChannelCount() {
        assertEquals(2048, silenceFrame(AudioSpec(channelCount = 1)).size)
    }

    // ── PTS ──

    @Test
    fun ptsAdvancesByOneFrameDuration() {
        val spec = AudioSpec.DEFAULT
        assertEquals(0L, ptsUsForFrame(0L, spec))
        assertEquals(21_333L, ptsUsForFrame(1L, spec))
        // 46.875 fps ⇒ 46 帧约 981 ms
        assertEquals(981_333L, ptsUsForFrame(46L, spec))
    }

    @Test
    fun ptsUsesLongArithmeticSoLongSessionsDoNotOverflow() {
        val spec = AudioSpec.DEFAULT
        // 1000 万帧 ≈ 59.26 h —— 远超任何单次会话。Int 域长不了：µs 乘积在第 3 帧
        // （3072 samples × 1e6 = 3.07e9 > 2^31）就已经溢出；即便只看 sample 计数，
        // 2^31 samples @ 48 kHz 也只有约 12.4 h（44 739 s）。所以这里必须是 Long。
        val pts = ptsUsForFrame(10_000_000L, spec)
        assertEquals(213_333_333_333L, pts)
        assertTrue("must exceed Int.MAX_VALUE to prove Long math", pts > Int.MAX_VALUE.toLong())
    }

    // ── 编码器候选排序（复用视频侧同一策略） ──

    @Test
    fun audioCandidatesPreferHardwareThenCodec2ThenLegacy() {
        // 音频设备常常只有软编（c2.android / OMX.google），但排序策略与视频侧一致：
        // 非纯软实现优先，同组内 c2. 优先于 OMX.，同键保持框架顺序。
        val ordered = orderEncoderCandidates(
            listOf(
                "OMX.google.aac.encoder" to true,
                "c2.android.aac.encoder" to true,
                "OMX.qcom.audio.encoder.aac" to false
            )
        )
        assertEquals(
            listOf(
                "OMX.qcom.audio.encoder.aac",
                "c2.android.aac.encoder",
                "OMX.google.aac.encoder"
            ),
            ordered
        )
    }

    @Test
    fun audioCandidateOrderSurvivesByPreferenceForCodec2Hardware() {
        val ordered = orderEncoderCandidates(
            listOf(
                "OMX.qcom.audio.encoder.aac" to false,
                "c2.qti.aac.encoder" to false
            )
        )
        assertEquals(listOf("c2.qti.aac.encoder", "OMX.qcom.audio.encoder.aac"), ordered)
    }

    @Test
    fun softwareOnlyDevicesStillGetACandidateList() {
        // 纯软编设备不能让候选表变空 —— 逐个真尝试的循环至少要有一个能成功。
        val ordered = orderEncoderCandidates(
            listOf("OMX.google.aac.encoder" to true, "c2.android.aac.encoder" to true)
        )
        assertEquals(2, ordered.size)
        assertEquals("c2.android.aac.encoder", ordered.first())
    }

    // ── 异常摘要 ──

    @Test
    fun describeFallsBackToTheClassNameForEmptyMessages() {
        // MI 9 的 OMX.qcom.video.encoder.avc 在 configure 时抛空 message 的 CodecException，
        // 只打 message 会得到一行 `Audio: encoder 'x' unusable: ` —— 现场无法定位。
        assertEquals("boom", RuntimeException("boom").describe())
        assertEquals("RuntimeException", RuntimeException("").describe())
        assertEquals("RuntimeException", RuntimeException("   ").describe())
        assertEquals("IllegalStateException", IllegalStateException().describe())
    }

    @Test
    fun startFailureIsNullBeforeStartIsCalled() {
        // S3/S4 靠它决定手机侧能力位怎么回传；没启动过就不该有「失败原因」。
        val pipeline = AudioPipeline(sink = RecordingSink())
        assertNull(pipeline.startFailure)
        assertFalse(pipeline.start() && pipeline.frameCount > 0)
        // 纯 JVM 上 AudioRecord 必然不可用 ⇒ 必须静默降级返回 false，而不是抛异常。
        assertFalse(pipeline.start())
        assertTrue("must record why audio is unavailable", pipeline.startFailure != null)
        pipeline.close()
    }

    // ── issue #165：音频投喂闸门 ──

    @Test
    fun captureKeepsFeedingOnlyWhileAllThreeGatesAreHealthy() {
        assertTrue(shouldContinueCapture(sessionRunning = true, pipelineRunning = true, sinkFailed = false))
    }

    @Test
    fun captureStopsAsSoonAsTheSessionIsTearingDown() {
        // cleanup() 第一行就把 PipelineServer.running 置 false，不能等到 cleanup 末尾的
        // AudioPipeline.close()：中间是 5~10s 的 shell 恢复阶段，正是收尾期
        // CodecException 的窗口（issue #165）。
        assertFalse(shouldContinueCapture(sessionRunning = false, pipelineRunning = true, sinkFailed = false))
    }

    @Test
    fun captureStopsWhenTheAudioSinkIsAlreadyDead() {
        // `AudioSend: write failed, audio disabled` 之后必须停采集：接收端已经没了。
        assertFalse(shouldContinueCapture(sessionRunning = true, pipelineRunning = true, sinkFailed = true))
    }

    @Test
    fun captureStopsWhenCloseWasRequested() {
        assertFalse(shouldContinueCapture(sessionRunning = true, pipelineRunning = false, sinkFailed = false))
    }

    @Test
    fun sinkDefaultsToNotFailed() {
        // 内存/测试 sink 不实现 isFailed 时不能把采集线程误停。
        assertFalse(RecordingSink().isFailed())
    }

    /** 记录收到的 CONFIG / FRAME，用于纯 JVM 上的降级路径断言。 */
    private class RecordingSink : AudioPipeline.Sink {
        var configCount = 0
        var frameCount = 0

        override fun onConfig(config: com.dilinkauto.protocol.AudioPayload.Config) {
            configCount++
        }

        override fun onFrame(frame: com.dilinkauto.protocol.AudioPayload.Frame) {
            frameCount++
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        org.junit.Assert.fail("Expected ${T::class.java.simpleName} to be thrown")
    }
}
