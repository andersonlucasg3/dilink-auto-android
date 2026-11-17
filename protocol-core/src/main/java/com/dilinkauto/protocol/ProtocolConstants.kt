package com.dilinkauto.protocol

/**
 * Wire-protocol constants shared by every client/server implementation.
 *
 * Split out of `Messages.kt` (which had grown into a message-types + decode-helpers
 * + constants grab-bag) so protocol-wide values have an obvious home and the
 * message file contains only message definitions. Everything here is top-level
 * in the same package, so existing references keep resolving unchanged.
 */

const val PROTOCOL_VERSION = 1

/**
 * Reject peers announcing an incompatible wire version instead of decoding
 * trailing fields at the wrong offsets (the field used to be carried and
 * ignored by all three consumers).
 *
 * @throws ProtocolDecodeException when [version] is not exactly [PROTOCOL_VERSION].
 */
fun requireSupportedProtocolVersion(version: Int): Int {
    if (version != PROTOCOL_VERSION) {
        throw ProtocolDecodeException("Unsupported protocol version: $version (expected $PROTOCOL_VERSION)")
    }
    return version
}

const val DISPLAY_MODE_MIRROR: Byte = 0
const val DISPLAY_MODE_VIRTUAL: Byte = 1

const val FEATURE_VIDEO = 0x01
const val FEATURE_AUDIO = 0x02
const val FEATURE_MEDIA_CONTROL = 0x08
const val FEATURE_NAVIGATION = 0x10

// Connection methods (handshake response)
const val CONNECTION_METHOD_USB_ADB: Byte = 0
const val CONNECTION_METHOD_WIFI_ADB: Byte = 1
const val CONNECTION_METHOD_SHIZUKU: Byte = 2
