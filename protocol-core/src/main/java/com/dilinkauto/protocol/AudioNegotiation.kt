package com.dilinkauto.protocol

/**
 * 音频镜像（9640）是否启用的**唯一**判据，纯 JVM，供车机、桌面端、vd-server 共用。
 *
 * 两侧能力位语义不同，缺一不可（issue #67，Refs #13）：
 * - [HandshakeRequest.supportedFeatures] = 接收端「想播且能播」；
 * - [HandshakeResponse.supportedFeatures] = 手机「能采」（Android 11 / SDK 30
 *   的音频采集下限只约束手机，车机 SDK 28/29 不受限）。
 *
 * 老手机不带 trailing [HandshakeResponse.supportedFeatures] 时解码为 0，
 * 这里自然回退成「不启用音频」——安全方向，不会去连一个不存在的 9640。
 */
fun isAudioNegotiated(request: HandshakeRequest, response: HandshakeResponse): Boolean =
    response.accepted &&
        (request.supportedFeatures and FEATURE_AUDIO) != 0 &&
        (response.supportedFeatures and FEATURE_AUDIO) != 0
