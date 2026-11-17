package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4ValidatorTest {

    @Test
    fun `strict validator accepts valid IPv4 addresses`() {
        val validIps = listOf(
            "0.0.0.0",
            "127.0.0.1",
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "255.255.255.255",
            "8.8.8.8"
        )
        for (ip in validIps) {
            assertTrue("Expected $ip to be valid strict IPv4", Ipv4Validator.isStrict(ip))
            assertTrue("Expected $ip to be valid via isValid(strict=true)", Ipv4Validator.isValid(ip, strict = true))
            assertTrue("Expected $ip to be valid via isStrictIpv4", Ipv4Validator.isStrictIpv4(ip))
        }
    }

    @Test
    fun `strict validator rejects out-of-range octets and invalid formats`() {
        val invalidIps = listOf(
            null,
            "",
            "   ",
            "256.0.0.1",
            "1.2.3.4.5",
            "1.2.3",
            "1.2.3.a",
            "-1.0.0.1",
            "999.999.999.999",
            "192.168.1.1:8080",
            "localhost",
            "192.168.1.",
            ".192.168.1.1",
            "192..168.1.1"
        )
        for (ip in invalidIps) {
            assertFalse("Expected $ip to be rejected by strict IPv4 validator", Ipv4Validator.isStrict(ip))
            assertFalse("Expected $ip to be rejected by isValid(strict=true)", Ipv4Validator.isValid(ip, strict = true))
            assertFalse("Expected $ip to be rejected by isStrictIpv4", Ipv4Validator.isStrictIpv4(ip))
        }
    }

    @Test
    fun `loose validator accepts 4 numeric segments even if octets exceed 255`() {
        val looseValidIps = listOf(
            "192.168.1.1",
            "0.0.0.0",
            "255.255.255.255",
            "999.999.999.999",
            "001.002.003.004",
            "123.456.789.0"
        )
        for (ip in looseValidIps) {
            assertTrue("Expected $ip to be accepted by loose IPv4 validator", Ipv4Validator.isLoose(ip))
            assertTrue("Expected $ip to be accepted by isValid(strict=false)", Ipv4Validator.isValid(ip, strict = false))
            assertTrue("Expected $ip to be accepted by isLooseIpv4", Ipv4Validator.isLooseIpv4(ip))
        }
    }

    @Test
    fun `loose validator rejects non-numeric or non-quad patterns`() {
        val looseInvalidIps = listOf(
            null,
            "",
            "   ",
            "1.2.3",
            "1.2.3.4.5",
            "abc",
            "1.2.3.a",
            "192.168.1.1:8080",
            "192.168.1.",
            ".192.168.1.1"
        )
        for (ip in looseInvalidIps) {
            assertFalse("Expected $ip to be rejected by loose IPv4 validator", Ipv4Validator.isLoose(ip))
            assertFalse("Expected $ip to be rejected by isValid(strict=false)", Ipv4Validator.isValid(ip, strict = false))
            assertFalse("Expected $ip to be rejected by isLooseIpv4", Ipv4Validator.isLooseIpv4(ip))
        }
    }
}
