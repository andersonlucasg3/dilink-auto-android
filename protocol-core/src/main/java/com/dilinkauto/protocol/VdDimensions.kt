package com.dilinkauto.protocol

/**
 * Pure viewport math for the phone-side VirtualDisplay created in response to
 * a car [HandshakeRequest].
 *
 * Lives in protocol-core (not app-client) so the DPI/size rules can be read
 * and tested as plain JVM code — the caller passes the phone's **real
 * physical** pixel size; no Android types are involved here.
 *
 * Rules:
 *  - VD width/height start from the car viewport, even-aligned (H.264 needs
 *    even dimensions) *before* scaling.
 *  - Anti-crop scale: if the car viewport is narrower than the phone's
 *    physical width (orientation-resolved: a portrait phone's long edge acts
 *    as the width for a landscape car), scale the VD up to match. Many
 *    Chinese ROMs hardcode IME width to the **physical display width**, and
 *    the IME width does not follow VD density — a narrower VD chops the
 *    keyboard horizontally no matter what DPI is negotiated. Scaling
 *    preserves the car's aspect ratio while satisfying the OS width
 *    requirement.
 *  - **Area tier (issue #153 PB)**: the upscale ceiling is selected by a
 *    session-scoped [VdAreaTier]. The default tier caps at
 *    `max(carWidth * 1.6, 1600)` (and never above [MAX_UPSCALED_WIDTH]) to cut
 *    per-frame composite area; the compatibility tier keeps the full 2560 cap
 *    for IME-heavy usage. The tier only changes the *ceiling*: the rule never
 *    scales a VD down, and the phone's physical width still wins when smaller.
 *  - **The phone size must come from real physical metrics
 *    (`WindowManager.maximumWindowMetrics` / `Display.getRealMetrics`), NOT
 *    `resources.displayMetrics`.** In screen-compat mode the latter reports
 *    compat-scaled values — the 2026-10-09 test phone (real 1368x3192
 *    @560dpi) reports a 2992px long edge, so the VD came out 2992 wide while
 *    the IME rendered at the true 3192, overhanging the VD by 200px (~6.7%)
 *    identically at DPI 120/200/300/400.
 *  - DPI: a car-side override (coerced to [120, 480]) bypasses the
 *    portrait-app-safe cap; otherwise auto-calibrate for a ~380dp logical
 *    width in landscape. **The car-reported density is scaled by the same
 *    anti-crop factor as the viewport** — the car reports density for its own
 *    (smaller) viewport, so using it verbatim on the enlarged VD makes the UI
 *    render too small (issue #20).
 */
object VdDimensions {

    /**
     * 放大上限（VD 宽度，px）—— **全局硬上限**，同时也是键盘兼容档的上限。
     *
     * issue #71：anti-crop 放大的目的只有一个 —— 满足「输入法按物理宽渲染」的
     * ROM 硬编码。但 VD 每帧都要整屏合成，本机（车机 1204x660、手机物理宽 3192）
     * 被放大 2.65× 到 3192x1748，即每帧 5.58MP，是长时间导航持续发烫的主要来源。
     * 封顶 2560 后同场景降到 2560x1402（合成像素 -36%）。
     *
     * 代价（明确记录）：物理宽超过 2560 的机型，输入法仍会被裁掉一部分
     * （3192 → 2560 约 20%）。这是用发热换来的取舍，见 issue #71 的 plan 追评。
     *
     * issue #153 PB：默认档会把上限进一步压到 [tierCap]，但**任何档位都不得越过
     * 2560**，且兼容档必须原样保留这个值，不得把 issue #71 的行为改回去。
     */
    const val MAX_UPSCALED_WIDTH = 2560

    /** 默认档宽度的下限（px）：车机很窄时至少放大到这里，避免 VD 过小导致 UI 挤压。 */
    const val DEFAULT_TIER_MIN_WIDTH = 1600

    /** 默认档按车机宽的 1.6 倍取上限；用整数 8/5，不用 float（见 [tierCap] 注释）。 */
    const val DEFAULT_TIER_SCALE_NUMERATOR = 8
    const val DEFAULT_TIER_SCALE_DENOMINATOR = 5

