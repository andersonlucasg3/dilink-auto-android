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
 *  - DPI: 用户 override 只有在 [VdDeployArgs.DPI_OVERRIDE_MIN]..[VdDeployArgs.DPI_OVERRIDE_MAX]
 *    **且不超过 [VideoConfig.maxSafeDpi]（本视口上限）**时才生效；否则回落到自动标定并打
 *    W 级日志（issue #202：`override=480` 曾无条件绕过上限，把竖屏应用压成细条）。
 *    自动标定按横屏 ~380dp 逻辑宽标定。**车机上报的密度按与视口相同的 anti-crop 系数放大**
 *    —— 车机报的是它自己（更小）视口的密度，直接用在放大后的 VD 上会让 UI 过小（issue #20）。
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
        val r = resolve(request, phoneRealWidth, phoneRealHeight, areaTier)
        return Triple(r.width, r.height, r.dpi)
    }

    /**
     * 一次 VD 参数解析的完整结果（issue #202 A：参数单一来源）。
     *
     * [token] 是 DPI 来源令牌（`auto` / `user-240` / `fallback-480-234`），由部署端原样
     * 透传到引擎 argv，让 `vd-server.log` 的 `Starting:` 行能直接说明"这个 dpi 是谁定的"。
     */
    data class Resolution(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val source: VdDpiSource,
        val token: String,
        /** 非 null = 需要打 W 级日志（用户设置非法/越界已回落）。 */
        val warning: String?
    )

    /**
     * [compute] 的带来源版本（issue #202 A）。
     *
     * 尺寸逻辑与 [compute] 完全一致；DPI 交给 [VdDpiResolver] 做唯一一次决策：
     * 用户 override（且不超过 [VideoConfig.maxSafeDpi]）> 自动标定 > 默认回落。
     */
    fun resolve(
        request: HandshakeRequest,
        phoneRealWidth: Int,
        phoneRealHeight: Int,
        areaTier: Int = VdAreaTier.AUTO,
    ): Resolution {
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

        // 自动标定值：未放大时 scale = 1，行为与原来完全一致；放大后把车机上报密度
        // 一起放大，否则 120..320 区间内会被原样保留，导致 UI 相对 VD 过小。
        val autoDpi = VideoConfig.calculateOptimalDpi(
            vdWidth,
            vdHeight,
            (request.screenDpi * scale).toInt(),
        )
        val dpi = VdDpiResolver.resolve(
            requestedOverride = request.dpiOverride,
            autoDpi = autoDpi,
            safeMaxDpi = VideoConfig.maxSafeDpi(vdWidth, vdHeight),
        )
        return Resolution(
            width = vdWidth,
            height = vdHeight,
            dpi = dpi.dpi,
            source = dpi.source,
            token = dpi.token,
            warning = dpi.warning
        )
    }
}
