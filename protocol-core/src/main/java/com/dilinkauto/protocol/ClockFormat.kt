package com.dilinkauto.protocol

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 车机主屏系统时间的纯格式化逻辑（issue #39）。
 *
 * 与 [SettingsFormat] 同格放在 protocol-core —— 车机 UI 必须在 BYD 硬件上才能完整
 * 驱动，而「把一个 epoch 毫秒渲染成 `HH:mm` / `h:mm`」与「算出距下一个整分钟还有
 * 多少毫秒」这两件事是纯函数，在 JVM 上就能用 JUnit 钉死，无需实机。
 *
 * 不显示秒、日期、星期；12/24 小时制由调用方按系统设置（[android.text.format.DateFormat.is24HourFormat]）
 * 决定后传入，本对象不读 Android API。
 */
object ClockFormat {

    /** 24 小时制：`HH:mm`，如 `18:05`。 */
    private val FORMAT_24H: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm")

    /** 12 小时制：`h:mm`（不补前导零，与 Android 系统时钟一致），如 `6:05`。 */
    private val FORMAT_12H: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm")

    /**
     * 把 [epochMillis]（UTC epoch 毫秒）按 [zoneId] 与 12/24 小时制渲染成 `HH:mm` / `h:mm`。
     *
     * @param epochMillis UTC epoch 毫秒；负值（1970 前）按 [ZonedDateTime] 的常规语义处理，
     *   车机场景不会出现，仅保证不抛异常。
     * @param zoneId 系统时区；调用方传 `ZoneId.systemDefault()`，本函数不自行读取系统默认时区，
     *   以便单测用固定时区钉跨日 / 跨时区行为。
     * @param is24Hour 是否 24 小时制，来自系统设置。
     */
    fun formatClockText(epochMillis: Long, zoneId: ZoneId, is24Hour: Boolean): String {
        val zoned = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            zoneId
        )
        return (if (is24Hour) FORMAT_24H else FORMAT_12H).format(zoned)
    }

    /**
     * 距下一个整分钟的毫秒数，用于协程 `delay` 对齐到分钟跳变，避免每秒重组。
     *
     * - 整分钟点（秒=0、毫秒=0）返回 60_000（下一个整分钟在 60s 后，不是 0）；
     * - 差 1ms 到整分钟返回 1；
     * - 结果恒在 `1..60_000`。
     *
     * 只看时-分-秒-毫秒，与日期 / 时区无关，因此 [epochMillis] 的绝对值不影响结果形状。
     */
    fun millisToNextMinute(epochMillis: Long): Long {
        val withinMinute = epochMillis % 60_000L
        // 负 epoch（1970 前）取模可能为负，规整到 [0, 60000)。
        val normalized = ((withinMinute % 60_000L) + 60_000L) % 60_000L
        return if (normalized == 0L) 60_000L else 60_000L - normalized
    }
}
