package com.dilinkauto.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature

/**
 * AdbCrypto 纯算法部分单测（与 AOSP 对齐、两种传输共用的认证握手基元）。
 *
 * 只覆盖不与 android.util 交互的部分。encodePublicKey 收尾那步
 * android.util.Base64 在 mockable jar 下返回 null，故对 base64 主体不做断言，
 * 只锁 NUL 结尾约定（见 buildAuthReplySecondTokenIsPublicKey）。
 */
class AdbCryptoTest {

    companion object {
        private lateinit var keyPair: KeyPair

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // 真实 2048-bit RSA（纯 JVM SunRsaSign）；整类生成一次。
            keyPair = AdbCrypto.generateKeyPair()
        }
    }

    @Test
    fun `sha1 digest info prefix matches aosp`() {
        val expected = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        assertEquals(15, AdbCrypto.SHA1_DIGEST_INFO.size)
        assertArrayEquals(expected, AdbCrypto.SHA1_DIGEST_INFO)
    }

    @Test
    fun `big int to LE padded reverses and zero pads`() {
        val out = AdbCrypto.bigIntToLEPadded(BigInteger.valueOf(0x01020304L), 8)
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01, 0, 0, 0, 0), out)
    }

    @Test
    fun `big int to LE padded strips leading sign byte`() {
        // 2^2047：最高位置 1，toByteArray() 为 257 字节且首字节为 0x00 符号位。
        val value = BigInteger.ONE.shiftLeft(2047)
        val out = AdbCrypto.bigIntToLEPadded(value, 256)
        assertEquals(256, out.size)
        for (i in 0 until 255) assertEquals(0.toByte(), out[i]) // 低位全 0
        assertEquals(0x80.toByte(), out[255])                  // 符号位已被剥掉，最高位落在这里
    }

    @Test
    fun `big int to LE padded truncates gracefully`() {
        // 0x0102030405 超出 size=4：小端表示只保留低 4 字节 02 03 04 05 → [05 04 03 02]
        val out = AdbCrypto.bigIntToLEPadded(BigInteger.valueOf(0x0102030405L), 4)
        assertArrayEquals(byteArrayOf(0x05, 0x04, 0x03, 0x02), out)
    }

    @Test
    fun `big int to LE padded zero`() {
        assertArrayEquals(ByteArray(256), AdbCrypto.bigIntToLEPadded(BigInteger.ZERO, 256))
    }

    @Test
    fun `fingerprint is four lowercase hex of sha1`() {
        // SHA-1("hello") = aaf4c61d... → 前 4 字节小写 hex
        assertEquals("aaf4c61d", AdbCrypto.fingerprint("hello".toByteArray()))
        // SHA-1("") = da39a3ee...
        assertEquals("da39a3ee", AdbCrypto.fingerprint(ByteArray(0)))
    }

    @Test
    fun `build auth reply returns null for non token type`() {
        // 只在 AUTH_TOKEN 时回复；别的 auth 类型一律 null（锁住纯认证状态机）
        val token = ByteArray(20) { it.toByte() }
        assertNull(AdbCrypto.buildAuthReply(AdbProtocol.AUTH_SIGNATURE, token, keyPair, false))
    }

    @Test
    fun `build auth reply first token is signature`() {
        val token = ByteArray(20) { (it + 1).toByte() }
        val reply = AdbCrypto.buildAuthReply(AdbProtocol.AUTH_TOKEN, token, keyPair, false)!!
        assertEquals(AdbProtocol.AUTH_SIGNATURE, reply.authType)
        assertNotNull(reply.payload)
        assertEquals(256, reply.payload.size) // RSA-2048 签名 = 256 字节

        // 可验证性：AdbCrypto 在 digestInfo=SHA1_DIGEST_INFO+token 上做 NONEwithRSA
        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(keyPair.public)
        verifier.update(AdbCrypto.SHA1_DIGEST_INFO + token)
        assertTrue("签名应能用公钥 + 相同 DigestInfo 验证通过", verifier.verify(reply.payload))
    }

    @Test
    fun `build auth reply second token is public key`() {
        // 第二次 token → 返回公钥（NUL 结尾）。encodePublicKey 现用 java.util.Base64（见 KDoc），
        // 结构由 encodePublicKeyRoundTripsTheAndroidPubkeyStruct 细验；这里锁 authType 与 NUL 结尾契约。
        val token = ByteArray(20) { it.toByte() }
        val reply = AdbCrypto.buildAuthReply(AdbProtocol.AUTH_TOKEN, token, keyPair, true)!!
        assertEquals(AdbProtocol.AUTH_RSAPUBLICKEY, reply.authType)
        val text = String(reply.payload)
        assertTrue("公钥串必须以 NUL 结尾（ADB 协议要求）", text.endsWith(" DiLinkAuto@car\u0000"))
        assertTrue("不应是旧的尾空格形态", !text.endsWith(" DiLinkAuto@car "))
    }

    @Test
    fun `encode public key round trips the android pubkey struct`() {
        // 切到 java.util.Base64 后，可在 JVM 上完整验证 ANDROID_PUBKEY 结构（此前 android.util.Base64 返回 null 无法验证）。
        val rsa = keyPair.public as java.security.interfaces.RSAPublicKey
        val bytes = AdbCrypto.encodePublicKey(keyPair.public)
        val text = String(bytes)
        assertTrue("必须以 ' DiLinkAuto@car\\0' 结尾（NUL 结尾契约）", text.endsWith(" DiLinkAuto@car\u0000"))

        val b64 = text.substring(0, text.indexOf(' '))          // base64 本体不含空格
        assertTrue("标准 base64 带 padding（与 Android NO_WRAP 字节一致）", b64.endsWith("="))
        val struct = java.util.Base64.getDecoder().decode(b64)
        assertEquals("4+4+256+256+4", 524, struct.size)

        val bb = java.nio.ByteBuffer.wrap(struct).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.getInt())            // modulus words = 256/4
        bb.getInt()                              // n0inv（Montgomery 参数，此处跳过）
        val modulusLE = ByteArray(256); bb.get(modulusLE)
        val rrLE = ByteArray(256); bb.get(rrLE)
        val exponent = bb.getInt()

        val modulus = java.math.BigInteger(1, modulusLE.reversedArray()) // 小端反转成大端
        assertEquals(rsa.modulus, modulus)
        assertEquals(rsa.publicExponent.toLong(), exponent.toLong())
        assertTrue("rr 段应是 Montgomery 值（非全零）", rrLE.any { it != 0.toByte() })
    }

    @Test
    fun `sign auth token wrong length token still signs`() {
        // 不强制 token 必须是 20 字节（记录当前语义）：短 token 仍产出 256 字节签名。
        val sig = AdbCrypto.signAuthToken(keyPair.private, ByteArray(5) { 7 })
        assertEquals(256, sig.size)
    }
}
