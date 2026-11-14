package com.dilinkauto.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.BatteryReportGate
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.PhoneStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 一次电量采样：百分比 + 是否充电 + 可选温度（摄氏度）。 */
internal data class BatterySample(
    val level: Int,
    val charging: Boolean,
    val temperatureC: Int?
)

/**
 * 从 `ACTION_BATTERY_CHANGED` 的原始字段换算出 [BatterySample]（issue #151）。
 *
 * 抽成纯函数是因为这层换算最容易出错且没法在真机上穷举：
 * `EXTRA_LEVEL` 是相对 `EXTRA_SCALE` 的刻度（大多数机型 scale=100，但不保证），
 * 直接当百分比会把 50/255 报成 50%。`EXTRA_TEMPERATURE` 的单位是 0.1°C。
 *
 * @param levelRaw `EXTRA_LEVEL`，缺失时调用方传 -1
 * @param scale `EXTRA_SCALE`，缺失时传 0；非正数视为坏数据
 * @param status `EXTRA_STATUS` 的 `BATTERY_STATUS_*`
 * @param temperatureTenths `EXTRA_TEMPERATURE`（0.1°C），缺失时传 [Int.MIN_VALUE]
 * @return 无法解读时返回 null（例如广播缺字段），调用方跳过本次上报
 */
internal fun parseBatterySample(
    levelRaw: Int,
    scale: Int,
    status: Int,
    temperatureTenths: Int
): BatterySample? {
    if (levelRaw < 0 || scale <= 0) return null
    // 四舍五入到整数百分比，再夹到 0..100：有的机型 scale 与 level 组合会给出 100.x。
    val level = ((levelRaw.toLong() * 100L + scale / 2) / scale).toInt().coerceIn(0, 100)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val temperatureC = if (temperatureTenths == Int.MIN_VALUE) {
        null
    } else {
        val celsius = (temperatureTenths / 10.0).roundToInt()
        // 越界温度视为传感器坏值：宁可不报，也不让车机拿到 -273°C 之类的数。
        if (celsius in PhoneStatus.MIN_TEMPERATURE_C..PhoneStatus.MAX_TEMPERATURE_C) celsius else null
    }
    return BatterySample(level = level, charging = charging, temperatureC = temperatureC)
}

/**
 * 手机电量的采集与上行（issue #151）。
 *
 * 数据来源：`ACTION_BATTERY_CHANGED` 是 sticky 广播 —— 注册时系统会立刻补发当前值，
 * 所以既不用常驻轮询 BatteryManager，也不用等电量变化。这里在 [start] 里再用
 * `registerReceiver(null, filter)` 主动取一次，保证服务一启动（还没收到广播时）就有值。
 *
 * 上报节奏全部交给纯函数 [BatteryReportGate]：变化 ≥2% / 充电位翻转立即发，
 * 1% 变化 5s 防抖，电量不变 60s 兜底。**只有握手完成且控制连接可用时才发**
 * （[isReady]），未连接不上报、也不消耗 gate 的基准。
 *
 * 失败必须留痕（issue #41 的教训）：广播回调与定时协程都在主线程，这里只做
 * 「判定 + 非阻塞入队」，`send` 抛出的异常统一 `FileLog.w` 记录原始原因；
 * 失败不 `markSent`，下一轮广播或 60s 兜底会重试。
 *
 * 生命周期跟随 [ConnectionService]：`onCreate` 调 [start]，`onDestroy` 调 [stop]。
 */
internal class PhoneBatteryReporter(
    private val scope: CoroutineScope,
    private val context: Context,
    private val isReady: () -> Boolean,
    private val send: (Byte, ByteArray) -> Unit
) {
    private val gate = BatteryReportGate()
    private var receiver: BroadcastReceiver? = null
    private var heartbeatJob: Job? = null

    /** 最近一次采样；广播没来的时候 60s 兜底用它重发。 */
    @Volatile
    private var lastSample: BatterySample? = null

    fun start() {
        // sticky 立即取当前值：注册回调那一刻不必等
        readSticky()?.let { onSample(it) }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                readSample(intent)?.let { onSample(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        this.receiver = receiver

        // 电量不变时 ACTION_BATTERY_CHANGED 不会再来，靠这条定时兜底证明手机还活着，
        // 也顺便把「断开重连后 60s 内没有变化」的窗口补上。
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(BatteryReportGate.HEARTBEAT_MS)
                lastSample?.let { maybeSend(it) }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { /* 已注销 */ }
        }
        receiver = null
        lastSample = null
        gate.reset()
    }

    /** 会话结束（断线/被清理）：清掉节流基准，下次连上第一帧立即上报。 */
    fun onSessionEnded() {
        gate.reset()
    }

    private fun readSticky(): BatterySample? {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            FileLog.w(TAG, "read sticky battery failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
        return intent?.let { readSample(it) }
    }

    private fun readSample(intent: Intent): BatterySample? = parseBatterySample(
        levelRaw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0),
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
        temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    )

    private fun onSample(sample: BatterySample) {
        lastSample = sample
        maybeSend(sample)
    }

    private fun maybeSend(sample: BatterySample) {
        if (!isReady()) return
        val now = SystemClock.elapsedRealtime()
        if (!gate.shouldSend(sample.level, sample.charging, now)) return
        try {
            val payload = PhoneStatus(
                level = sample.level,
                charging = sample.charging,
                temperatureC = sample.temperatureC
            ).encode()
            send(DataMsg.PHONE_STATUS, payload)
            gate.markSent(sample.level, sample.charging, now)
            FileLog.i(
                TAG,
                "phone status sent: level=${sample.level} charging=${sample.charging} temp=${sample.temperatureC}"
            )
        } catch (e: Exception) {
            // 关键：静默 catch 会让功能恒失效且查不出来（issue #41）。
            FileLog.w(TAG, "phone status send failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PhoneBattery"
    }
}
