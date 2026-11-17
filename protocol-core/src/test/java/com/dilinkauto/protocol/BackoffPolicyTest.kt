package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackoffPolicyTest {

    @Test
    fun defaultPolicyMatchesCarReconnectBackoff() {
        val policy = BackoffPolicy.DEFAULT
        assertEquals(500L, policy.compute(0))
        assertEquals(500L, policy.compute(1))
        assertEquals(1000L, policy.compute(2))
        assertEquals(2000L, policy.compute(3))
        assertEquals(4000L, policy.compute(4))
        assertEquals(8000L, policy.compute(5))
        assertEquals(8000L, policy.compute(6))
        assertEquals(8000L, policy.compute(50))
    }

    @Test
    fun tolerantPolicyMatchesDesktopRebuildBackoff() {
        val policy = BackoffPolicy.TOLERANT
        assertEquals(500L, policy(0))
        assertEquals(500L, policy(1))
        assertEquals(500L, policy(2))
        assertEquals(500L, policy(3))
        assertEquals(1000L, policy(4))
        assertEquals(2000L, policy(5))
        assertEquals(4000L, policy(6))
        assertEquals(8000L, policy(7))
        assertEquals(8000L, policy(8))
        assertEquals(8000L, policy(100))
    }

    @Test
    fun staticExponentialHelperMatchesInstance() {
        for (i in 0..10) {
            assertEquals(
                BackoffPolicy.DEFAULT.compute(i),
                BackoffPolicy.exponential(i.toLong())
            )
            assertEquals(
                BackoffPolicy.TOLERANT.compute(i),
                BackoffPolicy.exponential(i.toLong(), escalateAfter = 3L)
            )
        }
    }

    @Test
    fun customCapAndDoublings() {
        val policy = BackoffPolicy(baseMs = 100L, maxMs = 350L, escalateAfter = 0L, maxDoublings = 3)
        // 0 -> 100
        // 1 -> 200
        // 2 -> 400 coerce to 350
        assertEquals(100L, policy(0))
        assertEquals(200L, policy(1))
        assertEquals(350L, policy(2))
        assertEquals(350L, policy(3))
    }

    @Test
    fun invalidParametersThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(baseMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(baseMs = 1000L, maxMs = 500L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(escalateAfter = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(maxDoublings = -1)
        }
    }
}
