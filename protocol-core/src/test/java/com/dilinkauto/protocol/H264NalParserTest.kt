package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H264NalParser 单测：IDR 判定是解码路径"跳过 P 帧等 IDR"的判据，错判会导致黑屏或卡帧。
 */
class H264NalParserTest {

    /** 拼一个 Annex B 格式的 NAL：[startCode] 3 或 4 字节 + header(type) + 若干字节负载。 */
    private fun nal(type: Int, startCodeLen: Int = 4, payloadSize: Int = 8): ByteArray {
        val startCode = ByteArray(startCodeLen) // 全 0，最后一个字节填 1
        startCode[startCodeLen - 1] = 1
        val header = byteArrayOf(type.toByte()) // forbidden_zero=0, nal_ref_idc=0, nal_unit_type=type
        return startCode + header + ByteArray(payloadSize) { 0x42 }
    }

    @Test
    fun `4 字节起始码的 IDR 判定为关键帧`() {
        assertTrue(H264NalParser.isKeyFrame(nal(type = 5, startCodeLen = 4)))
    }

    @Test
    fun `3 字节起始码的 IDR 判定为关键帧`() {
        assertTrue(H264NalParser.isKeyFrame(nal(type = 5, startCodeLen = 3)))
    }

    @Test
    fun `4 字节裸 IDR（3 字节起始码且无后续负载）正确判定为关键帧`() {
        val bareIdr = byteArrayOf(0, 0, 1, 0x65)
        assertTrue(H264NalParser.isKeyFrame(bareIdr))
    }

    @Test
    fun `非 IDR 的 slice（P 帧）不判定为关键帧`() {
        assertFalse(H264NalParser.isKeyFrame(nal(type = 1)))
    }

    @Test
    fun `SPS 与 PPS 不判定为关键帧`() {
        assertFalse(H264NalParser.isKeyFrame(nal(type = 7)))
        assertFalse(H264NalParser.isKeyFrame(nal(type = 8)))
    }

    @Test
    fun `多个 NAL 里只要有一个 IDR 就判定为关键帧`() {
        // 模拟一个访问单元：SEI(type=6) + IDR(type=5) 拼接
        val accessUnit = nal(type = 6) + nal(type = 5) + nal(type = 1)
        assertTrue(H264NalParser.isKeyFrame(accessUnit))
    }

    @Test
    fun `空数组与过短数组安全返回 false`() {
        assertFalse(H264NalParser.isKeyFrame(ByteArray(0)))
        assertFalse(H264NalParser.isKeyFrame(byteArrayOf(0, 0, 1)))
    }

    @Test
    fun `扫描窗口外的 IDR 不判定（只扫前 1KB 的性能约定）`() {
        // 1KB 普通数据 + 尾部紧跟 IDR：落在扫描窗口之外，约定为漏判（上层仍有下一个 IDR 兜底）
        val data = ByteArray(1500) { 0x11 } + nal(type = 5)
        assertFalse(H264NalParser.isKeyFrame(data))
    }

    @Test
    fun `全零数据不会误判`() {
        assertFalse(H264NalParser.isKeyFrame(ByteArray(256)))
    }
}