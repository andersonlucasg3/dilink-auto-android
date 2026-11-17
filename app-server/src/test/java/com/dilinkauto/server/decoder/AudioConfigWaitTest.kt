package com.dilinkauto.server.decoder

import com.dilinkauto.protocol.AudioPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「等第一份 CONFIG」的时序（issue #181）。
 *
 * 现场：车机在音频 TCP 连上时就开始 3 s 计时（`CarSessionCoordinator.connectAudioIfEnabled`），
 * 而手机端 9640 的 accept 被刻意放在视频管线 unpark 之后（`PipelineServer.run`），
 * CONFIG 只能在 accept + 采集 + 首帧编码输出之后才发。于是 3 s 预算实际覆盖了
 * VD/GL bring-up，CONFIG 迟到 109 ms 就让播放线程永久退出、整段会话静音。
 *
 * 这里用注入的时钟/睡眠模拟时序，不起 `AudioTrack` / `MediaCodec`：
 * 1. CONFIG 在 3.1 s 到达 → 仍要起播，且只在 3 s 处告警一次；
 * 2. CONFIG 永不到达 → 不能静默退出，必须在软阈值告警、在硬阈值显式上报一次。
 */
class AudioConfigWaitTest {

    /** 假时钟：每 park 一次推进 1 ms，便于把 3.1 s / 10 s 的时序压进毫秒级用例。 */
    private class FakeClock {
        var now = 0L
        fun clock(): Long = now
        fun park(@Suppress("UNUSED_PARAMETER") nanos: Long) {
            now += 1
        }
    }

    private fun config() = AudioPayload.Config(
        sampleRate = 48_000,
        channelCount = 2,
        bitrate = 128_000,
        csd = byteArrayOf(0x12, 0x10),
    )

    @Test
    fun `CONFIG arriving at 3_1s still starts playback`() {
        val clock = FakeClock()
        var warnedAt = -1L
        var timedOut = false

        val result = AudioConfigWaiter(
            config = { if (clock.now >= 3_100L) config() else null },
            running = { true },
            clockMs = clock::clock,
            parkNanos = clock::park,
            onStillWaiting = { warnedAt = it },
            onTimeout = { timedOut = true },
        ).await()

        assertTrue("应返回 CONFIG 并起播，实际=$result", result is AudioConfigWaitResult.Ready)
        assertEquals(48_000, (result as AudioConfigWaitResult.Ready).config.sampleRate)
        // 3 s 处必须留一条可观测的告警，而不是悄悄继续等
        assertEquals(AUDIO_CONFIG_SOFT_TIMEOUT_MS, warnedAt)
        assertFalse("CONFIG 已到达，不得上报超时", timedOut)
    }

    @Test
    fun `CONFIG never arriving reports at hard timeout instead of exiting silently`() {
        val clock = FakeClock()
        val warnings = mutableListOf<Long>()
        val timeouts = mutableListOf<Long>()

        val result = AudioConfigWaiter(
            config = { null },
            running = { true },
            clockMs = clock::clock,
            parkNanos = clock::park,
            onStillWaiting = { warnings += it },
            onTimeout = { timeouts += it },
        ).await()

        // 软阈值只告警一次（别每 2 ms 刷一行）
        assertEquals(listOf(AUDIO_CONFIG_SOFT_TIMEOUT_MS), warnings)
        // 硬阈值必须显式上报，且只报一次
        assertEquals(listOf(AUDIO_CONFIG_HARD_TIMEOUT_MS), timeouts)
        assertTrue("应返回 TimedOut，实际=$result", result is AudioConfigWaitResult.TimedOut)
    }

    @Test
    fun `stop request aborts without reporting a timeout`() {
        val clock = FakeClock()
        var warned = false
        var timedOut = false

        val result = AudioConfigWaiter(
            config = { null },
            running = { clock.now < 500L },
            clockMs = clock::clock,
            parkNanos = clock::park,
            onStillWaiting = { warned = true },
            onTimeout = { timedOut = true },
        ).await()

        assertEquals(AudioConfigWaitResult.Stopped, result)
        assertFalse(warned)
        assertFalse("stop 不是故障，不该上报超时", timedOut)
    }

    @Test
    fun `hard timeout is same order as video first frame budget`() {
        // issue #181 候选 A：放宽到与视频首帧同量级（8~10s），不是无限等
        assertTrue(
            "hard=${AUDIO_CONFIG_HARD_TIMEOUT_MS}ms",
            AUDIO_CONFIG_HARD_TIMEOUT_MS in 8_000L..10_000L,
        )
        assertTrue(AUDIO_CONFIG_HARD_TIMEOUT_MS > AUDIO_CONFIG_SOFT_TIMEOUT_MS)
    }
}
