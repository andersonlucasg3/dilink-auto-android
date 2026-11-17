package com.dilinkauto.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair

class UsbAdbKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writeFile and readFile preserve byte contents`() {
        val file = tempFolder.newFile("test_key")
        val sample = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x55, 0xaa.toByte())
        UsbAdbKeyStore.writeFile(file, sample)

        val readBack = UsbAdbKeyStore.readFile(file)
        assertArrayEquals(sample, readBack)
    }

    @Test
    fun `copyFile copies contents accurately to destination`() {
        val src = tempFolder.newFile("src_file")
        val dst = File(tempFolder.root, "dst_file")
        val payload = "Hello ADB Key Store".toByteArray(Charsets.UTF_8)
        UsbAdbKeyStore.writeFile(src, payload)

        UsbAdbKeyStore.copyFile(src, dst)
        assertTrue(dst.exists())
        assertArrayEquals(payload, UsbAdbKeyStore.readFile(dst))
    }

    @Test
    fun `key result contains key pair and diag info`() {
        val kp = AdbCrypto.generateKeyPair()
        val result = UsbAdbKeyStore.KeyResult(kp, "LOADED path=/test fp=1234")
        assertNotNull(result.keyPair)
        assertEquals(kp, result.keyPair)
        assertEquals("LOADED path=/test fp=1234", result.diagInfo)
    }

    @Test
    fun `key store file operations work with RSA key specs`() {
        val dir = tempFolder.newFolder("keys")
        val privFile = File(dir, UsbAdbKeyStore.KEY_FILE)
        val pubFile = File(dir, UsbAdbKeyStore.KEY_FILE + ".pub")

        val generated = AdbCrypto.generateKeyPair()
        UsbAdbKeyStore.writeFile(privFile, generated.private.encoded)
        UsbAdbKeyStore.writeFile(pubFile, generated.public.encoded)

        val loaded = AdbCrypto.loadKeyPair(
            UsbAdbKeyStore.readFile(privFile),
            UsbAdbKeyStore.readFile(pubFile)
        )
        assertNotNull(loaded)
        assertArrayEquals(generated.private.encoded, loaded.private.encoded)
        assertArrayEquals(generated.public.encoded, loaded.public.encoded)
    }
}
