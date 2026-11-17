package com.dilinkauto.protocol

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    /**
     * 默认目标帧率。issue #71：24 → 20（每帧预算 41ms → 50ms，管线节拍 -17%）。
     *
     * 24fps 是给 A53 + Adreno 505 那档车机留的折中；实车 2.5h 导航反馈手机持续发烫，
     * 降帧是与 VD 放大封顶配套的第二档减压手段。车机若存过 `startup_fps` 偏好，
     * 仍会按存值下发，需要在设置里同步改。
     */
    const val TARGET_FPS = 20

    /** UI-accepted frame-rate range for the settings screens' FPS field. */
    const val MIN_FPS = 10
    const val MAX_FPS = 60

    // ─── Bitrate bounds, layered on purpose ───
    // All bitrate limits live here so the four consumers (settings UI, deploy
    // argv validation, runtime adaptive controller, encoder default) cannot
    // drift into four different value sets again.

    /** Default / start bitrate for the video pipeline (CBR). */
    const val DEFAULT_BITRATE = 4_000_000 // 4 Mbps

    /** UI-accepted range for the settings screens' bitrate field. */
    const val MIN_BITRATE = 1_000_000     // 1 Mbps
    const val MAX_BITRATE = 12_000_000    // 12 Mbps

    /**
     * Deployment argv validation range — deliberately wider than the UI range
     * so older configs and manually-edited values keep deploying.
     * (Consumed by [VdDeployArgs.format].)
     */
    const val INPUT_MIN_BITRATE = 500_000
    const val INPUT_MAX_BITRATE = 20_000_000

    /**
     * Quality floor for the runtime adaptive bitrate controller. Higher than
     * [MIN_BITRATE]: below ~1.5 Mbps the decoder picture on the car becomes
     * visibly unusable, which is worse than the controller just not throttling
     * further. (Consumed by `AdaptiveBitrate` in vd-server.)
     */
    const val ADAPTIVE_MIN_BITRATE = 1_500_000

    const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 50ms at 20fps
    const val VIRTUAL_DISPLAY_DPI = 480  // legacy fallback constant
    const val TARGET_SW_DP = 600  // smallest-width dp for VD size calculation
    const val DEFAULT_FALLBACK_DPI = 160

    /**
     * 静止画面的保活合成间隔（毫秒），issue #153-PA。
     *
     * 手机侧两处共用：`GlPipeline` 按 [ComposeDecision] 决策每隔这么久用
     * 上一帧纹理重画一次；`VideoEncoderSelector` 把它换算成微秒写进
     * `repeat-previous-frame-after`，让编码器在无输入时自动重复上一帧。
     *
     * 必须是**同一个值**：手机侧短路间隔若大于编码器补帧间隔，会有一段
     * 车机收不到任何帧的窗口；若小于，则两个保活机制互相抢拍、多编码帧。
     * 这与 issue #75 的 IDR 闸门死区同源 —— 两端各写一个字面量必然漂移。
     *
     * 取 500ms 而不是更长：车机侧 `VideoDecoder` 空闲按 `poll(1000/fps)`
     * 等帧、`RenderStallDetector` 会把明显的停顿打成 STALL；2fps 的保活
     * 足以维持画面与参考链，同时把静止时的合成次数降到满速的约 1/10
     * （20fps 口径；车机设 30fps 时约 1/15）。
     */
    const val KEEP_ALIVE_COMPOSE_INTERVAL_MS = 500L

    // ─── IDR 补帧请求闸门（两端单一来源 [SyncRequestGate]：issue #75 / #163）───

    /**
     * IDR 补帧请求滑动窗口的长度（毫秒），**车机与手机共用这一个值**。
     *
     * 车机（app-server）与手机（vd-server）各有一道 IDR 请求闸门。历史上两端各写各的：
     * 车机 500ms、手机 1000ms，于是「距上次授予 500–999ms」的补帧请求会被车机放行、
     * 又被手机静默吞掉，车机只能干等下一个自然 GOP（`I_FRAME_INTERVAL`=3s）。实测
     * 长会话每约 50s 冻结 2.4–2.9s（issue #75 现场日志：747ms 间隔的请求被吞后干等
     * 2372ms）。
     *
     * 窗口长度沿用 1000：手机侧 1s 是 issue #3 用来打断「IDR 突发打满链路 → 更多
     * 丢帧 → 更多 IDR」正反馈的闸门，issue #70 又把自然 GOP 拉到 3s，方向一致。
     * issue #163 只把窗口内的**放行次数**从 1 提到 [SYNC_FRAME_REQUEST_MAX_PER_WINDOW]，
     * 不改窗口长度。
     */
    const val SYNC_FRAME_REQUEST_WINDOW_MS = 1000L

    /**
     * 单个 [SYNC_FRAME_REQUEST_WINDOW_MS] 窗口内最多放行的 IDR 请求次数。
     *
     * issue #163：旧实现 `syncFrameRequestAllowed` 是「距上次放行 ≥1s」的硬性间隔，
     * 等价于窗口内只放行 1 次。现场 2026-11-14 20:19 的日志里，第 8 次重同步刚在 305ms
     * 前恢复、随即再次断链，第二次请求被吞（`IDR request throttled (last request 298ms
     * ago, min 1000ms)`），只能干等 3s 的自然 GOP，跳过 87 个 P 帧、冻结 2894ms。
     *
     * 提到 2 次：既能补上「刚恢复又断链」的二次缺口，又保留窗口限次（不放开 issue #70 /
     * #55 担心的 IDR 风暴）。
     */
    const val SYNC_FRAME_REQUEST_MAX_PER_WINDOW = 2

    /**
     * Calculate optimal Virtual Display DPI for car display.
     *
     * Problem addressed:
     * When car display is in landscape (W > H, e.g. 2074x1020, 1408x792, 1280x720):
     * Portrait-only apps (like Amap / 高德地图, WeChat) are letterboxed by Android WMS.
     * Their window height equals VD height H, and window width is clamped by aspect ratio:
     * W_portrait ≈ H * (9 / 18) to H * (9 / 16) ≈ H * 0.5 to H * 0.5625.
     *
     * In Android design guidelines, handheld phone apps REQUIRE a minimum logical width:
     * sw >= 360dp (ideal comfortable phone width is 380dp ~ 412dp).
     *
     * If DPI is set too high (e.g. 480 or 560):
     * W_dp = W_portrait / (DPI / 160) = (H * 0.5 * 160) / DPI.
     * At H=792, DPI=480: W_dp = 132dp!
     * At H=1020, DPI=560: W_dp = 145dp!
     * All UI elements (icons, text, tabs) keep their huge 480dpi pixel size and get crushed
     * into a 140dp sliver, overlapping each other and making buttons unclickable.
     *
     * To achieve an "iPad-like phone app experience" where the app renders at standard
     * comfortable touch sizes:
     * We require W_dp >= 360dp (targeting ~380dp ~ 400dp).
     *
     * Therefore:
     * DPI <= (H * 0.5 * 160) / 360 = (H * 80) / 360 = H * 2 / 9 ≈ H / 4.5.
     *
     * For landscape (W >= H):
     * - H=1080/1020: max DPI ≈ 226 -> optimal DPI ≈ 200~220
     * - H=792/720: max DPI ≈ 160~176 -> optimal DPI ≈ 160
     * - H=600: max DPI ≈ 133 -> optimal DPI ≈ 120~130
     *
     * For portrait (W < H, rotatable car screen rotated to vertical):
     * The entire width W is available.
     * Optimal DPI = W * 160 / 380. (e.g. W=892 -> DPI ≈ 375).
     */
    fun calculateOptimalDpi(width: Int, height: Int, carReportedDpi: Int = 0): Int {
        if (width <= 0 || height <= 0) return DEFAULT_FALLBACK_DPI

        val isLandscape = width >= height
        val safeMax = maxSafeDpi(width, height)

        val goldenDpi = if (isLandscape) {
            ((height * 0.52f * 160f) / 385f).toInt()
        } else {
            ((width * 160f) / 385f).toInt()
        }

        val baseDpi = if (carReportedDpi in 120..320) {
            minOf(carReportedDpi, safeMax)
        } else {
            goldenDpi
        }

        return baseDpi.coerceIn(120, maxOf(120, safeMax))
    }

    /**
     * 本视口下 VD 密度的安全上限（issue #202）。
     *
     * 与 [calculateOptimalDpi] 里 `maxSafeDpi` 的算式**同一份**：横屏按高度
     * `H*80/360`（≈ H/4.5），竖屏按宽度 `W*160/380`。超过它，竖屏应用在横屏 VD 里
     * 只剩 ~140dp 逻辑宽，图标/按钮互相重叠到不可点（见 [calculateOptimalDpi] 的推导）。
     *
     * 抽成公开函数是为了让 [VdDpiResolver] 能对「用户 override」做同一道校验，
     * 而不是像 issue #202 现场那样让 `override=480` 无条件绕过上限。
     */
    fun maxSafeDpi(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return DEFAULT_FALLBACK_DPI
        val maxSafe = if (width >= height) {
            ((height * 80f) / 360f).toInt()
        } else {
            ((width * 160f) / 380f).toInt()
        }
        return maxSafe.coerceAtLeast(120)
    }
}
