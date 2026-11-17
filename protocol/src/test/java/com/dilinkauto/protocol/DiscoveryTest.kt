package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun exactServiceNameMatches() {
        assertTrue(Discovery.isCompatibleServiceName("DiLink-Auto"))
    }

    @Test
    fun conflictRenamedServiceMatches() {
        assertTrue(Discovery.isCompatibleServiceName("DiLink-Auto (1)"))
        assertTrue(Discovery.isCompatibleServiceName("DiLink-Auto (2)"))
        assertTrue(Discovery.isCompatibleServiceName("DiLink-Auto (12)"))
    }

    @Test
    fun foreignServiceNameRejected() {
        assertFalse(Discovery.isCompatibleServiceName("OtherDevice"))
        assertFalse(Discovery.isCompatibleServiceName("DiLink"))
        assertFalse(Discovery.isCompatibleServiceName("AppleTV"))
        assertFalse(Discovery.isCompatibleServiceName(null))
        assertFalse(Discovery.isCompatibleServiceName(""))
    }
}
