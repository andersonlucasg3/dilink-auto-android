package com.dilinkauto.protocol

/**
 * Builds the argv tail for the vd-server ([com.dilinkauto.vdserver.PipelineServer])
 * process, shared by the three deploy sites (phone Shizuku, car USB ADB, car TCP ADB).
 *
 * PipelineServer.main() parses: `W H DPI PHONE_HOST EW EH FPS [CAR_HOST]`
 *   - W H       — VirtualDisplay dims (phone-side: scaled up to prevent IME crop on
 *                 Chinese ROMs; car-side: native car viewport).
 *   - DPI       — VD density (auto-calibrated or car-side override).
 *   - PHONE_HOST — host the VD server reverse-connects to on [LIFECYCLE_PORT].
 *   - EW EH     — encoder dims, clamped to 1920x1080. The Snapdragon 439 VPU caps
 *                 hardware AVC decode at 1080p; encoding larger forces software
 *                 decode on the car's 8x A53 (single-digit fps). Phone-side these
 *                 are the car-native dims (1:1 with car pixels, no decode downscale);
 *                 car-side they equal the VD dims (already <= 1080p).
 *   - FPS       — target frame rate; all pipeline timeouts derive from it.
 *   - CAR_HOST  — trailing, optional: the receiver's IP as the deployer sees it.
 *                 The engine binds 0.0.0.0:9638/9639 with otherwise no
 *                 authentication (audit S-01), so this is the one gate that keeps
 *                 every other host on the phone's WiFi out of a shell-UID process.
 *                 `-` or empty = accept any peer (legacy behaviour, kept for
 *                 deploy sites that cannot determine their own outbound IP).
 *   - ALLOWED   — issue #21: comma-joined package names the user允许上车（车机 App 列表
 *                 的白名单），`-` = 不下传（引擎不做白名单过滤）。引擎侧用它约束
 *                 [com.dilinkauto.protocol.RecentsParser.pickQuickSwitchTarget] 的
 *                 建屏恢复目标，避免把私有应用拉到车机屏。
 *   - SESSION   — issue #180 第三版: 本次会话的 token（`[A-Za-z0-9_-]{1,64}`），
 *                 `-` = 未传。引擎只监听自己的 `stop-request-<token>` 哨兵，
 *                 没有 token 时回退同目录下的固定名 `stop-request` + mtime 判定。
 *   - DPI_SRC   — issue #202 A: DPI 来源令牌（`auto` / `user-240` / `fallback-480-234`），
 *                 `-` = 旧部署端未上报。只用于引擎启动行打印"生效值是谁定的"，不参与建屏。
 */
object VdDeployArgs {
    const val MAX_ENCODE_WIDTH = 1920
    const val MAX_ENCODE_HEIGHT = 1080

    /** Sentinel for "no peer pinning" in the CAR_HOST argv slot. */
    const val CAR_HOST_ANY = "-"

    /** Sentinel for "no allowlist handed down" in the ALLOWED argv slot（issue #21）. */
    const val ALLOWED_ANY = "-"

    /** Sentinel for "no session token" in the SESSION slot（issue #180 第三版）. */
    const val SESSION_TOKEN_ANY = "-"

    /** Dotted-quad IPv4 or a DNS hostname — nothing a shell could break out of. */
    private val HOST_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

    /** Valid range for a car-side DPI override (sent as HandshakeRequest.dpiOverride). */
    const val DPI_OVERRIDE_MIN = 120
    const val DPI_OVERRIDE_MAX = 480

