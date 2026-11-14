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
    const val GO_RECENT: Byte = 0x1F
    const val APP_STARTED: Byte = 0x13
    const val APP_STOPPED: Byte = 0x14
    /** Phone → Car: VD display has no activities (stack empty after back) */
    const val VD_STACK_EMPTY: Byte = 0x15
    /** Car → Phone: VD server is running, connect to it on localhost:port */
    const val VD_SERVER_READY: Byte = 0x20
    /** Car → Phone: request to uninstall an app (payload: package name UTF-8) */
    const val APP_UNINSTALL: Byte = 0x1B
    /** Car → Phone: request to open app info/settings (payload: package name UTF-8) */
    const val APP_INFO: Byte = 0x17
    /** Phone → Car: VD server has bound video (9638) and input (9639) ports — connect now */
    const val VD_PORTS_BOUND: Byte = 0x31
    /**
     * Car/PC → Phone: 开关手机**物理**屏（payload: 1 字节，0=熄屏 1=亮屏）。
     *
     * 走输入口 9639 的 CONTROL 通道，由 vd-server 的 `handleCarCommand` 用
     * `DisplayPowerController.setPhysicalDisplayPower` 执行 —— 与 GO_HOME 同一条路径，
     * app-client 无需参与。
     *
     * 背景：vd-server 在接收端握住 video+input 两条通道后会**自动**熄屏，断开时自动恢复。
     * 这条命令只是把"中途手动点亮/熄灭"的开关交给用户。
     */
    const val SET_DISPLAY_POWER: Byte = 0x32
    /**
     * Car/PC → Phone: 要一个同步帧（IDR），payload 为空。
     *
     * 车机端解码器的参考链因丢帧出现洞时发这条（issue #9）：手机端立刻向编码器
     * `PARAMETER_KEY_REQUEST_SYNC_FRAME`，不必等下一个自然 GOP 边界（最多 ~1.2s）。
     * 与 SET_DISPLAY_POWER 同走输入口 9639 的 CONTROL 通道，由 vd-server 执行。
     */
    const val REQUEST_SYNC_FRAME: Byte = 0x33
    /**
     * Car/PC → Phone: VD 内容持续纯黑但解码仍在出帧时，请求手机侧做一次显示自愈。
     *
     * 与"重握手重建 VD"（[com.dilinkauto.protocol.BlackScreenDetector.onSustainedBlackScreen]）
     * 是两条不同的路：重建 VD 会打断整条会话（issue #11 已证明对纯色画面是误伤），
     * 而这里只让手机侧重新确认 VD 上的可见性（issue #15：多开应用后顶层 task
     * `visibleRequested=false`，内容纯黑且无法自恢复）。payload 为空。
     */
    const val REQUEST_DISPLAY_HEAL: Byte = 0x34
}

/** Video channel message types */
object VideoMsg {
    const val CONFIG: Byte = 0x01  // SPS/PPS config data
    const val FRAME: Byte = 0x02  // H.264 frame (may contain multiple NAL units)
}

/**
 * 音频通道（[Channel.AUDIO]，端口 [Ports.AUDIO_PORT]）的消息类型。
 * payload 布局在 [AudioPayload]；传输层仍用 [FrameCodec] 的 6 字节帧头。
 *
 * CONFIG 必须先于任何 FRAME：它带 MediaCodec 的 `csd-0`（AAC-LC 48 kHz
 * 立体声实测 2 字节）。裸 AAC 帧没有 ADTS 头，缺了 csd 解码器无法初始化
 * （issue #13 双机实测）。FRAME 带一个无符号 32 位毫秒 PTS 加一帧裸 AAC
 * 访问单元（48 kHz 下 1024 samples = 21.333 ms，平均约 341 B）。
 */
object AudioMsg {
    const val CONFIG: Byte = 0x01  // AudioPayload.encodeConfig
    const val FRAME: Byte = 0x02  // AudioPayload.encodeFrame
}

/** Data channel message types */
object DataMsg {
    const val APP_LIST: Byte = 0x03
    /** Phone → Car: an app was uninstalled (payload: package name UTF-8) */
    const val APP_UNINSTALLED: Byte = 0x06
    /** Phone → Car: app info data (payload: AppInfoDataMessage) */
    const val APP_INFO_DATA: Byte = 0x07
    const val MEDIA_METADATA: Byte = 0x10
    const val MEDIA_PLAYBACK_STATE: Byte = 0x11
    const val MEDIA_ACTION: Byte = 0x12
    const val NAVIGATION_STATE: Byte = 0x20
    /**
     * Phone → Car: 手机电量 / 充电状态（issue #151）。
     *
     * 定长 3 字节载荷（见 [PhoneStatus]）：`level | flags(bit0=charging) | temperature`。
     * 手机端在握手完成后按 [BatteryReportGate] 的节奏上行（≥2% 变化或充电位翻转立即、
     * 1% 变化 5s 防抖、每 60s 兜底），车机端只用于信息栏 chip。
     *
     * 向后兼容：车机端 [DataMsg] 的 `when` 分发没有 else 分支，
     * 老车机会直接忽略未知类型，不会把这一帧当成别的消息执行。
     */
    const val PHONE_STATUS: Byte = 0x21
    /** Car → Phone: log line (UTF-8 text). Phone writes to FileLog */
    const val CAR_LOG: Byte = 0x30.toByte()
    /** Phone ↔ Car: toggle logging (payload: 1-byte 0 or 1) */
    const val LOG_TOGGLE: Byte = 0x31.toByte()
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

