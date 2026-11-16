package com.dilinkauto.protocol

/**
 * 帧率能力的判定函数（issue #203）。
 *
 * 背景：请求 30fps 的实车会话里，编码循环精确按 30 拍/秒走，但 VD 只交付了
 * 17.2 个新帧/秒（`.tmp/run-1116/vd-server.log`）。这说明「帧率是否兑现」必须靠
 * **实测**观测，而不是靠配置项调用成功与否；30fps 是否可达也还没有定论。
 *
 * 因此这里只提供两个纯函数：
 *  - [isTargetReached]：手机侧按窗口实测帧率判断「目标帧率有没有兑现」，供日志打 W；
 *  - [capFor]：**将来**有了车机 `decodeMs` 回传通道后，按单帧预算给出档位上限。
 *    没有实测值时必须返回 null（= 不设上限、不禁用任何档位），不能把某个数字
 *    写死成「实车已验证上界」——那会让 A 方案（刷新率真正生效）永远失去验证机会。
 *
 * 刻意无 Android 依赖，车机与手机两端都能用，也便于在 JVM 上钉成回归测试。
 */
object FrameRateBudget {

    /** 实测解码耗时必须低于「单帧预算 × 该比例」，才算该档留有余量。 */
    const val DECODE_HEADROOM = 0.9

    /** 实测帧率达到「目标 × 该比例」即认为目标已兑现（容忍抖动与静止画面）。 */
    const val ACHIEVED_TOLERANCE = 0.9

    /** 单个帧的预算（毫秒）。超出即意味着该档一定排队。 */
    fun budgetMs(fps: Int): Double = 1000.0 / SettingsFormat.coerceFps(fps)

    /**
     * 能力上限：建议可用的最高预设档 + 判定理由（理由直接进日志/设置页说明）。
     *
     * 只作为**展示/建议**，不作禁用依据：本 issue 的可达性结论待实车回签。
     */
    data class Cap(val fps: Int, val reason: String)

    /**
     * 按实测解码耗时给出建议的最高预设档。
     *
     * @param decodeMs 车机实测单帧解码耗时（毫秒）。null / ≤0 = 没测过。
     * @return null 表示「没有实测依据，不设上限」；否则给出能装下该耗时的最高预设档。
     */
    fun capFor(decodeMs: Double?): Cap? {
        if (decodeMs == null || decodeMs <= 0.0) return null
        val presets = SettingsFormat.FPS_PRESETS
        val floor = presets.min()
        val usable = presets
            .filter { budgetMs(it) * DECODE_HEADROOM >= decodeMs }
            .maxOrNull()
        if (usable == null) {
            return Cap(
                floor,
                "实测单帧解码 ${formatMs(decodeMs)}ms 已超出 ${floor}fps 的预算（${formatMs(budgetMs(floor))}ms）",
            )
        }
        return Cap(
            usable,
            "实测单帧解码 ${formatMs(decodeMs)}ms，${usable}fps 预算 ${formatMs(budgetMs(usable))}ms 仍在余量内",
        )
    }

    /**
     * 目标帧率是否真的兑现（issue #203 的 C 项：不能只看调用成功）。
     *
     * @param achievedFps 实测帧率（手机侧按窗口墙钟统计的 `newFps`）
     */
    fun isTargetReached(requestedFps: Int, achievedFps: Double): Boolean =
        requestedFps > 0 && achievedFps >= requestedFps * ACHIEVED_TOLERANCE

    private fun formatMs(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}
