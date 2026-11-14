package com.dilinkauto.desktop

import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdAreaTier
import com.dilinkauto.protocol.VideoConfig

/**
 * 桌面接收端的运行配置。
 *
 * 端口默认沿用协议常量（[Ports]）；测试里会替换成独立端口，避免与真实手机/车机冲突。
 */
data class DesktopConfig(
    /** 手机 IP（v1 先硬编码 / 由命令行传入）。 */
    val phoneHost: String,
    val controlPort: Int = Ports.DEFAULT_PORT,
    val videoPort: Int = Ports.VIDEO_PORT,
    val inputPort: Int = Ports.INPUT_PORT,
    /** 视口宽高（像素）。H.264 要求偶数，构造握手时会做偶数对齐。 */
    val viewportWidth: Int,
    val viewportHeight: Int,
    /** 报给手机的 DPI。桌面没有可靠物理 DPI，先给一个可配置值。 */
    val screenDpi: Int = DEFAULT_SCREEN_DPI,
    val targetFps: Int = VideoConfig.TARGET_FPS,
    val bitrate: Int = VideoConfig.DEFAULT_BITRATE,
    /** 0 = 手机自动标定 DPI；非 0 则强制使用（透传）。 */
    val dpiOverride: Int = 0,
    /**
     * VD 合成面积档位（issue #153 PB）。桌面端没有档位设置 UI，这里保留程序化
     * 逃生口：默认 [VdAreaTier.AUTO]，需要完整 IME 时可显式传 [VdAreaTier.COMPAT_2560]。
     */
    val vdAreaTier: Int = VdAreaTier.AUTO,
    /** 报给手机的"接收端版本号"，仅信息用；手机握手不据此做版本门控。 */
    val appVersionCode: Int = DESKTOP_APP_VERSION_CODE,
    val deviceName: String = DEFAULT_DEVICE_NAME,
    /**
     * 单个连接的建立超时（毫秒）。
     *
     * Phase 1a 联调发现问题：手机正在回收上一轮会话（9637 暂时未监听）时，
     * `Connection.connect` 会静默卡住且无任何输出，用户无法判断是在重试还是已死。
     * 超时后抛出带排查提示的异常。
     */
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_SCREEN_DPI = 160
        const val DESKTOP_APP_VERSION_CODE = 1
        const val DEFAULT_DEVICE_NAME = "DiLink-Desktop"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    }
}