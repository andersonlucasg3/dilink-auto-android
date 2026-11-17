package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H264NalParser.isKeyFrame —— nal_ref_idc 位还原测试。
 *
 * 现有 H264NalParserTest 都用 nal_ref_idc=0 的裸 type（5）。真实编码器发 IDR 时带头
 * 0x65（ref_idc=3），靠 `and 0x1F` 掩码才认得出。这里锁定：若有人把判定"简化"成 ==5，
 * 所有真实关键帧都会漏判（两端黑屏），本测试必须失败。
 */
class H264NalParserRefIdcTest {

    /** 4 字节起始码 + 单个 NAL 头字节。 */
    private fun oneNal(header: Int) = byteArrayOf(0, 0, 0, 1, header.toByte())

    @Test
    fun `accepts real idr headers with non zero ref idc`() {
        // 都被 0x1F 掩码后得到 type=5
        assertTrue(H264NalParser.isKeyFrame(oneNal(0x65))) // ref_idc=3
        assertTrue(H264NalParser.isKeyFrame(oneNal(0x45))) // ref_idc=2
        assertTrue(H264NalParser.isKeyFrame(oneNal(0x25))) // ref_idc=1
        assertTrue(H264NalParser.isKeyFrame(oneNal(0x05))) // ref_idc=0
    }

    @Test
    fun `ignores non idr types with high ref idc`() {
        assertFalse(H264NalParser.isKeyFrame(oneNal(0x41))) // 非 IDR slice，ref_idc=2
        assertFalse(H264NalParser.isKeyFrame(oneNal(0x67))) // SPS
        assertFalse(H264NalParser.isKeyFrame(oneNal(0x68))) // PPS
    }
}
