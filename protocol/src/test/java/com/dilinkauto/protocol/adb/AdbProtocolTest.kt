package com.dilinkauto.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AdbProtocol 帧层单测。
 *
 * 这是两种传输（TcpAdbConnection / UsbAdbConnection）共用的报文头打包/解析器：
 * 校验和与 magic 校验一旦回归，会悄悄放过"WiFi 推 JAR 损坏"这类问题。
 * 全部纯 JVM，无 Android 依赖。
 */
class AdbProtocolTest {

    // ---- 校验和：无符号按字节累加（不是 CRC32） ----

    @Test
    fun `checksum empty and small payloads`() {
        assertEquals(0, AdbProtocol.checksum(ByteArray(0)))
        assertEquals(1, AdbProtocol.checksum(byteArrayOf(0x01)))
        // 两个 0xFF：若误用有符号 byte 累加会得到 -2；无符号应为 510
        assertEquals(510, AdbProtocol.checksum(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `checksum adb reference vector`() {
        // 0x01..0x14 之和 = 210，锁定累加算法不会漂移成 CRC32
        val data = ByteArray(20) { (it + 1).toByte() }
        assertEquals(210, AdbProtocol.checksum(data))
        // 与内容顺序无关，纯累加
        assertEquals(0x42 * 64, AdbProtocol.checksum(ByteArray(64) { 0x42.toByte() }))
    }

    @Test
    fun `constants match adb ascii values`() {
        // "CNXN" "AUTH" "OPEN" "OKAY" "CLSE" "WRTE"（小端 4 字节 ASCII）
        assertEquals(0x4e584e43, AdbProtocol.A_CNXN)
        assertEquals(0x48545541, AdbProtocol.A_AUTH)
        assertEquals(0x4e45504f, AdbProtocol.A_OPEN)
        assertEquals(0x59414b4f, AdbProtocol.A_OKAY)
        assertEquals(0x45534c43, AdbProtocol.A_CLSE)
        assertEquals(0x45545257, AdbProtocol.A_WRTE)
        assertEquals(24, AdbProtocol.HEADER_SIZE)
        assertEquals(262144, AdbProtocol.MAX_PAYLOAD)
        assertEquals(0x01000000, AdbProtocol.A_VERSION)
    }

    // ---- encode 布局：小端 + magic + crc ----

    @Test
    fun `encode layout is little endian with magic and crc`() {
        val data = byteArrayOf(0x01, 0x02)
        val out = AdbProtocol.encode(AdbProtocol.A_WRTE, 7, 9, data)
        assertEquals(24 + data.size, out.size)

        // command 字段是小端 ASCII "WRTE"
        assertArrayEquals("WRTE".toByteArray(), out.copyOfRange(0, 4))

        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_WRTE, buf.getInt())
        assertEquals(7, buf.getInt())
        assertEquals(9, buf.getInt())
        assertEquals(2, buf.getInt())
        assertEquals(3, buf.getInt())                       // data_crc = 1 + 2
        assertEquals(AdbProtocol.A_WRTE xor -1, buf.getInt()) // magic = command ^ 0xFFFFFFFF
        assertArrayEquals(data, out.copyOfRange(24, out.size))
    }

    @Test
    fun `encode null data writes zero crc and header only`() {
        val out = AdbProtocol.encode(AdbProtocol.A_OKAY, 1, 2, null)
        assertEquals(24, out.size)
        val parsed = AdbProtocol.parseHeader(out)!!
        assertEquals(0, parsed[3]) // data_len
        assertEquals(0, parsed[4]) // data_crc
    }

    @Test
    fun `encode does not validate upper bound on encode side`() {
        // encode 侧不做 MAX_PAYLOAD 上限校验：超限只在 parse 侧拦。
        val big = ByteArray(300 * 1024)
        val out = AdbProtocol.encode(AdbProtocol.A_WRTE, 1, 2, big)
        assertEquals(24 + big.size, out.size)
        // 但同一段字节再 parseHeader 时被 MAX_PAYLOAD 拒绝（> 256KB）
        assertNull(AdbProtocol.parseHeader(out))
    }

    @Test
    fun `encodeConnect uses host banner and version and max payload`() {
        val parsed = AdbProtocol.parseHeader(AdbProtocol.encodeConnect())!!
        assertEquals(AdbProtocol.A_CNXN, parsed[0])
        assertEquals(AdbProtocol.A_VERSION, parsed[1])
        assertEquals(AdbProtocol.MAX_PAYLOAD, parsed[2])
        assertEquals(7, parsed[3])                 // "host::\u0000"
        assertEquals(AdbProtocol.checksum("host::\u0000".toByteArray()), parsed[4])
    }

    @Test
    fun `encodeAuth puts type in arg0`() {
        val token = ByteArray(20) { it.toByte() }
        val parsed = AdbProtocol.parseHeader(AdbProtocol.encodeAuth(AdbProtocol.AUTH_SIGNATURE, token))!!
        assertEquals(AdbProtocol.A_AUTH, parsed[0])
        assertEquals(AdbProtocol.AUTH_SIGNATURE, parsed[1]) // type goes in arg0
        assertEquals(0, parsed[2])
        assertEquals(20, parsed[3])
    }

    @Test
    fun `encodeOpen appends nul`() {
        val raw = AdbProtocol.encodeOpen(11, "shell:echo hi")
        val body = raw.copyOfRange(24, raw.size)
        assertEquals("shell:echo hi\u0000", String(body))
    }

    // ---- parseHeader：magic / data_len 边界 ----

    @Test
    fun `parseHeader accepts a well formed header`() {
        val header = AdbProtocol.encode(AdbProtocol.A_CLSE, 3, 4, null)
        val parsed = AdbProtocol.parseHeader(header)!!
        assertArrayEquals(intArrayOf(AdbProtocol.A_CLSE, 3, 4, 0, 0), parsed)
    }

    @Test
    fun `parseHeader rejects bad magic`() {
        val header = AdbProtocol.encode(AdbProtocol.A_OPEN, 1, 2, null)
        header[20] = (header[20].toInt() xor 0xFF).toByte() // corrupt magic high byte
        assertNull(AdbProtocol.parseHeader(header))
    }

    @Test
    fun `parseHeader rejects short buffer`() {
        assertNull(AdbProtocol.parseHeader(ByteArray(0)))
        assertNull(AdbProtocol.parseHeader(ByteArray(23)))
    }

    @Test
    fun `parseHeader rejects oversized and negative data len`() {
        // 正确 magic，只把 data_len 设为 0x7FFFFFFF（OOM 向量，KDoc 里记过）
        assertNull(headerWithDataLen(0x7FFFFFFF))
        assertNull(headerWithDataLen(-1))

        val header = headerWithDataLen(AdbProtocol.MAX_PAYLOAD)!!
        assertEquals(AdbProtocol.MAX_PAYLOAD, header[3])
        assertNull(headerWithDataLen(AdbProtocol.MAX_PAYLOAD + 1))
    }

    @Test
    fun `parseHeader extra bytes beyond header are ignored`() {
        val header = AdbProtocol.encode(AdbProtocol.A_WRTE, 5, 6, byteArrayOf(1, 2, 3, 4))
        val padded = header + ByteArray(8) // 尾部多余字节
        val parsed = AdbProtocol.parseHeader(padded)!!
        assertEquals(4, parsed[3]) // data_len 来自头，不因尾字节变化
    }

    // ---- round-trip：每种 encode → parseHeader 语义不变 ----

    @Test
    fun `round trip encode then parse header`() {
        val payload = "shell:pm list packages".toByteArray() + 0x00.toByte()

        val wrti = AdbProtocol.encode(AdbProtocol.A_WRTE, 5, 6, payload)
        assertArrayEquals(
            intArrayOf(AdbProtocol.A_WRTE, 5, 6, payload.size, AdbProtocol.checksum(payload)),
            AdbProtocol.parseHeader(wrti)
        )

        val opn = AdbProtocol.encodeOpen(42, "shell:ls")
        assertArrayEquals(
            intArrayOf(
                AdbProtocol.A_OPEN, 42, 0, "shell:ls\u0000".length,
                AdbProtocol.checksum("shell:ls\u0000".toByteArray())
            ),
            AdbProtocol.parseHeader(opn)
        )

        val clse = AdbProtocol.encodeClose(1, 2)
        assertArrayEquals(intArrayOf(AdbProtocol.A_CLSE, 1, 2, 0, 0), AdbProtocol.parseHeader(clse))

        val okay = AdbProtocol.encodeOkay(1, 2)
        assertArrayEquals(intArrayOf(AdbProtocol.A_OKAY, 1, 2, 0, 0), AdbProtocol.parseHeader(okay))
    }

    // ---- helpers ----

    /** 造一个 magic 正确、仅 data_len 可变的 24 字节头。 */
    private fun headerWithDataLen(dataLen: Int): IntArray? {
        val b = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(AdbProtocol.A_WRTE)
        b.putInt(0)
        b.putInt(0)
        b.putInt(dataLen)
        b.putInt(0)
        b.putInt(AdbProtocol.A_WRTE xor -1)
        assertTrue(b.array().size == AdbProtocol.HEADER_SIZE)
        return AdbProtocol.parseHeader(b.array())
    }
}
