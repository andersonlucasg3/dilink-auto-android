package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the encoder-candidate ordering introduced for the 2026-10-10 MI 9 defect.
 *
 * On that device (Android 15 port ROM) `MediaCodec.createEncoderByType("video/avc")`
 * blindly picked `OMX.qcom.video.encoder.avc`, the legacy component listed first in
 * media_codecs.xml, which fails configure with an *empty-message* CodecException
 * (logcat: `venc_dev: Unsupported eColorFormat 0x7f000789`, i.e. no COLOR_FormatSurface
 * support) — killing the whole capture pipeline. `c2.qti.avc.encoder` (hardware
 * Codec2) works on the same device.
 *
 * The fix enumerates all candidates and tries each one for real
 * (create → configure → createInputSurface → start); [orderEncoderCandidates] only
 * decides *who gets tried first* — these tests pin that order.
 */
class EncoderCandidateOrderTest {

    @Test
    fun hardwareEncodersComeBeforeSoftwareOnes() {
        val ordered = orderEncoderCandidates(
            listOf(
                "c2.android.avc.encoder" to true,
                "c2.qti.avc.encoder" to false,
            ),
        )
        assertEquals(listOf("c2.qti.avc.encoder", "c2.android.avc.encoder"), ordered)
    }

    @Test
    fun codec2BeatsLegacyOmxWithinTheSameGroup() {
        // The exact MI 9 arrangement: the broken OMX component is listed first.
        val ordered = orderEncoderCandidates(
            listOf(
                "OMX.qcom.video.encoder.avc" to false,
                "c2.qti.avc.encoder" to false,
            ),
        )
        assertEquals(listOf("c2.qti.avc.encoder", "OMX.qcom.video.encoder.avc"), ordered)
    }

    @Test
    fun equalKeysKeepTheFrameworkOrderStable() {
        val ordered = orderEncoderCandidates(
            listOf(
                "c2.aaa.encoder" to false,
                "c2.bbb.encoder" to false,
                "OMX.zzz" to true,
            ),
        )
        assertEquals(
            listOf("c2.aaa.encoder", "c2.bbb.encoder", "OMX.zzz"),
            ordered,
        )
    }

    @Test
    fun mI9RealWorldListPutsTheWorkingHardwareEncoderFirst() {
        // The actual list `MediaCodecList(ALL_CODECS)` returned on the MI 9
        // (in framework order). The first entry must be the only one that
        // demonstrably configures there (verified by an on-device probe).
        val ordered = orderEncoderCandidates(
            listOf(
                "OMX.qcom.video.encoder.avc" to false,
                "c2.qti.avc.encoder" to false,
                "c2.android.avc.encoder" to true,
                "OMX.google.h264.encoder" to true,
            ),
        )
        assertEquals(
            listOf(
                "c2.qti.avc.encoder",
                "OMX.qcom.video.encoder.avc",
                "c2.android.avc.encoder",
                "OMX.google.h264.encoder",
            ),
            ordered,
        )
    }

    @Test
    fun pureSoftwareDeviceStillYieldsACandidateList() {
        // Devices with only software encoders must not end up with an empty order —
        // the try-each loop needs at least the software fallback to succeed.
        val ordered = orderEncoderCandidates(
            listOf("OMX.google.h264.encoder" to true, "c2.android.avc.encoder" to true),
        )
        assertTrue("software fallback must survive ordering", ordered.size == 2)
        assertEquals("c2.android.avc.encoder", ordered.first())
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals(emptyList<String>(), orderEncoderCandidates(emptyList()))
    }
}
