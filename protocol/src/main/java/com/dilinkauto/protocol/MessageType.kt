package com.dilinkauto.protocol

/** Control channel message types */
object ControlMsg {
    const val HANDSHAKE_REQUEST: Byte = 0x01
    const val HANDSHAKE_RESPONSE: Byte = 0x02
    const val HEARTBEAT: Byte = 0x03
    const val HEARTBEAT_ACK: Byte = 0x04
    const val DISCONNECT: Byte = 0x05
    const val LAUNCH_APP: Byte = 0x10
    const val GO_HOME: Byte = 0x11
    const val GO_BACK: Byte = 0x12
    const val APP_STARTED: Byte = 0x13
    const val APP_STOPPED: Byte = 0x14
    /** Phone → Car: VD display has no activities (stack empty after back) */
    const val VD_STACK_EMPTY: Byte = 0x15
    /** Phone → Car: currently focused app package on VD (payload: UTF-8 package name) */
    const val FOCUSED_APP: Byte = 0x16
    /** Car → Phone: VD server is running, connect to it on localhost:port */
    const val VD_SERVER_READY: Byte = 0x20
    /** Phone → Car: car app is being updated, don't reconnect — wait for restart */
    const val UPDATING_CAR: Byte = 0x30
    /** Car → Phone: request to uninstall an app (payload: package name UTF-8) */
    const val APP_UNINSTALL: Byte = 0x1B
    /** Car → Phone: request to open app info/settings (payload: package name UTF-8) */
    const val APP_INFO: Byte = 0x17
    /** Car → Phone: request app shortcuts (payload: package name UTF-8) */
    const val APP_SHORTCUTS: Byte = 0x18
    /** Phone → Car: app shortcuts list (payload: AppShortcutsListMessage) */
    const val APP_SHORTCUTS_LIST: Byte = 0x19
    /** Car → Phone: execute a specific shortcut (payload: AppShortcutActionMessage) */
    const val APP_SHORTCUT_ACTION: Byte = 0x1A
}

/** Video channel message types */
object VideoMsg {
    const val CONFIG: Byte = 0x01  // SPS/PPS config data
    const val FRAME: Byte = 0x02  // H.264 frame (may contain multiple NAL units)
}

/** Audio channel message types */
object AudioMsg {
    const val CONFIG: Byte = 0x01  // Audio format config
    const val FRAME: Byte = 0x02  // Audio frame
}

/** Data channel message types */
object DataMsg {
    const val NOTIFICATION_POST: Byte = 0x01
    const val NOTIFICATION_REMOVE: Byte = 0x02
    const val APP_LIST: Byte = 0x03
    /** Car → Phone: clear a single notification by id+packageName */
    const val NOTIFICATION_CLEAR: Byte = 0x04
    /** Car → Phone: clear all notifications */
    const val NOTIFICATION_CLEAR_ALL: Byte = 0x05
    /** Phone → Car: an app was uninstalled (payload: package name UTF-8) */
    const val APP_UNINSTALLED: Byte = 0x06
    /** Phone → Car: app info data (payload: AppInfoDataMessage) */
    const val APP_INFO_DATA: Byte = 0x07
    const val MEDIA_METADATA: Byte = 0x10
    const val MEDIA_PLAYBACK_STATE: Byte = 0x11
    const val MEDIA_ACTION: Byte = 0x12
    const val NAVIGATION_STATE: Byte = 0x20
    /** Car → Phone: log line (UTF-8 text). Phone writes to FileLog */
    const val CAR_LOG: Byte = 0x30.toByte()
}

/** Input channel message types */
object InputMsg {
    const val TOUCH_DOWN: Byte = 0x01
    const val TOUCH_MOVE: Byte = 0x02
    const val TOUCH_UP: Byte = 0x03
    /** Batched MOVE: N pointers in one message. Payload: count(1) + N * (pointerId(4) + x(4) + y(4) + pressure(4) + timestamp(8)) */
    const val TOUCH_MOVE_BATCH: Byte = 0x04
    const val KEY_EVENT: Byte = 0x10
}
