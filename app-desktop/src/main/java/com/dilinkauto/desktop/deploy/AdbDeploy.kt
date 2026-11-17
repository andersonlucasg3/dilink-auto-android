package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.desktop.HandshakeFactory
import com.dilinkauto.protocol.DimAlign
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VideoConfig
import java.io.IOException

/**
 * ADB 部署路径的纯命令拼装（Phase 5c）。
 *
 * 车机端走的是自己实现的 `TcpAdbConnection`（要自签 ADB 密钥、自己做协议握手）；
 * Windows 上直接调 `adb.exe` 省掉这一整块 —— 这里只负责把 `DesktopConfig`
 * 翻译成 `adb` 的 argv，进程怎么跑交给 [AdbRunner]。
 *
 * 与 `VdDeploy` 的分工：参数与 `CLASSPATH=... app_process ...` 那行的形状由
 * `VdDeploy.buildDeployPlan` 单点决定（三端共用），本对象只补 adb 特有的
 * `connect` / `-s <serial> shell` 外壳。
 */
object AdbDeploy {

    /**
     * VD 尺寸的合法区间（audit D-M4 / D-01）。
     *
     * 下限 2 = H.264 编码器能接受的最小偶数边长（[DimAlign.evenMin2] 的下限）。
     * 上限 4096：够覆盖任何手机的物理长边（含 foldable 展开态），再大就是对端在
     * 要内存 —— VirtualDisplay 是按 vdWidth×vdHeight×4 分配的**设备侧**画布。
     */
    const val MIN_VD_DIM = 2
    const val MAX_VD_DIM = 4096

    /** adb 里的设备号（TCP 连接时就是 `host:port`）。 */
    fun serial(phoneHost: String, adbPort: Int = Ports.ADB_PORT): String = "$phoneHost:$adbPort"

    // ─── 对端下发字段的白名单校验（audit D-01，CRITICAL）────

    /**
     * 校验手机握手响应里下发的 adb 端口。
     *
     * 合法值只有一个：[Ports.ADB_PORT]。端口会拼进 `adb connect <host>:<port>` 与
     * `adb -s host:port shell ...`，对端（被 MITM / 占位的 LAN 主机）写任意值就能
     * 让桌面端去连一个它选定的端口 —— 那是"打哪台机器的哪个服务"，不是本功能需要的
     * 自由度。手机侧实际下发的也永远是 [Ports.ADB_PORT]（`app-client` 的
     * `ConnectionService.handleHandshake` 写死），所以收窄没有兼容代价。
     *
     * @throws IOException 端口不是 [Ports.ADB_PORT] 时由上层转成会话错误
     *   （必须让用户看到"为什么连不上"，而不是静默换个端口继续）。
     */
    fun checkAdbPort(port: Int) {
        if (port != Ports.ADB_PORT) {
            throw IOException(
                "手机下发的 adb 端口非法：$port（本端只连接 ${Ports.ADB_PORT}）—— " +
                    "会话已终止以防连到非预期的 adb 服务",
            )
        }
    }

    /**
     * 校验手机握手响应里下发的 VD server jar 路径（audit D-01）。
     *
     * 这个字符串会原样进入设备侧 `CLASSPATH=<path> exec app_process ...`，执行身份是
     * shell —— 等于"对端点哪段代码就跑哪段代码"。[VdDeploy.shellQuote] 只保证它
     * **逃不出这条命令**（不会注入额外命令），防不住"选择跑什么"，所以必须白名单：
     *  - 空 / 纯空白 → 退回 [VdDeploy.JAR_PATH]（本端自己落盘的那份）；
     *  - 等于 [VdDeploy.JAR_PATH]，或以 `VdDeploy.DIR_PATH + "/"` 开头
     *    （同目录下的其它文件名，兼容手机侧改名的少数 ROM）。
     *
     * 其它一律拒绝。注意白名单**不是**为了防注入而是为了防"选代码"：`..` 穿越也会
     * 被 `startsWith` 放行，所以额外排除含 `..` 的路径。
     *
     * @return 可直接使用的 jar 路径（空输入时是 [VdDeploy.JAR_PATH]）
     * @throws IOException 路径不在白名单内
     */
    fun sanitizeJarPath(raw: String): String {
        val path = raw.trim()
        if (path.isEmpty()) return VdDeploy.JAR_PATH
        val allowed = path == VdDeploy.JAR_PATH || (path.startsWith(VdDeploy.DIR_PATH + "/") && !path.contains(".."))
        if (!allowed) {
            throw IOException(
                "手机下发的 VD server 路径不在白名单内：\"$path\"（只允许 " +
                    "${VdDeploy.DIR_PATH}/ 下的文件）—— 会话已终止以防在设备上执行非预期的代码",
            )
        }
        return path
    }

    /** `adb connect <host>:<port>`。 */
    fun connectArgs(phoneHost: String, adbPort: Int = Ports.ADB_PORT): List<String> =
        listOf("connect", serial(phoneHost, adbPort))

