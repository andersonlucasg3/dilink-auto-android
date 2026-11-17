package com.dilinkauto.client.service

import dadb.AdbKeyPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Self-healing key-pair policy of [AdbKeyUtil] (audit A-L24).
 *
 * The old guard regenerated only when the *private* half was missing. A
 * process killed between the two writes of `generate()` (or a half cleared by
 * a cleaner) left `adbkey` without `adbkey.pub`; `AdbKeyPair.read` then either
 * threw or produced a pair with an empty public key, and the car installer
 * surfaced that as a generic failure. Both halves must exist, or the pair is
 * rebuilt before the read.
 *
 * dadb's key code is plain `java.security` (RSA + PKCS8 + Base64), so this
 * runs on the JVM with no Android dependencies.
 */
class AdbKeyUtilSelfHealTest {

    @Test
    fun generatesBothHalvesWhenNothingExists() {
        val dir = createTempDir(prefix = "adbkey")
        try {
            val pair: AdbKeyPair = AdbKeyUtil.ensureAdbKeyPair(dir)
            assertNotNull(pair)
            assertTrue("private half must be generated", File(dir, "adbkey").exists())
            assertTrue("public half must be generated", File(dir, "adbkey.pub").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun regeneratesWhenPublicHalfIsMissing() {
        val dir = createTempDir(prefix = "adbkey")
        try {
            AdbKeyUtil.ensureAdbKeyPair(dir)
            assertTrue("precondition: the public half exists", File(dir, "adbkey.pub").delete())

            // The A-L24 state: private key present, public key absent. Must
            // self-heal rather than throw out of AdbKeyPair.read.
            val healed = AdbKeyUtil.ensureAdbKeyPair(dir)
            assertNotNull(healed)
            assertTrue("public half must be regenerated", File(dir, "adbkey.pub").exists())
            assertTrue("private half must still be present", File(dir, "adbkey").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun regeneratesWhenPrivateHalfIsMissing() {
        val dir = createTempDir(prefix = "adbkey")
        try {
            AdbKeyUtil.ensureAdbKeyPair(dir)
            assertTrue("precondition: the private half exists", File(dir, "adbkey").delete())

            val healed = AdbKeyUtil.ensureAdbKeyPair(dir)
            assertNotNull(healed)
            assertTrue("private half must be regenerated", File(dir, "adbkey").exists())
            assertTrue("public half must still be present", File(dir, "adbkey.pub").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aStalePublicHalfDoesNotSurviveARegeneration() {
        // A surviving public key next to a regenerated private key is a
        // mismatched pair — the car side would reject the auth with a
        // confusing signature error instead of an obvious regeneration.
        val dir = createTempDir(prefix = "adbkey")
        try {
            AdbKeyUtil.ensureAdbKeyPair(dir)
            val stalePub = File(dir, "adbkey.pub").readBytes()
            File(dir, "adbkey").delete()

            AdbKeyUtil.ensureAdbKeyPair(dir)
            assertFalse(
                "the stale public half must be replaced, not kept",
                stalePub.contentEquals(File(dir, "adbkey.pub").readBytes())
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anIntactPairIsReadWithoutRegenerating() {
        // The fix must not churn a healthy pair: regenerating on every call
        // would invalidate the car's authorized key on each install.
        val dir = createTempDir(prefix = "adbkey")
        try {
            AdbKeyUtil.ensureAdbKeyPair(dir)
            val priv = File(dir, "adbkey").readBytes()
            val pub = File(dir, "adbkey.pub").readBytes()

            AdbKeyUtil.ensureAdbKeyPair(dir)
            assertArrayEquals("private half must be untouched", priv, File(dir, "adbkey").readBytes())
            assertArrayEquals("public half must be untouched", pub, File(dir, "adbkey.pub").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
