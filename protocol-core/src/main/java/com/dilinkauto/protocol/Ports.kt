package com.dilinkauto.protocol

/**
 * 协议级 TCP 端口常量。
 *
 * 这些端口是手机（app-client / vd-server）与接收端（车机 app-server / 桌面 app-desktop）
 * 之间的契约，三端必须一致。集中放在平台无关的 :protocol-core，保证只有一处定义。
 *
 * 说明：Android 侧的 [Discovery] 保留同名常量并转发到这里（历史调用点较多），
 * 因此实际取值仍只有本文件这一个来源。
 */
object Ports {
    const val DEFAULT_PORT = 9637     // 控制 + 数据（手机监听，接收端连接）
    const val VIDEO_PORT = 9638       // 仅视频（手机 -> 接收端）
    const val INPUT_PORT = 9639       // 仅输入（接收端 -> 手机）
    /** 仅音频（手机 -> 接收端，AAC-LC），由 vd-server 监听；见 AudioPayload。 */
    const val AUDIO_PORT = 9640
    const val LIFECYCLE_PORT = 19647  // vd-server 反向连回手机（localhost）
    const val ADB_PORT = 5555         // 标准 ADB TCP 端口（开发模式 + 自安装）
}