    /** 在指定设备上跑一条 shell 命令（整条命令作为单个 argv，交给设备侧 shell 解析）。 */
    fun shellArgs(serial: String, command: String): List<String> =
        listOf("-s", serial, "shell", command)

    /**
     * 对端下发的 VD 尺寸做上界钳制（audit D-M4 / D-01）。
     *
     * [DimAlign.even] 对负数是 `value and 0x7FFFFFFE`（把符号位也清掉）→ 得到约
     * 2^31 的**正**尺寸，敌意/故障对端就能让手机申请任意大的 VirtualDisplay
     * （设备侧 OOM）。所以必须换 [DimAlign.evenMin2]（0/1/负数一律 2）再夹到
     * [MAX_VD_DIM]。
     */
    fun clampVdDimension(value: Int): Int =
        DimAlign.evenMin2(value).coerceIn(MIN_VD_DIM, MAX_VD_DIM)

    /**
     * 构造 VD server 的部署计划。
     *
     * **VD 尺寸 ≠ 编码尺寸**：编码尺寸（encodeWidth/Height）用本端视口（H.264
     * 偶数对齐后），这是流的真实分辨率；而 VirtualDisplay 尺寸必须用手机侧
     * 握手响应下发的 [phoneVdWidth]/[phoneVdHeight]（`VdDimensions.compute`
     * 的输出——车机视口按手机真实物理长边放大的结果，防 Chinese-ROM IME 把
     * 键盘按物理像素宽渲染到更窄的画布上）。2026-10-09 实锤：按视口 1280x720
     * 建 VD 时，微信键道键盘行宽 1368px 被切 88px，且与 DPI 无关。两个字段
     * 为 0（旧版手机不下发）时退回视口尺寸，保持旧行为。
     * `phoneHost` 固定 127.0.0.1：VD server 与手机 app 同机，推流目标就是本机
     * 回环（与车机端 `VdServerDeployer` 一致）。
     *
     * `background = false` 是硬约束：只有 `exec app_process` 才能让 adb shell 流
     * 在引擎存活期间保持附着；`&` 后台化会被 adbd 回收，表现为日志 0 字节、进程秒死。
     *
     * @param jarPath 用握手响应里的 `vdServerJarPath`（手机侧刚校验过 CRC 的那份）；
     *   空则退回 `VdDeploy.JAR_PATH`。**调用方必须先过白名单**
     *   （[sanitizeJarPath]，audit D-01）—— 这个字符串会进设备侧的 shell。
     * @param phoneVdWidth 手机下发的 VD 宽；会经 [clampVdDimension] 钳制
     *   （audit D-M4）。0/负值 = 未下发，退回视口尺寸。
     * @param phoneVdHeight 同上，高。
     */
    fun plan(
        config: DesktopConfig,
        jarPath: String = VdDeploy.JAR_PATH,
        phoneVdWidth: Int = 0,
        phoneVdHeight: Int = 0,
    ): VdDeploy.DeployPlan {
        val encodeWidth = HandshakeFactory.evenAlign(config.viewportWidth)
        val encodeHeight = HandshakeFactory.evenAlign(config.viewportHeight)
        // 手机下发的 VD 尺寸优先（防 IME 裁切）；0/负值时退回视口。
        // 对端可控 → 必须钳到 [MIN_VD_DIM]..[MAX_VD_DIM]（audit D-M4 / D-01）：
        // DimAlign.even 会把负数映射成 ~2^31 的正数，见 [clampVdDimension]。
        val vdWidth = if (phoneVdWidth > 0) clampVdDimension(phoneVdWidth) else encodeWidth
        val vdHeight = if (phoneVdHeight > 0) clampVdDimension(phoneVdHeight) else encodeHeight
        return VdDeploy.buildDeployPlan(
            jarPath = jarPath.ifBlank { VdDeploy.JAR_PATH },
            logPath = VdDeploy.LOG_PATH,
            vdWidth = vdWidth,
            vdHeight = vdHeight,
            dpi = resolveDpi(config, vdWidth, vdHeight),
            encodeWidth = encodeWidth,
            encodeHeight = encodeHeight,
            phoneHost = "127.0.0.1",
            fps = config.targetFps,
            bitrate = config.bitrate,
            background = false,
        )
    }

    /**
     * 本次部署用的 DPI。
     *
     * 握手里的 `dpiOverride` 优先，且**按协议区间夹紧**（[VdDeployArgs.coerceDpiOverride]）：
     * 桌面端以前直通不夹紧，同一个 UI 值在 Shizuku 路径落到 480、在 ADB 路径原样发 640
     * （audit WIN-08）。为 0（自动）时用 [VideoConfig.calculateOptimalDpi] —— 与手机侧
     * `VdDimensions` 的自动标定同一个函数，而不是直接拿 `screenDpi` 顶上去。
     */
    fun resolveDpi(config: DesktopConfig, vdWidth: Int, vdHeight: Int): Int {
        val override = VdDeployArgs.coerceDpiOverride(config.dpiOverride)
        return if (override > 0) {
            override
        } else {
            VideoConfig.calculateOptimalDpi(vdWidth, vdHeight, config.screenDpi)
        }
    }
}
