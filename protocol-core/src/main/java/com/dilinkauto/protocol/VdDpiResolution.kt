package com.dilinkauto.protocol

/**
 * VD 密度（DPI）的最终来源（issue #202 A：参数单一来源）。
 *
 * 现场（2026-11-16 `.tmp/run-1116/client.log:1630`）出现过一次
 * `VD: 1926x1054 @480dpi (… override=480 …)`：同一台车、同一个视口，前后几次会话
 * 分别是 `@234dpi` / `@480dpi` / `@302dpi`。三个数分别来自「自动标定」「用户 override」
 * 「档位放大后的自动标定」，而旧实现里没有任何一处日志说明**生效值是谁决定的**。
 */
enum class VdDpiSource {
    /** 用户显式设置且通过校验（[VdDeployArgs.DPI_OVERRIDE_MIN]..[VdDeployArgs.DPI_OVERRIDE_MAX]、不超过本视口安全上限）。 */
    USER_OVERRIDE,

    /** 按视口与 [VdAreaTier] 自动标定（[VideoConfig.calculateOptimalDpi]）。 */
    AUTO,

    /** 用户设置非法或越界，已回落到自动标定值；必须伴随一条 W 级日志。 */
    FALLBACK
}

/**
 * 一次 DPI 解析的结果（issue #202 A）。
 *
 * 纯数据、无 Android 依赖：手机端（[VdDimensions.resolve]）与车机端（握手前校验）
 * 共用同一套判定，避免"三处各写一个字面量"再次漂移。
 */
data class VdDpiResolution(
    /** 最终生效的 DPI。 */
    val dpi: Int,
    /** 生效值的来源。 */
    val source: VdDpiSource,
    /** 用户原始设置的 override（<=0 = 未设置）。 */
    val requestedOverride: Int,
    /** 本视口下 DPI 的安全上限（[VideoConfig.maxSafeDpi]）：超过它竖屏应用会被压成细条。 */
    val safeMaxDpi: Int,
    /** 部署链路的来源令牌，只用 `[A-Za-z0-9._-]`（进 shell argv，不能带空格/元字符）。 */
    val token: String,
    /** 需要 W 级日志的回落原因；null = 无异常。 */
    val warning: String?
)

/**
 * DPI 单一来源解析器（issue #202 A）。
 *
 * 优先级（高 → 低）：
 *  1. **用户设置**（`HandshakeRequest.dpiOverride`）：必须同时满足协议区间
 *     `120..480` 与「不超过本视口安全上限」；
 *  2. **自动标定**（面积档位/视口决定的 [VideoConfig.calculateOptimalDpi]）；
 *  3. **默认值**：自动标定本身在视口非法时已经回落到
 *     [VideoConfig.DEFAULT_FALLBACK_DPI]，这里不再叠加第四个数。
 *
 * 为什么 override 也要过「安全上限」这一关：`VideoConfig.calculateOptimalDpi` 的 KDoc
 * 写明 DPI 高于 `H*80/360` 时，竖屏应用（高德/微信）在横屏 VD 里只剩 ~140dp 逻辑宽，
 * 图标按钮互相重叠到不可点。旧实现让 override **无条件绕过**这个上限，于是
 * "用户手滑输入 560 → UI 夹到 480 → 每次重连都是 480" 变成了一次持续的错误布局。
 * issue #202 的现场就是 `1926x1054` 视口（安全上限 234）配 `@480dpi`。
 *
 * 非法值（<=0 之外越界、或超过安全上限）**不夹到边界**，而是回落到自动标定值并打
 * W 日志 —— 夹到边界正是 480 这个"合法但荒谬"的值的制造方式。
 */
object VdDpiResolver {

    /** argv 里"来源未上报"的哨兵（旧版部署端不带该槽位）。 */
    const val SOURCE_UNKNOWN = "-"

    /** 来源令牌长度上限：只为日志/argv 可读性，不承载语义。 */
    const val MAX_TOKEN_LENGTH = 48

    /** 允许进 argv 的字符集：无空格、无 shell 元字符。 */
    private val TOKEN_REGEX = Regex("^[A-Za-z0-9._-]{1,$MAX_TOKEN_LENGTH}$")

    /**
     * 解析最终 DPI。
     *
     * @param requestedOverride 用户设置的 override，`<=0` = 未设置（Auto）。
     * @param autoDpi 自动标定值（已包含档位放大倍数与视口），通常来自
     *   [VideoConfig.calculateOptimalDpi]。
     * @param safeMaxDpi 本视口的安全上限，来自 [VideoConfig.maxSafeDpi]。
     */
    fun resolve(requestedOverride: Int, autoDpi: Int, safeMaxDpi: Int): VdDpiResolution {
        val auto = autoDpi.coerceAtLeast(1)
        val safeMax = safeMaxDpi.coerceAtLeast(VdDeployArgs.DPI_OVERRIDE_MIN)
        return when {
            requestedOverride <= 0 -> VdDpiResolution(
                dpi = auto,
                source = VdDpiSource.AUTO,
                requestedOverride = 0,
                safeMaxDpi = safeMax,
                token = "auto",
                warning = null
            )

            requestedOverride !in VdDeployArgs.DPI_OVERRIDE_MIN..VdDeployArgs.DPI_OVERRIDE_MAX ->
                VdDpiResolution(
                    dpi = auto,
                    source = VdDpiSource.FALLBACK,
                    requestedOverride = requestedOverride,
                    safeMaxDpi = safeMax,
                    token = fallbackToken(requestedOverride, auto),
                    warning = "W: dpiOverride=$requestedOverride 非法（只接受 0 或 " +
                        "${VdDeployArgs.DPI_OVERRIDE_MIN}..${VdDeployArgs.DPI_OVERRIDE_MAX}），" +
                        "已回落自动标定值 ${auto}dpi"
                )

            requestedOverride > safeMax -> VdDpiResolution(
                dpi = auto,
                source = VdDpiSource.FALLBACK,
                requestedOverride = requestedOverride,
                safeMaxDpi = safeMax,
                token = fallbackToken(requestedOverride, auto),
                warning = "W: dpiOverride=$requestedOverride 超过本视口安全上限 ${safeMax}dpi" +
                    "（超过后竖屏应用会被压成细条），已回落自动标定值 ${auto}dpi"
            )

            else -> VdDpiResolution(
                dpi = requestedOverride,
                source = VdDpiSource.USER_OVERRIDE,
                requestedOverride = requestedOverride,
                safeMaxDpi = safeMax,
                token = "user-$requestedOverride",
                warning = null
            )
        }
    }

    /** 回落令牌：`fallback-<请求值>-<生效值>`，负数按 0 记，保证只含允许字符。 */
    private fun fallbackToken(requested: Int, dpi: Int): String =
        "fallback-${requested.coerceIn(0, 99_999)}-$dpi"

    /**
     * 把任意来源令牌清洗成可进 argv 的形式；非法/空 → [SOURCE_UNKNOWN]。
     *
     * 该字符串会拼进设备侧 shell 行（`app_process … <argv>`），所以只放行
     * `[A-Za-z0-9._-]`，其余一律退化为"未上报"，而不是让整次部署失败。
     */
    fun sanitizeToken(token: String?): String {
        val t = token?.trim().orEmpty()
        return if (TOKEN_REGEX.matches(t)) t else SOURCE_UNKNOWN
    }

    /** 引擎侧解析来源槽位：`-`/缺省/非法 → `"unknown"`（只用于日志）。 */
    fun parseToken(arg: String?): String =
        sanitizeToken(arg).takeUnless { it == SOURCE_UNKNOWN } ?: "unknown"
}