    /**
     * 按档位解析 anti-crop 放大的宽度上限（纯函数，可脱机表驱动测试）。
     *
     * @param carWidth 车机视口宽（**已偶数对齐**）；用放大前的宽度，不是放大后的目标宽度。
     * @param areaTier [VdAreaTier] 值，未知值由 [VdAreaTier.normalize] 回落默认档。
     * @return 偶数对齐的放大上限，且一定落在 [DEFAULT_TIER_MIN_WIDTH]..[MAX_UPSCALED_WIDTH]。
     */
    fun tierCap(carWidth: Int, areaTier: Int = VdAreaTier.AUTO): Int {
        if (VdAreaTier.normalize(areaTier) == VdAreaTier.COMPAT_2560) return MAX_UPSCALED_WIDTH
        // 宽度可达数千像素，用 float 回算会在偶数边界两侧漂移（下方放大算式已踩过
        // 这个坑：1080*3192/1920 = 1795.5 落到 1794/1796 都不对）。整数 8/5 = 1.6 精确。
        val scaled = ((carWidth.toLong() * DEFAULT_TIER_SCALE_NUMERATOR) /
            DEFAULT_TIER_SCALE_DENOMINATOR).toInt()
        return DimAlign.even(
            maxOf(scaled, DEFAULT_TIER_MIN_WIDTH).coerceAtMost(MAX_UPSCALED_WIDTH)
        )
    }

    /**
     * 满速理论合成吞吐（MPix/s），供 `vd-server` 周期日志的 `mpixPerSec=` 字段使用。
     *
     * **口径（重要）**：这是循环外的常量，按目标 fps 算「假如每拍都合成」的理论值，
     * **不区分静止与活动**。issue #153 分节 PA 的静止短路会让实际合成拍数远低于 fps，
     * 因此温升归因不能只看这个值，要看同一条日志里 `idleSkip` / `ticks` / `frames`
     * 的增长率。分母用 VD 尺寸而非 encode 尺寸：面积成本发生在 SurfaceFlinger
     * 合成那一步，不是 GL quad 缩放重绘那一步。
     */
    fun mpixPerSec(width: Int, height: Int, fps: Int): Double =
        width.toLong() * height * fps / 1_000_000.0

    /**
     * @param phoneRealWidth  real physical pixel width of the phone's default
     *   display (compat-scaling must be bypassed by the caller — see class
     *   KDoc).
     * @param phoneRealHeight real physical pixel height of the phone's
     *   default display.
     * @param areaTier        session-scoped [VdAreaTier] received in the
     *   handshake. Defaults to [VdAreaTier.AUTO] so callers that predate PB
     *   (and old peers that omit the trailing wire field) get the new default.
     *   Production caller always passes `request.vdAreaTier` explicitly.
     * @return (vdWidth, vdHeight, dpi) for the VD deploy args and the
     *   VirtualDisplay creation.
     */
    fun compute(
        request: HandshakeRequest,
        phoneRealWidth: Int,
        phoneRealHeight: Int,
        areaTier: Int = VdAreaTier.AUTO,
    ): Triple<Int, Int, Int> {
        var vdWidth = DimAlign.even(request.screenWidth)
        var vdHeight = DimAlign.even(request.screenHeight)

        val isCarLandscape = vdWidth > vdHeight
        val isPhoneLandscape = phoneRealWidth > phoneRealHeight
        val phonePhysicalWidth =
            if (isPhoneLandscape == isCarLandscape) phoneRealWidth else phoneRealHeight
        // 放大倍数要保留到 DPI 计算：车机上报的密度是按它自己的视口算的，
        // VD 放大多少倍，同一逻辑密度就要跟着放大多少倍（#20）。
        var scale = 1f
        // 放大目标同时受档位上限约束（issue #71 封顶 / #153 PB 两档）。
        // tierCap 只改上限：手机物理宽更小时仍以物理宽为准，车机视口更宽时
        // 下面的 `if (vdWidth < targetWidth)` 不会缩小 VD。
        val targetWidth = minOf(phonePhysicalWidth, tierCap(vdWidth, areaTier))
        if (vdWidth < targetWidth) {
            val sourceWidth = vdWidth
            scale = targetWidth.toFloat() / sourceWidth
            // 宽度直接取目标值，高度用整数比例算 —— 避免 `width * (target/width)`
            // 的浮点回算误差（1080*3192/1920 = 1795.5，float 可能落到 1794/1796 两侧）。
            vdWidth = DimAlign.even(targetWidth)
            vdHeight = DimAlign.even(((vdHeight.toLong() * targetWidth) / sourceWidth).toInt())
        }

        val dpi = if (request.dpiOverride > 0) {
            VdDeployArgs.coerceDpiOverride(request.dpiOverride)
        } else {
            // 未放大时 scale = 1，行为与原来完全一致；放大后把车机上报密度
            // 一起放大，否则 120..320 区间内会被原样保留，导致 UI 相对 VD 过小。
            VideoConfig.calculateOptimalDpi(
                vdWidth,
                vdHeight,
                (request.screenDpi * scale).toInt(),
            )
        }
        return Triple(vdWidth, vdHeight, dpi)
    }
}
