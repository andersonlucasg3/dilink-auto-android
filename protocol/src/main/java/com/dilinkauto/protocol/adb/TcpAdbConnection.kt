package com.dilinkauto.protocol.adb

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Persistent ADB connection over TCP that reuses a single socket for all commands.
 * Unlike Dadb (which opens a new connection per command), this keeps one socket open
 * and sends all shell commands through it.
 *
 * Shares ADB protocol constants and encoding with AdbProtocol/UsbAdbConnection.
 */
class TcpAdbConnection(
    private val host: String,
    private val port: Int,
    private val keyDir: File
) : Closeable {
    private var socket: Socket? = null
    private var maxPayload = AdbProtocol.MAX_PAYLOAD
    private var authSignatureSent = false

    val isConnected: Boolean get() = socket?.isConnected == true && !socket!!.isClosed

    fun connect(): Boolean {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.tcpNoDelay = true; sock.keepAlive = true
            sock.soTimeout = 30000
            socket = sock
            val keyPair = getOrCreateKeyPair()
            // Send CNXN
            sock.getOutputStream().write(AdbProtocol.encodeConnect())
            // Handle AUTH + CNXN response
            while (true) {
                val msg = readMessage()
                when (msg.command) {
                    AdbProtocol.A_CNXN -> {
                        val data = msg.data
                        if (data != null && data.size >= 8) {
                            val peerMax = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
                            maxPayload = minOf(peerMax, AdbProtocol.MAX_PAYLOAD)
                        }
                        if (maxPayload < 1) maxPayload = AdbProtocol.MAX_PAYLOAD
                        return true
                    }
                    AdbProtocol.A_AUTH -> {
                        handleAuth(msg.arg0, msg.data ?: ByteArray(0), keyPair)
                    }
                    else -> {
                        android.util.Log.w("TcpAdb", "Unexpected cmd: ${msg.command}")
                        close(); return false
                    }
                }
            }
        } catch (e: Exception) {
            close()
            return false
        }
    }

    /** Execute a shell command synchronously. Returns exit code (0 = success, -1 = error). */
    fun shell(command: String): Int {
        val sock = socket ?: return -1
        val localId = nextLocalId
        return try {
            synchronized(sock) {
                // OPEN shell stream
                val openBytes = "shell:$command".toByteArray()
                sock.getOutputStream().write(
                    AdbProtocol.encode(AdbProtocol.A_OPEN, localId, 0, openBytes))
                // Read response: OKAY opens stream, then WRTE for data, CLSE when done
                var exitCode = -1
                val output = StringBuilder()
                while (true) {
                    val msg = readMessage()
                    when (msg.command) {
                        AdbProtocol.A_OKAY -> { /* stream opened, continue reading */ }
                        AdbProtocol.A_WRTE -> {
                            msg.data?.let { output.append(String(it)) }
                        }
                        AdbProtocol.A_CLSE -> {
                            // CLSE payload may contain exit code
                            exitCode = 0
                            break
                        }
                        else -> break
                    }
                }
                exitCode
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Start a long-running shell command. Keeps the stream open so the process survives.
     *  Returns the local stream ID if successful, -1 on failure. */
    fun shellBackground(command: String): Int {
        val sock = socket ?: return -1
        val localId = nextLocalId
        return try {
            synchronized(sock) {
                val openBytes = "shell:$command".toByteArray()
                sock.getOutputStream().write(
                    AdbProtocol.encode(AdbProtocol.A_OPEN, localId, 0, openBytes))
                val msg = readMessage()
                if (msg.command == AdbProtocol.A_OKAY) {
                    localId
                } else {
                    -1
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Fire-and-forget shell command. Returns true if OPEN+OKAY succeeded. */
    fun shellNoWait(command: String): Boolean {
        return shellBackground(command) >= 0
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // ── Message I/O ──

    data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray?)

    private fun readMessage(): AdbMessage {
        val header = ByteArray(24)
        readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val dataLen = buf.getInt()
        // skip checksum and magic
        val data = if (dataLen > 0) {
            val d = ByteArray(dataLen)
            readFully(d)
            d
        } else null
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        val inp = socket?.getInputStream() ?: throw IllegalStateException("Not connected")
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException("ADB connection closed")
            off += n
        }
    }

    private var localIdCounter = 1
    private val nextLocalId: Int get() = localIdCounter++

    // ── AUTH (same logic as UsbAdbConnection) ──

    private fun handleAuth(type: Int, data: ByteArray, keyPair: KeyPair) {
        if (type == AdbProtocol.AUTH_TOKEN) {
            if (!authSignatureSent) {
                // Sign token with stored key (SHA-1 DigestInfo + NONEwithRSA)
                val sha1DigestInfo = byteArrayOf(
                    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
                )
                val toSign = sha1DigestInfo + data
                val sig = Signature.getInstance("NONEwithRSA").apply {
                    initSign(keyPair.private)
                    update(toSign)
                }
                writeRaw(AdbProtocol.encodeAuth(AdbProtocol.AUTH_SIGNATURE, sig.sign()))
                authSignatureSent = true
            } else {
                // Signature rejected — send public key for user approval
                val pubKey = encodePublicKey(keyPair.public)
                writeRaw(AdbProtocol.encodeAuth(AdbProtocol.AUTH_RSAPUBLICKEY, pubKey))
            }
        }
    }

    private fun writeRaw(data: ByteArray) {
        socket?.getOutputStream()?.write(data)
    }

    // ── Key management (shared logic with UsbAdbConnection) ──

    companion object {
        // Use same key file name as Dadb and UsbAdbConnection for compatibility
        private const val KEY_FILE = "adbkey"

        /** SHA-1 fingerprint of a DER-encoded public key */
        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(der)
                .joinToString("") { "%02x".format(it) }

        // ANDROID_PUBKEY constants matching UsbAdbConnection
        private const val ANDROID_PUBKEY_MODULUS_SIZE = 256 // 2048 bits / 8
        private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4

        /** Encode RSA public key in Android ADB format (ANDROID_PUBKEY).
         *  Reference: AOSP libcrypto_utils/android_pubkey.cpp */
        fun encodePublicKey(publicKey: PublicKey): ByteArray {
            val rsaKey = publicKey as java.security.interfaces.RSAPublicKey
            val n = rsaKey.modulus
            val e = rsaKey.publicExponent
            val r32 = java.math.BigInteger.ONE.shiftLeft(32)

            // n0inv = -1/n[0] mod 2^32 (Montgomery reduction parameter)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()

            // rr = (2^2048)^2 mod n = 2^4096 mod n
            val rr = java.math.BigInteger.ONE
                .shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 8)
                .modPow(java.math.BigInteger.valueOf(2), n)

            val modulusLE = bigIntToLEPadded(n)
            val rrLE = bigIntToLEPadded(rr)

            val struct = ByteBuffer.allocate(4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE + ANDROID_PUBKEY_MODULUS_SIZE + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            struct.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
            struct.putInt(n0inv)
            struct.put(modulusLE)
            struct.put(rrLE)
            struct.putInt(e.toInt())

            val base64 = android.util.Base64.encodeToString(struct.array(), android.util.Base64.NO_WRAP)
            return "$base64 DiLinkAuto@car ".toByteArray()
        }

        private fun bigIntToLEPadded(bi: java.math.BigInteger): ByteArray {
            val bytes = ByteArray(ANDROID_PUBKEY_MODULUS_SIZE)
            val raw = bi.toByteArray() // big-endian, may have leading zero
            // Skip leading zero byte if present (sign byte for unsigned big integers)
            val src = if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            for (i in src.indices) bytes[i] = src[src.size - 1 - i] // reverse to little-endian
            return bytes
        }
    }

    private fun getOrCreateKeyPair(): KeyPair {
        keyDir.mkdirs()
        val privFile = File(keyDir, KEY_FILE)
        val pubFile = File(keyDir, KEY_FILE + ".pub")

        if (privFile.exists() && pubFile.exists()) {
            return try {
                val kf = KeyFactory.getInstance("RSA")
                val pubKey = kf.generatePublic(X509EncodedKeySpec(readKeyBytes(pubFile)))
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(readKeyBytes(privFile)))
                KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                generateAndStore(privFile, pubFile)
            }
        }
        return generateAndStore(privFile, pubFile)
    }

    /** Read key bytes, handling both PEM and DER formats */
    private fun readKeyBytes(file: File): ByteArray {
        val text = file.readText()
        return if (text.startsWith("-----BEGIN")) {
            // PEM format (used by Dadb)
            val b64 = text.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } else {
            file.readBytes()
        }
    }

    private fun generateAndStore(privFile: File, pubFile: File): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        // Save in DER format (compatible with both PEM-aware and direct readers)
        // Also save in PEM for Dadb compatibility
        saveKey(privFile, kp.private)
        saveKey(pubFile, kp.public)
        return kp
    }

    private fun saveKey(file: File, key: java.security.Key) {
        val encoded = key.encoded
        val b64 = android.util.Base64.encodeToString(encoded, android.util.Base64.DEFAULT)
        val pem = "-----BEGIN ${if (key is java.security.PrivateKey) "RSA PRIVATE" else "PUBLIC"} KEY-----\n" +
                b64 +
                "-----END ${if (key is java.security.PrivateKey) "RSA PRIVATE" else "PUBLIC"} KEY-----\n"
        file.writeText(pem)
    }
}
