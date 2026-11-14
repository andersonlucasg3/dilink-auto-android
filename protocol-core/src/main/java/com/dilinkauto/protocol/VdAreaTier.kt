package com.dilinkauto.protocol

/**
 * VD 合成面积档位（issue #153 分节 PB / 候选 E）。
 *
 * 只决定 anti-crop 放大的**上限**，不决定 VD 的最终尺寸：最终尺寸仍由
 * [VdDimensions.compute] 按车机视口、手机真实物理尺寸与档位上限共同算出。
 * 档位是**会话级**的 —— 车机在握手时下发一个整数，手机在本次会话建屏时取用；
 * 它不是运行期可切换的 VD resize 开关，切档靠下次连接或旋转重握手。
 *
 * 同一个 Int 同时用于 SharedPreferences（[AppPrefs.VD_AREA_TIER]）与
 * [HandshakeRequest.vdAreaTier] 尾字段，因此 [AUTO] 必须是 0：老 peer 缺该
 * 尾字段时 `decode` 解出 0，行为自动落到默认档，无需版本协商。
 */
object VdAreaTier {

    /**
     * 默认档：上限 = `max(车机宽 * 1.6, 1600)`，且不越过
     * [VdDimensions.MAX_UPSCALED_WIDTH]。代价是部分 ROM 的输入法裁切会略增，
     * 收益是合成面积显著下降（实车 1204 宽：3.59MP → 2.03MP）。
     */
    const val AUTO = 0

    /**
     * 键盘兼容档：上限 = [VdDimensions.MAX_UPSCALED_WIDTH]（2560），
     * **完整保留 issue #71 的既有行为**，供 IME 密集场景手动切回。
     */
    const val COMPAT_2560 = 1

    /**
     * 未知值一律回落 [AUTO]。prefs 可能被手工改脏，新 peer 也可能下发本版本
     * 不认识的档位；回落比抛异常或误用兼容档更安全（后者会静默把面积放回去）。
     */
    fun normalize(value: Int): Int = if (value == COMPAT_2560) COMPAT_2560 else AUTO
}
