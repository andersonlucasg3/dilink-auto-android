package com.dilinkauto.client.service

import android.os.Build
import com.dilinkauto.protocol.FEATURE_AUDIO
import com.dilinkauto.protocol.FEATURE_VIDEO

/**
 * 手机（采集端）在握手响应里回传的能力位（issue #72, Refs #13）。
 *
 * **这是代理判据，不是精确探测。** 真实判据是 vd-server（shell UID）里
 * `AudioPipeline.start()` 的 REMOTE_SUBMIX / AAC 编码器初始化结果，但那条
 * 结果在**结构上**进不了这条握手响应：`ConnectionService.kt` 在构造响应
 * （`:598`）与发送（`:617`）之后才部署 vd-server（`:632-633`）。构造响应那一刻
 * `AudioPipeline.start()` 尚未运行，因此真实探测值无法回传 —— 除非新增一轮
 * 后置 IPC（协议层改动，超出本分节边界）。
 *
 * 为什么用 `SDK_INT >= 30` 作代理：Android 11（SDK 30）是 REMOTE_SUBMIX 音频
 * 采集的系统下限（scrcpy `doc/audio.md`：Android 10 及以前无法采集）。
 *
 * **已知误报（不假装完备）**：SDK≥30 但 ROM 拦截 shell REMOTE_SUBMIX 时，本函数
 * 会说「支持」而实际采不到。兜底是 S3 的静默降级：音频通道初始化/连接失败只
 * `carLogSend()`、绝不停视频会话；表现为「开关可点、打开后无声、不崩会话」。
 */
internal fun phoneAudioFeatures(sdkInt: Int): Int =
    FEATURE_VIDEO or (if (sdkInt >= Build.VERSION_CODES.R) FEATURE_AUDIO else 0)
