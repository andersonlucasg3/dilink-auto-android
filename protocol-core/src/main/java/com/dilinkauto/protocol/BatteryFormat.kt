package com.dilinkauto.protocol

/**
 * 车机信息栏的手机电量文本（issue #151）。
 *
 * 与 [ClockFormat] / [AltitudeFormat] / [WeatherFormat] 同格放在 protocol-core：
 * 「把 `level + charging` 渲染成 `86%` / `86%+` / `—`」是纯函数，JVM 单测即可覆盖，
 * 不需要真机电池广播。
 *
 * 空间约束：横屏左栏宽钉死在 76dp（`app-server/.../ui/InfoBar.kt` 的
 * `InfoChipColumn`，改宽会触发视口重握手），chip 只有一行文本、没有图标位置。
 * 因此这里只输出「数字 + `%`（+ 充电后缀 `+`）」，充电状态用后缀而不是图标表达。
 */
object BatteryFormat {

    /** 无数据（手机未上报 / 已断开）时的占位符，与天气 chip 保持一致。 */
    const val UNKNOWN = "—"

    /**
     * 把电量渲染成 chip 文本。
     *
     * - `level == null`（从未收到 / 已清空）→ [UNKNOWN]。
     * - `charging == true` → `86%+`（`+` 表示充电中）。
     * - `charging == false` / `null`（老手机不带该位）→ `86%`。
     * - `level` 越界（对端坏数据）夹到 `0..100`，宁可显示端点值也不让 chip 变空。
     *
     * 注：`100%+` 是唯一 5 字符的组合（其余 ≤4 字符）。12sp 等宽数字下宽度约 35dp，
     * 远小于 76dp 栏宽，且父容器定宽 + `maxLines=1` + `Ellipsis`，不会撑破栏位。
     */
    fun formatBattery(level: Int?, charging: Boolean?): String {
        if (level == null) return UNKNOWN
        val percent = level.coerceIn(0, 100)
        return if (charging == true) "$percent%+" else "$percent%"
    }
}
