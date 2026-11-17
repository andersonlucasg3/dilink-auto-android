package com.dilinkauto.server.service

import com.dilinkauto.protocol.FEATURE_AUDIO
import com.dilinkauto.protocol.FEATURE_VIDEO

/**
 * 车机端音频镜像的纯策略（issue #72, Refs #13）。
 *
 * 抽成 top-level `internal fun` 而非内联在服务/UI 里，是因为 `app-server` 没有
 * Robolectric（`build.gradle.kts:96` `unitTests.isReturnDefaultValues = true` 会让
 * `getSharedPreferences()` 返回 null），凡是牵扯 `CarPrefs` 的读写都无法在 JVM 上直测。
 * 因此把「能判定的部分」全部挤到这几个不碰 Android API 的函数里，由
 * [com.dilinkauto.server.service.AudioPolicyTest] 钉住；`CarPrefs` 的持久化本身
 * 只能走真机回签。范式同 `CarConnectionServicePolicyTest` 里的
 * `reconnectBackoffMs` / `iconBudgetAccepts`。
 */

/**
 * 车机在握手 request 里声明「想用且能用」的能力位。
 *
 * 开关打开 → 置 [FEATURE_AUDIO]；关闭 → 只声明 [FEATURE_VIDEO]。语义是**接收端
 * 自己的意愿**（`HandshakeRequest.supportedFeatures` 的 KDoc 定义），不代表手机端
 * 能采集；手机端能力走 `HandshakeResponse.supportedFeatures` 单独回传。
 */
internal fun carRequestFeatures(audioEnabled: Boolean): Int =
    FEATURE_VIDEO or (if (audioEnabled) FEATURE_AUDIO else 0)

/**
 * 手机（采集端）回传的能力位里是否置了音频位。
 *
 * 这是设置页音频开关「按手机能力位置灰」的唯一依据。车机自身 `SDK_INT` 不参与
 * 判定——音频采集的系统下限（Android 11 / SDK 30）只约束手机端，车机 `minSdk`
 * 为 26，不受限（issue #13 §4.4）。
 */
internal fun phoneSupportsAudio(features: Int): Boolean =
    features and FEATURE_AUDIO != 0

/** 音频开关不可点的原因；[NONE] 表示可点。 */
internal enum class AudioSwitchBlockReason {
    /** 可点。 */
    NONE,

    /**
     * 手机端回传的能力位没置音频位（旧手机、Android 10 及以下，或能力位对端未实现）。
     * UI 用 `audio_mirror_unsupported` 文案说明原因。
     */
    PHONE_UNSUPPORTED,
}

/**
 * 设置页音频开关的渲染状态：是否可点、当前勾选、不可点时的原因。
 *
 * @param phoneSupported 手机端回传的能力位是否含音频位（握手响应到达前为 false）。
 * @param audioEnabled 用户持久化的开关值（默认开）。
 *
 * 置灰（`phoneSupported == false`）时 `checked` 一并归 false：能力位没到位就显示
 * 「开」会与灰态自相矛盾，也会让用户以为打开过却始终无声。
 */
internal data class AudioSwitchState(
    val clickable: Boolean,
    val checked: Boolean,
    val blockReason: AudioSwitchBlockReason,
)

internal fun audioSwitchState(phoneSupported: Boolean, audioEnabled: Boolean): AudioSwitchState =
    if (phoneSupported) {
        AudioSwitchState(
            clickable = true,
            checked = audioEnabled,
            blockReason = AudioSwitchBlockReason.NONE,
        )
    } else {
        AudioSwitchState(
            clickable = false,
            checked = false,
            blockReason = AudioSwitchBlockReason.PHONE_UNSUPPORTED,
        )
    }

/**
 * 车机是否应当去连 9640（S3-PB 的连接门，issue #73 / Refs #13）。
 *
 * 两个条件缺一不可：
 * - `audioEnabled`：用户自己的开关（S4 落地，默认开）。关掉就不去连，即便手机支持。
 * - `phoneSupported`：手机（采集端）是否支持音频 —— **直接复用 S4 已有的单一真源**
 *   `CarConnectionService.phoneAudioSupported`（它自己由 [phoneSupportsAudio] 从
 *   握手响应的能力位算出，S4 已落地并单测）。本节**不另存原始能力位**：那是同一信息
 *   的第二份状态，会与 `phoneAudioSupported` 漂移。
 *
 * 抽成纯函数的原因同 [phoneSupportsAudio]：`carPrefs` 在纯 JVM 上返回 null，
 * `CarConnectionService` 是 Service，连接门若内联就无法单测。
 */
internal fun carShouldConnectAudio(audioEnabled: Boolean, phoneSupported: Boolean): Boolean =
    audioEnabled && phoneSupported
