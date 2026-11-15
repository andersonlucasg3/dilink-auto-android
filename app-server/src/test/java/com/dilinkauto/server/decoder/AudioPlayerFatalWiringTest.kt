package com.dilinkauto.server.decoder

import com.dilinkauto.protocol.AudioPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「CONFIG 硬超时 → 显式上报」这条接线（issue #181 评审意见 2）。
 *
 * `AudioConfigWaitTest` 只测纯时序机，删掉 `AudioPlayer.onFatal?.invoke` 它仍然全绿；
 * 这里直接驱动 `AudioPlayer.awaitFirstConfig`（时钟注入，不碰 `AudioTrack`/`MediaCodec`），
 * 断言 [AudioPlayer.onFatal] 被调用、`playbackFailed` 置位、E 日志关键字可 grep。
 * 删除任一接线都会让本文件红。
 */
class AudioPlayerFatalWiringTest {

    /** 假时钟：每 park 一次推进 1 ms。 */
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
    fun `TimedOut reaches onFatal and marks playback failed`() {
        val player = AudioPlayer()
        val logs = mutableListOf<String>()
        val fatal = mutableListOf<String>()
        player.logSink = { logs += it }
        player.onFatal = { fatal += it }
        val clock = FakeClock()

        val result = player.awaitFirstConfig(
            configSource = { null },
            runningSource = { true },
            clockMs = clock::clock,
            parkNanos = clock::park,
        )

        assertNull(result)
        // 删除 reportFatal 里的 onFatal?.invoke → 这里红
        assertEquals(
            listOf("CONFIG fatal: No CONFIG within 10000ms — audio unavailable for this session"),
            fatal,
        )
        // 删除 reportFatal 里的 playbackFailed = true → 这里红（喂帧闸门会继续放行）
        assertTrue(player.isPlaybackFailed)
        // 会话层接的是 sendUnconditional，整行必须带可 grep 的固定前缀
        assertTrue(
            logs.any { it == "[AudioPlayer][E] CONFIG fatal: No CONFIG within 10000ms — audio unavailable for this session" },
        )
    }

    @Test
    fun `Ready CONFIG does not report fatal`() {
        val player = AudioPlayer()
        val fatal = mutableListOf<String>()
        player.onFatal = { fatal += it }
        val clock = FakeClock()

        val result = player.awaitFirstConfig(
            configSource = { config() },
            runningSource = { true },
            clockMs = clock::clock,
            parkNanos = clock::park,
        )

        assertEquals(48_000, result?.sampleRate)
        assertTrue(fatal.isEmpty())
        assertFalse(player.isPlaybackFailed)
    }

    @Test
    fun `stop before CONFIG is not a fatal`() {
        val player = AudioPlayer()
        val fatal = mutableListOf<String>()
        player.onFatal = { fatal += it }
        val clock = FakeClock()

        val result = player.awaitFirstConfig(
            configSource = { null },
            runningSource = { false },
            clockMs = clock::clock,
            parkNanos = clock::park,
        )

        assertNull(result)
        assertTrue("stop 不是故障，不该触发 onFatal", fatal.isEmpty())
        assertFalse(player.isPlaybackFailed)
    }

    @Test
    fun `start after fatal rebuilds and clears failed state`() {
        val player = AudioPlayer()
        player.logSink = { }
        val clock = FakeClock()
        // 先真的进入运行态；再注入时钟走一遍 CONFIG 硬超时（生产路径）。
        player.start()
        try {
            player.awaitFirstConfig(
                configSource = { null },
                runningSource = { player.isRunning },
                clockMs = clock::clock,
                parkNanos = clock::park,
            )
            assertTrue(player.isPlaybackFailed)
            // 致命退出必须同时放掉 running，否则同一连接再次 start() 会被
            // `start() called but already running` 早退，playbackFailed 永不复位。
            assertFalse("致命后 running 仍为 true，下一次 start() 会早退", player.isRunning)

            player.start()
            assertTrue(player.isRunning)
            assertFalse(player.isPlaybackFailed)
        } finally {
            player.stop()
        }
    }

    @Test
    fun `playback failed closes the frame gate without touching drop policy`() {
        // running / playbackFailed / hasConfig 三个闸门的表
        assertTrue(audioShouldEnqueueFrame(running = true, playbackFailed = false, hasConfig = true))
        assertFalse(audioShouldEnqueueFrame(running = false, playbackFailed = false, hasConfig = true))
        assertFalse(audioShouldEnqueueFrame(running = true, playbackFailed = true, hasConfig = true))
        assertFalse(audioShouldEnqueueFrame(running = true, playbackFailed = false, hasConfig = false))
        assertFalse(audioShouldEnqueueFrame(running = true, playbackFailed = true, hasConfig = false))
    }
}
