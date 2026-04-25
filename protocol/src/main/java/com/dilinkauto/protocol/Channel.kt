package com.dilinkauto.protocol

/**
 * Channel IDs for the DiLink-Auto protocol.
 * Each channel runs on a dedicated TCP connection (v0.10.0+).
 */
object Channel {
    /** Control messages: handshake, heartbeat, app switching, commands */
    const val CONTROL: Byte = 0

    /** Video stream: H.264 NAL units from phone to car */
    const val VIDEO: Byte = 1

    /** Audio stream: Opus frames from phone to car */
    const val AUDIO: Byte = 2

    /** Data messages: notifications, app list, metadata */
    const val DATA: Byte = 3

    /** Touch/input events: from car to phone */
    const val INPUT: Byte = 4
}
