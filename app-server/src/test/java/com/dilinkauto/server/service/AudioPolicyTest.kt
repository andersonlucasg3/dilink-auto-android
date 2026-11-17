package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机端音频镜像纯策略单测（issue #72, Refs #13）。
 *
 * 为什么只测这几个函数：`app-server` 没有 Robolectric，且
 * `build.gradle.kts:96` 把 `unitTests.isReturnDefaultValues` 设为 true，
 * `getSharedPreferences()` 在纯 JVM 上返回 null —— `CarPrefs` 的读写本身无法单测。
 * 于是把可判定的逻辑（位拼装、置灰映射）全部挤进 [AudioPolicy.kt] 的 top-level
 * 纯函数，在这里钉住；`CarPrefs` 持久化走真机回签。
 *
 * 期望值一律写字面量（1 / 3 / 5 ...），不用被测函数反推 —— 否则断言与被测
 * 逻辑同错，测不出东西。
 */
class AudioPolicyTest {

    // FEATURE_VIDEO = 0x01, FEATURE_AUDIO = 0x02（ProtocolConstants.kt:31-32）

    @Test
    fun `car request advertises video only when audio switch is off`() {
        assertEquals(0x01, carRequestFeatures(audioEnabled = false))
    }

    @Test
    fun `car request advertises video plus audio when audio switch is on`() {
        assertEquals(0x01 or 0x02, carRequestFeatures(audioEnabled = true))
    }

    @Test
    fun `car request always advertises video in both switch states`() {
        assertTrue(carRequestFeatures(audioEnabled = true) and 0x01 != 0)
        assertTrue(carRequestFeatures(audioEnabled = false) and 0x01 != 0)
    }

    @Test
    fun `phone support is true only when the audio bit is set`() {
        assertTrue(phoneSupportsAudio(0x02))
        assertTrue(phoneSupportsAudio(0x01 or 0x02))
        // 其他位（视频/媒体控制/导航）单独出现都不算音频支持
        assertFalse(phoneSupportsAudio(0x01))
        assertFalse(phoneSupportsAudio(0x00))
        assertFalse(phoneSupportsAudio(0x08 or 0x10))
    }

    @Test
    fun `phone support ignores unrelated high bits`() {
        // 只有低 2 位是 S1 定义的视频/音频；高位即便全置，也不得误判音频
        assertFalse(phoneSupportsAudio(0x7FFFFFFC))
        assertTrue(phoneSupportsAudio(0x7FFFFFFE))
    }

    // ─── 置灰映射：audioSwitchState ───

    @Test
    fun `switch is clickable and follows the persisted value when the phone supports audio`() {
        val on = audioSwitchState(phoneSupported = true, audioEnabled = true)
        assertEquals(true, on.clickable)
        assertEquals(true, on.checked)
        assertEquals(AudioSwitchBlockReason.NONE, on.blockReason)

        val off = audioSwitchState(phoneSupported = true, audioEnabled = false)
        assertEquals(true, off.clickable)
        assertEquals(false, off.checked)
        assertEquals(AudioSwitchBlockReason.NONE, off.blockReason)
    }

    @Test
    fun `switch is greyed out with a reason when the phone does not support audio`() {
        val state = audioSwitchState(phoneSupported = false, audioEnabled = true)
        assertEquals(false, state.clickable)
        // 置灰时必须归 false：否则灰掉的开关显示「开」，与「不可用」自相矛盾
        assertEquals(false, state.checked)
        assertEquals(AudioSwitchBlockReason.PHONE_UNSUPPORTED, state.blockReason)
    }

    @Test
    fun `unsupported phone greys out even when the persisted switch is off`() {
        val state = audioSwitchState(phoneSupported = false, audioEnabled = false)
        assertEquals(false, state.clickable)
        assertEquals(false, state.checked)
        assertEquals(AudioSwitchBlockReason.PHONE_UNSUPPORTED, state.blockReason)
    }

    // ─── 连接门：carShouldConnectAudio（issue #73 / S3-PB）───
    //
    // 参数是 Boolean 而非原始能力位：位解释只发生在 S4 的 `phoneSupportsAudio`
    // 一处（其位语义已由本文件上半段单测钉住），连接门不再持有第二份能力位状态。

    @Test
    fun `connect gate needs both the switch and phone support`() {
        assertTrue(carShouldConnectAudio(audioEnabled = true, phoneSupported = true))
        // 开关关 → 不连，即便手机支持
        assertFalse(carShouldConnectAudio(audioEnabled = false, phoneSupported = true))
        // 手机不支持 → 不连，即便开关开（老手机响应恒为 0 → phoneSupport=false）
        assertFalse(carShouldConnectAudio(audioEnabled = true, phoneSupported = false))
        assertFalse(carShouldConnectAudio(audioEnabled = false, phoneSupported = false))
    }

    @Test
    fun `connect gate composes with phoneSupportsAudio over the raw feature bits`() {
        // 端到端：原始能力位 → phoneSupportsAudio → 连接门。0x02 = FEATURE_AUDIO。
        assertTrue(carShouldConnectAudio(true, phoneSupportsAudio(0x02)))
        assertTrue(carShouldConnectAudio(true, phoneSupportsAudio(0x01 or 0x02)))
        // 只有视频位 / 无位 → 不连
        assertFalse(carShouldConnectAudio(true, phoneSupportsAudio(0x01)))
        assertFalse(carShouldConnectAudio(true, phoneSupportsAudio(0x00)))
        // 高位全置但无音频位 → 不连；加上音频位 → 连
        assertFalse(carShouldConnectAudio(true, phoneSupportsAudio(0x7FFFFFFC)))
        assertTrue(carShouldConnectAudio(true, phoneSupportsAudio(0x7FFFFFFE)))
    }
}
