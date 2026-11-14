package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「本拍该补多少帧静音」的判定（issue #164）。
 *
 * 为什么要有这张表：实车 `underrun` 每秒 22.6 次、而应用层 `starved` 只有 25（差 445 倍），
 * 根因是「有输入但解码器本拍没输出」这一拍一个字节都不写（`AudioPlayer.kt` 主循环旧的
 * `frame == null` 分支）。判定抽成纯函数后，这里用表把三种语义钉死：
 * 1. 有输入、本拍无输出 → **必须补**；
 * 2. 水位已到低水位 / 缓冲已满 → **不补**（防无限堆积，延迟必须有上界）；
 * 3. 本拍已有真实 PCM → **不补**（别用静音掩盖解码器供不上的问题）。
 *
 * 框架类（AudioTrack）在无 Robolectric 的 JVM 单测里全是默认值，所以被测的是纯函数，
 * `getPlaybackHeadPosition()` 的读取留在 `AudioPlayer` 里、不进断言。
 */
class AudioPlayerPadPolicyTest {

    private data class PadCase(
        val name: String,
        val bufferFrames: Int,
        val pendingFrames: Int,
        val wroteFramesThisTick: Int,
        val expected: Int,
        val lowWater: Int = AUDIO_TRACK_LOW_WATER_FRAMES,
    )

    private val cases = listOf(
        // ── 1. 有输入、本拍无输出 → 必须补到低水位 ──
        PadCase("空输出且 track 已见底 → 补满低水位", 12, 0, 0, 3),
        PadCase("空输出且水位 1 → 补 2 帧", 12, 1, 0, 2),
        PadCase("空输出且水位 2 → 补 1 帧", 12, 2, 0, 1),

        // ── 2. 水位够 / 缓冲满 → 不补（防堆积）──
        PadCase("水位恰为低水位 → 不补", 12, 3, 0, 0),
        PadCase("水位高于低水位 → 不补", 12, 4, 0, 0),
        PadCase("缓冲已满 → 不补", 12, 12, 0, 0),
        PadCase("缓冲只剩 1 帧空位（水位已高于低水位）→ 不补", 12, 11, 0, 0),
        PadCase("pending 超出容量（ROM 实际缓冲更大）→ 不补", 12, 20, 0, 0),
        PadCase("pending 为负（播放头算错）→ 按 0 补满低水位", 12, -2, 0, 3),

        // ── 3. 本拍已有真实 PCM → 不补 ──
        PadCase("本拍写了 1 帧 → 不补", 12, 0, 1, 0),
        PadCase("本拍写了 2 帧 → 不补", 12, 0, 2, 0),

        // ── 边界 ──
        PadCase("缓冲比低水位还小 → 只补到容量", 2, 0, 0, 2),
        PadCase("容量 0 → 不补", 0, 0, 0, 0),
        PadCase("低水位 0（禁用）→ 不补", 12, 0, 0, 0, lowWater = 0),
        PadCase("自定义低水位 6 → 补 6", 12, 0, 0, 6, lowWater = 6),
    )

    @Test
    fun `pad decision table`() {
        for (c in cases) {
            val actual = audioPadSilenceFrames(
                bufferFrames = c.bufferFrames,
                pendingFrames = c.pendingFrames,
                wroteFramesThisTick = c.wroteFramesThisTick,
                lowWaterFrames = c.lowWater,
            )
            assertEquals("case: ${c.name}", c.expected, actual)
        }
    }

    @Test
    fun `pad never exceeds free space in the track`() {
        // 任意组合下补帧数都不得让水位超过容量（失败即 = 无界堆积）
        for (buffer in 1..20) {
            for (pending in 0..buffer) {
                for (wrote in 0..2) {
                    val pad = audioPadSilenceFrames(buffer, pending, wrote)
                    assertTrue(
                        "buffer=$buffer pending=$pending wrote=$wrote pad=$pad",
                        pad >= 0 && pad + pending <= buffer,
                    )
                }
            }
        }
    }

    @Test
    fun `pad is monotonically non-increasing as the track fills up`() {
        var last = Int.MAX_VALUE
        for (pending in 0..12) {
            val pad = audioPadSilenceFrames(bufferFrames = 12, pendingFrames = pending, wroteFramesThisTick = 0)
            assertTrue("pending=$pending pad=$pad last=$last", pad <= last)
            last = pad
        }
    }

    @Test
    fun `low water is three frames which is about 64ms of 48kHz audio`() {
        // 3 × 21.333 ms ≈ 64 ms：够覆盖连续 2 拍 INFO_TRY_AGAIN_LATER（42.7ms）+ parkNanos 抖动
        assertEquals(3, AUDIO_TRACK_LOW_WATER_FRAMES)
        val ms = AUDIO_TRACK_LOW_WATER_FRAMES * 1024.0 / 48_000.0 * 1000.0
        assertTrue("low water = $ms ms", ms in 60.0..68.0)
    }

    // ── pending（已写入未播）的计算 ──

    private data class PendingCase(
        val name: String,
        val written: Long,
        val head: Long,
        val bufferFrames: Int,
        val expected: Int,
    )

    @Test
    fun `pending frames table`() {
        val cases = listOf(
            PendingCase("播放头持平 → 0", 100, 100, 12, 0),
            PendingCase("落后 5 帧 → 5", 100, 95, 12, 5),
            PendingCase("落后 1 帧 → 1", 100, 99, 12, 1),
            PendingCase("播放头超前（flush/重建）→ 钳到 0", 100, 120, 12, 0),
            PendingCase("差值超过容量 → 钳到容量", 1000, 0, 12, 12),
            PendingCase("容量 0 → 0", 100, 90, 0, 0),
        )
        for (c in cases) {
            assertEquals(
                "case: ${c.name}",
                c.expected,
                audioTrackPendingFrames(c.written, c.head, c.bufferFrames),
            )
        }
    }
}