    fun format(
        vdWidth: Int,
        vdHeight: Int,
        dpi: Int,
        phoneHost: String,
        encodeWidth: Int,
        encodeHeight: Int,
        fps: Int,
        bitrate: Int = VideoConfig.DEFAULT_BITRATE,
        carHost: String = CAR_HOST_ANY,
        allowedPackages: Collection<String> = emptyList(),
        sessionToken: String? = null,
        /**
         * issue #202 A：DPI 来源令牌（[VdDpiResolver.sanitizeToken] 的输出）。
         * 只用于引擎 `Starting:` 行打印"最终生效值是谁定的"；`-` = 旧部署端未上报。
         */
        dpiSource: String = VdDpiResolver.SOURCE_UNKNOWN
    ): String {
        // 等比缩放到编码上限内：此前宽高分开 clamp 会把竖屏 1080x2152 压成方形
        // 1080x1080，导致车机端画面比例失真；等比缩放保持车机 viewport 原始宽高比。
        val encodeScale = minOf(1f, MAX_ENCODE_WIDTH.toFloat() / encodeWidth, MAX_ENCODE_HEIGHT.toFloat() / encodeHeight)
        val ew = DimAlign.even((encodeWidth * encodeScale).toInt())
        val eh = DimAlign.even((encodeHeight * encodeScale).toInt())
        // 输入校验范围有意比 UI 范围宽（见 VideoConfig 的分层说明），
        // 让手工写过的旧配置继续可部署。
        val br = if (bitrate in VideoConfig.INPUT_MIN_BITRATE..VideoConfig.INPUT_MAX_BITRATE) {
            bitrate
        } else VideoConfig.DEFAULT_BITRATE
        val safeCarHost = sanitizeCarHost(carHost)
        return "$vdWidth $vdHeight $dpi $phoneHost $ew $eh $fps $br $safeCarHost " +
            "${formatAllowedPackages(allowedPackages)} ${sanitizeSessionToken(sessionToken)} " +
            VdDpiResolver.sanitizeToken(dpiSource)
    }

    /**
     * 把会话 token 编码进 argv 的 SESSION 槽位（issue #180 第三版）。
     *
     * 这个字符串会进设备侧 shell 行，也会被拼成哨兵文件名，所以只放行
     * [VdDeploy.isValidSessionToken] 认可的字符集；非法/缺省一律退化为
     * [SESSION_TOKEN_ANY]，引擎据此走旧格式固定名。
     */
    fun sanitizeSessionToken(sessionToken: String?): String {
        val token = sessionToken?.trim().orEmpty()
        return if (VdDeploy.isValidSessionToken(token)) token else SESSION_TOKEN_ANY
    }

    /** 解析引擎侧的 SESSION 槽位：[sanitizeSessionToken] 的逆操作，`-`/非法 → 空串。 */
    fun parseSessionToken(arg: String?): String {
        val token = arg?.trim().orEmpty()
        return if (VdDeploy.isValidSessionToken(token)) token else ""
    }

    /**
     * issue #21：把白名单包名集合编码进 argv 的 ALLOWED 槽位。
     *
     * 只保留能过 [PACKAGE_NAME_REGEX] 的条目 —— 这个字符串会进设备侧 shell 行
     * （`app_process ... <args>`），任何 shell 元字符都必须被挡在编码端。非法条目
     * 直接丢弃而不是抛错：少放行一个应用只会退化成"不恢复它"，不值得让整次部署失败。
     */
    fun formatAllowedPackages(packages: Collection<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 255 && PACKAGE_NAME_REGEX.matches(it) }
            .distinct()
            .joinToString(",")
            .ifEmpty { ALLOWED_ANY }

    /**
     * 解析引擎侧的 ALLOWED 槽位（[formatAllowedPackages] 的逆操作）。
     *
     * `null`/`-`/空串/全部非法 → 空集合 = 不做白名单过滤（三条部署路径里车机/桌面
     * 拿不到手机 prefs，保持它们原有的行为）。
     */
    fun parseAllowedPackages(arg: String?): Set<String> {
        val raw = arg?.trim().orEmpty()
        if (raw.isEmpty() || raw == ALLOWED_ANY) return emptySet()
        return raw.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 255 && PACKAGE_NAME_REGEX.matches(it) }
            .toSet()
    }

    /**
     * Normalise the peer-pinning argument: blank or malformed values degrade to
     * [CAR_HOST_ANY] (legacy behaviour) rather than throwing at deploy time — a
     * failed host lookup must not take the session down with it. The value is
     * matched against [HOST_REGEX] before it is ever used, so a malformed one
     * would have been inert anyway.
     */
    fun sanitizeCarHost(carHost: String?): String {
        val host = carHost?.trim().orEmpty()
        if (host.isEmpty() || host == CAR_HOST_ANY) return CAR_HOST_ANY
        return if (host.length <= 253 && HOST_REGEX.matches(host)) host else CAR_HOST_ANY
    }

    /**
     * Coerce a raw DPI override to the valid range, with 0 = Auto (no override).
     * Used by both the phone (HandshakeResponse echo) and the car UI's input
     * field so the two sides agree on what counts as a valid override.
     */
    fun coerceDpiOverride(value: Int): Int = when {
        value <= 0 -> 0
        value < DPI_OVERRIDE_MIN -> DPI_OVERRIDE_MIN
        value > DPI_OVERRIDE_MAX -> DPI_OVERRIDE_MAX
        else -> value
    }
}

