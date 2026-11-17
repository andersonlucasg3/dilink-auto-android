package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机侧能力位回传判据（issue #72, Refs #13）。
 *
 * 期望值写字面量，不用被测函数反推。`FEATURE_VIDEO = 0x01`、`FEATURE_AUDIO = 0x02`
 * （`ProtocolConstants.kt:31-32`）；Android 11 = SDK 30。
 */
class PhoneAudioPolicyTest {

    @Test
    fun `android 10 and below reports video only`() {
        assertEquals(0x01, phoneAudioFeatures(29))
        assertEquals(0x01, phoneAudioFeatures(28))
        assertEquals(0x01, phoneAudioFeatures(1))
    }

    @Test
    fun `android 11 and above reports video plus audio`() {
        assertEquals(0x01 or 0x02, phoneAudioFeatures(30))
        assertEquals(0x01 or 0x02, phoneAudioFeatures(31))
        assertEquals(0x01 or 0x02, phoneAudioFeatures(35))
    }

    @Test
    fun `video bit is always present on both sides of the boundary`() {
        assertTrue(phoneAudioFeatures(29) and 0x01 != 0)
        assertTrue(phoneAudioFeatures(30) and 0x01 != 0)
    }

    @Test
    fun `boundary is exactly sdk 30`() {
        // 29 无音频位、30 有音频位：判据是 >= 30，不是 > 30 也不是 >= 29
        assertEquals(0x01, phoneAudioFeatures(29))
        assertEquals(0x01 or 0x02, phoneAudioFeatures(30))
    }
}
