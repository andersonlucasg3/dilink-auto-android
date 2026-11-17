package com.dilinkauto.server.service

import com.dilinkauto.protocol.PhoneStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 车机主屏的手机电量 UI 状态（issue #151）。
 *
 * `null` 表示「本会话还没收到过电量」或「会话已结束」——UI 渲染 `—`。
 * 温度 v1 不参与 UI，只留在协议里。
 */
data class PhoneStatusUiState(
    val level: Int,
    val charging: Boolean
)

/**
 * 手机电量在车机侧的持有者（issue #151）。
 *
 * 与 [ClockProvider] / [WeatherProvider] 同形态：`sink` 由 [CarConnectionService] 注入，
 * [state] 是只读出口（UI 只 `collectAsState`），写入 sink 的路径只有
 * [onStatus] / [clear] / [start] / [stop] 四处，保证「唯一写入者」。
 *
 * 数据不是自己采的：`CarSessionCoordinator.handleDataFrame` 解出 [PhoneStatus] 后经
 * 构造参数回调进来。协议解码留在分发点（与其他 DataMsg 一致），服务层不碰 Compose。
 *
 * 生命周期跟随服务：`onCreate` 建并 [start]，`onDestroy` 调 [stop]；会话断开时
 * coordinator 会回调 `null`（[clear]），避免下一次连接前显示上一台手机的电量。
 */
internal class PhoneStatusProvider(
    private val sink: MutableStateFlow<PhoneStatusUiState?>
) {
    /** 只读出口，供 UI `collectAsState`。 */
    val state: StateFlow<PhoneStatusUiState?> = sink.asStateFlow()

    fun start() {
        sink.value = null
    }

    fun stop() {
        sink.value = null
    }

    /** 收到一条合法电量状态。 */
    fun onStatus(status: PhoneStatus) {
        sink.value = PhoneStatusUiState(level = status.level, charging = status.charging)
    }

    /** 会话结束：回到「无数据」状态。 */
    fun clear() {
        sink.value = null
    }
}
