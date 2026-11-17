package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEncoderSelectorTest {

    @Test
    fun filterAacEncodersRetainsOnlyAacEncoders() {
        val candidates = listOf(
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.android.aac.decoder",
                isEncoder = false,
                isSoftwareOnly = true,
                supportedTypes = listOf("audio/mp4a-latm")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.android.avc.encoder",
                isEncoder = true,
                isSoftwareOnly = true,
                supportedTypes = listOf("video/avc")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.android.opus.encoder",
                isEncoder = true,
                isSoftwareOnly = true,
                supportedTypes = listOf("audio/opus")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.android.aac.encoder",
                isEncoder = true,
                isSoftwareOnly = true,
                supportedTypes = listOf("audio/mp4a-latm")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.qti.aac.encoder",
                isEncoder = true,
                isSoftwareOnly = false,
                supportedTypes = listOf("audio/mp4a-latm")
            )
        )

        val result = AudioEncoderSelector.filterAacEncoders(candidates)
        assertEquals(
            listOf(
                "c2.android.aac.encoder" to true,
                "c2.qti.aac.encoder" to false
            ),
            result
        )
    }

    @Test
    fun filterAacEncodersMatchesTypeCaseInsensitively() {
        val candidates = listOf(
            AudioEncoderSelector.CodecDescriptor(
                name = "OMX.custom.aac.encoder",
                isEncoder = true,
                isSoftwareOnly = false,
                supportedTypes = listOf("AUDIO/MP4A-LATM")
            )
        )
        val result = AudioEncoderSelector.filterAacEncoders(candidates)
        assertEquals(listOf("OMX.custom.aac.encoder" to false), result)
    }

    @Test
    fun candidateOrderingPrefersHardwareAndCodec2() {
        val candidates = listOf(
            AudioEncoderSelector.CodecDescriptor(
                name = "OMX.google.aac.encoder",
                isEncoder = true,
                isSoftwareOnly = true,
                supportedTypes = listOf("audio/mp4a-latm")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.android.aac.encoder",
                isEncoder = true,
                isSoftwareOnly = true,
                supportedTypes = listOf("audio/mp4a-latm")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "OMX.qcom.audio.encoder.aac",
                isEncoder = true,
                isSoftwareOnly = false,
                supportedTypes = listOf("audio/mp4a-latm")
            ),
            AudioEncoderSelector.CodecDescriptor(
                name = "c2.qti.audio.encoder.aac",
                isEncoder = true,
                isSoftwareOnly = false,
                supportedTypes = listOf("audio/mp4a-latm")
            )
        )

        val filtered = AudioEncoderSelector.filterAacEncoders(candidates)
        val ordered = orderEncoderCandidates(filtered)

        // Hardware (isSoftwareOnly=false) first, within hardware c2. beats OMX.
        // Software (isSoftwareOnly=true) later, within software c2. beats OMX.
        assertEquals(
            listOf(
                "c2.qti.audio.encoder.aac",
                "OMX.qcom.audio.encoder.aac",
                "c2.android.aac.encoder",
                "OMX.google.aac.encoder"
            ),
            ordered
        )
    }

    @Test
    fun emptyCandidatesYieldEmptyResult() {
        val result = AudioEncoderSelector.filterAacEncoders(emptyList())
        assertTrue(result.isEmpty())
        assertTrue(orderEncoderCandidates(result).isEmpty())
    }
}
