package com.dilinkauto.protocol

/**
 * Validates IPv4 addresses with both strict (0-255 octets) and loose (4 numeric groups) modes.
 */
object Ipv4Validator {
    /**
     * Strict IPv4 pattern: 4 dot-separated octets, each in 0..255.
     */
    private val STRICT_REGEX =
        Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    /**
     * Loose IPv4 pattern: 4 dot-separated numeric groups.
     */
    private val LOOSE_REGEX = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

    @JvmStatic
    fun isStrict(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return STRICT_REGEX.matches(ip)
    }

    @JvmStatic
    fun isLoose(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return LOOSE_REGEX.matches(ip)
    }

    @JvmStatic
    fun isValid(ip: String?, strict: Boolean = true): Boolean =
        if (strict) isStrict(ip) else isLoose(ip)

    @JvmStatic
    fun isStrictIpv4(ip: String?): Boolean = isStrict(ip)

    @JvmStatic
    fun isLooseIpv4(ip: String?): Boolean = isLoose(ip)
}
