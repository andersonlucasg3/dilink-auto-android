package com.dilinkauto.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import com.dilinkauto.protocol.ClockFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/**
 * 车机主屏系统时间的 UI 状态（issue #39）。
 *
 * `null` 表示尚未首次采样（冷启动那一帧），UI 渲染空 chip；一旦 [ClockProvider]
 * 发出第一个值就不再回 null。
 */
data class TimeUiState(val text: String)

/**
 * 车机系统时钟的采样与广播订阅（issue #39）。
 *
 * 把「读系统时钟 + 按 12/24 制格式化 + 对齐整分钟刷新 + 跟随用户改时间/时区」
 * 这几件事从 [CarConnectionService] 里拆出来单独成一个类（SRP）：服务只持有
 * `timeState` 出口与 [start]/[stop] 两个生命周期调用，时间的来源与刷新节奏全归这里。
 *
 * - 取值：`System.currentTimeMillis()` + `ZoneId.systemDefault()`，纯车机本地时钟，
 *   不依赖手机端时间、不联网、不要权限。
 * - 格式化：委托纯函数 [ClockFormat.formatClockText]（protocol-core，单测覆盖跨日/跨时区/12-24 制）。
 * - 刷新：协程对齐到下一个整分钟（[ClockFormat.millisToNextMinute]）后每 60s 更新一次，
 *   不做每秒 tick，避免整棵 Compose 树每秒重组（骁龙 439 / 4GB RAM）。
 * - 立即跟随：注册 `ACTION_TIME_TICK`（系统每分钟广播）/ `ACTION_TIME_CHANGED`
 *   （用户改系统时间）/ `ACTION_TIMEZONE_CHANGED`（用户改时区），任一触发立即重算。
 *   复用 [CarConnectionService] 的 `usbReceiver` 同款 `RECEIVER_NOT_EXPORTED`（API ≥ 33）注册模式。
 *
 * 注册/注销跟随服务生命周期：服务 `onCreate` 调 [start]，`onDestroy` 调 [stop]。
 */
internal class ClockProvider(
    private val scope: CoroutineScope,
    private val context: Context,
    private val sink: MutableStateFlow<TimeUiState?>
) {
    private var tickJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    /** 只读出口，供 UI `collectAsState`。 */
    val state: StateFlow<TimeUiState?> = sink.asStateFlow()

    fun start() {
        // 先发一帧，避免冷启动到第一个整分钟之间 UI 一直空白。
        emitNow()
        tickJob = scope.launch {
            while (isActive) {
                // 对齐到下一个整分钟；整分钟点返回 60_000，差 1ms 返回 1（见 ClockFormatTest）。
                val waitMs = ClockFormat.millisToNextMinute(System.currentTimeMillis())
                delay(waitMs)
                emitNow()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // 用户改系统时间或时区后立即重算，不等下一个整分钟。
                emitNow()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        this.receiver = receiver
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { /* already unregistered */ }
        }
        receiver = null
    }

    private fun emitNow() {
        val is24Hour = DateFormat.is24HourFormat(context)
        sink.value = TimeUiState(
            ClockFormat.formatClockText(
                epochMillis = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault(),
                is24Hour = is24Hour
            )
        )
    }
}
