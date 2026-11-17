package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [AdbDeploy] 纯命令拼装单测。
 *
 * 这些断言锁定的都是**线上事故的直接原因**，改错会静默失效（引擎起来了但日志 0 字节、
 * 或者两个引擎抢 VD），所以逐条钉住文本形状而不是只测"不为空"。
 */
class AdbDeployTest {

    private fun config(
        width: Int = 1280,
        height: Int = 720,
        dpiOverride: Int = 0,
        screenDpi: Int = 160,
    ) = DesktopConfig(
        phoneHost = "192.168.3.206",
        viewportWidth = width,
        viewportHeight = height,
        screenDpi = screenDpi,
        dpiOverride = dpiOverride,
    )

    @Test
    fun `serial 默认拼标准 adb 端口`() {
        assertEquals("192.168.3.206:${Ports.ADB_PORT}", AdbDeploy.serial("192.168.3.206"))
        assertEquals("10.0.0.5:4321", AdbDeploy.serial("10.0.0.5", 4321))
    }

    @Test
    fun `connectArgs 是 connect 加设备号`() {
        assertEquals(listOf("connect", "192.168.3.206:5555"), AdbDeploy.connectArgs("192.168.3.206"))
        assertEquals(listOf("connect", "192.168.3.206:4321"), AdbDeploy.connectArgs("192.168.3.206", 4321))
    }

    @Test
    fun `shellArgs 把整条命令作为单个参数交给设备 shell`() {
        // 整条命令必须是一个 argv：拆开会被本地 shell 先解析，重定向/分号会落到 PC 上
        val args = AdbDeploy.shellArgs("192.168.3.206:5555", "pkill -f [P]ipelineServer 2>/dev/null")
        assertEquals(listOf("-s", "192.168.3.206:5555", "shell", "pkill -f [P]ipelineServer 2>/dev/null"), args)
    }

    @Test
    fun `resolveDpi override 为正时优先用它`() {
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 160), 1280, 720))
        assertEquals(240, AdbDeploy.resolveDpi(config(dpiOverride = 240, screenDpi = 160), 1280, 720))
        // 负数等同自动（与手机侧 coerceDpiOverride 同语义）
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = -1, screenDpi = 160), 1280, 720))
    }

    /**
     * WIN-08：overrides 必须按协议区间（[VdDeployArgs] 的 120..480）夹紧。
     * 此前直通不夹紧 —— 同一个 UI 值在 Shizuku 路径落到 480、在 ADB 路径原样发 640。
     */
    @Test
    fun `resolveDpi 把 override 夹进协议区间`() {
        assertEquals(
            VdDeployArgs.DPI_OVERRIDE_MAX,
            AdbDeploy.resolveDpi(config(dpiOverride = 640), 1280, 720),
        )
        assertEquals(
            VdDeployArgs.DPI_OVERRIDE_MIN,
            AdbDeploy.resolveDpi(config(dpiOverride = 50), 1280, 720),
        )
    }

    /**
     * WIN-08：自动（0）时用 [VideoConfig.calculateOptimalDpi] 按视口标定，
     * 而不是把 `screenDpi` 原样顶上去 —— 与手机侧 `VdDimensions` 同一个函数。
     */
    @Test
    fun `resolveDpi 自动时按视口标定`() {
        // 1280x720 横屏、上报 160 → 落在 120..320 内取 min(160, maxSafe=160)
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 160), 1280, 720))
        // 1920x1080 且手机没报 DPI → 黄金比例 (1080*0.52*160)/385 ≈ 233
        assertEquals(233, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 0), 1920, 1080))
    }

    @Test
    fun `plan 用偶数对齐后的视口并把背景参数固定为前台`() {
        // 奇数边长会被 H.264 编码器拒绝，plan 里必须已经对齐过
        val plan = AdbDeploy.plan(config(width = 1281, height = 721))

        assertTrue("视口未偶数对齐: ${plan.args}", plan.args.startsWith("1280 720 "))
        // 停止命令不在 plan 里（由 vdRunDeploySequence 统一走哨兵文件 + -9 兜底）
        assertTrue("应以 exec 前台启动: ${plan.launchCommand}", plan.launchCommand.contains("exec app_process"))
        // 注意不能简单查 "&"：日志重定向 2>&1 本身就含 &，要查后台化后缀 " &"
        assertFalse("不能后台化（行尾不能有 &）: ${plan.launchCommand}", plan.launchCommand.trimEnd().endsWith("&"))
        assertFalse("不能带 setsid: ${plan.launchCommand}", plan.launchCommand.contains("setsid"))
    }

    @Test
    fun `plan 固定回环推流地址与默认 jar 路径`() {
        val plan = AdbDeploy.plan(config())

        // VD server 与手机 app 同机，推流目标是回环（与车机端一致）
        assertTrue("推流地址应为 127.0.0.1: ${plan.args}", plan.args.contains(" 127.0.0.1 "))
        // 路径一律 shell 引用（WIN-09）
        assertTrue(
            "应使用默认 jar（且被引用）: ${plan.launchCommand}",
            plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(VdDeploy.JAR_PATH)}"),
        )
        assertTrue(
            "日志应指向设备侧（且被引用）: ${plan.launchCommand}",
            plan.launchCommand.contains(VdDeploy.shellQuote(VdDeploy.LOG_PATH)),
        )
        assertTrue("应含主类: ${plan.launchCommand}", plan.launchCommand.contains(VdDeploy.MAIN_CLASS))
    }

    @Test
    fun `plan 用握手响应里的 jar 路径`() {
        val jar = "/sdcard/DiLinkAuto/vd-server.jar"
        val plan = AdbDeploy.plan(config(), jarPath = jar)
        assertTrue(plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(jar)}"))
    }

    @Test
    fun `plan 对空 jar 路径回退到默认`() {
        val plan = AdbDeploy.plan(config(), jarPath = "   ")
        assertTrue(plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(VdDeploy.JAR_PATH)}"))
    }

    /**
     * 2026-10-09 IME 裁切实锤：VD 尺寸必须用手机握手响应下发的值（VdDimensions
     * 按手机真实物理长边放大的结果），本端视口只是编码尺寸。此前按视口 1280x720
     * 建 VD，微信键道键盘行（硬编码手机物理宽 1368px）被切 88px，且与 DPI 无关。
     */
    @Test
    fun `plan 手机下发 VD 尺寸时命令行头部采用之而编码尺寸保持视口`() {
        val plan = AdbDeploy.plan(config(width = 1280, height = 720), phoneVdWidth = 3192, phoneVdHeight = 1794)

        // argv 头两位 = VD 尺寸（VirtualDisplayCreator 用），必须不是视口
        assertTrue("VD 尺寸应为手机下发值: ${plan.args}", plan.args.startsWith("3192 1794 "))
        // 编码尺寸仍是本端视口（argv 第 5/6 位）：VD=3192x1794 encode=1280x720
        val parts = plan.args.split(" ")
        assertEquals("1280", parts[4])
        assertEquals("720", parts[5])
    }

    @Test
    fun `plan 手机 VD 尺寸为奇数时偶数对齐`() {
        val plan = AdbDeploy.plan(config(), phoneVdWidth = 3193, phoneVdHeight = 1795)
        assertTrue("应偶数对齐: ${plan.args}", plan.args.startsWith("3192 1794 "))
    }

    @Test
    fun `plan 手机未下发 VD 尺寸时回退视口`() {
        // 旧版手机（trailing 字段缺失解码为 0）→ 保持旧行为
        val plan = AdbDeploy.plan(config(width = 1280, height = 720), phoneVdWidth = 0, phoneVdHeight = 0)
        assertTrue("应回退视口: ${plan.args}", plan.args.startsWith("1280 720 "))
    }

    // ─── D-M4：对端 VD 尺寸无上界（DimAlign.even 把负数映射到 ~2^31）────

    @Test
    fun `clampVdDimension 把越界尺寸夹进 2 到 4096`() {
        assertEquals(2, AdbDeploy.clampVdDimension(0))
        assertEquals(2, AdbDeploy.clampVdDimension(1))
        // 关键：DimAlign.even(-1) = 2147483646（符号位被清掉）—— 敌意对端的放大器
        assertEquals(2, AdbDeploy.clampVdDimension(-1))
        assertEquals(2, AdbDeploy.clampVdDimension(-9999))
        assertEquals(2, AdbDeploy.clampVdDimension(2))
        assertEquals(4096, AdbDeploy.clampVdDimension(4096))
        assertEquals(4096, AdbDeploy.clampVdDimension(99999))
        assertEquals(4096, AdbDeploy.clampVdDimension(Int.MAX_VALUE))
        // 奇数向下取偶后在区间内
        assertEquals(4094, AdbDeploy.clampVdDimension(4095))
        assertEquals(1280, AdbDeploy.clampVdDimension(1281))
    }

    @Test
    fun `plan 对敌意 VD 尺寸夹到上限而不是原样下发`() {
        // 手机侧 VdDimensions.compute 的输出被伪造/出错时，不能在设备上申请
        // 任意大的 VirtualDisplay（设备 OOM）—— 夹到 4096。
        val plan = AdbDeploy.plan(config(width = 1280, height = 720), phoneVdWidth = 99999, phoneVdHeight = Int.MAX_VALUE)
        assertTrue("VD 尺寸应被夹住: ${plan.args}", plan.args.startsWith("4096 4096 "))
    }

    @Test
    fun `plan 负数 VD 尺寸被当作未下发退回视口`() {
        // phoneVdWidth <= 0 的语义是"旧版手机未下发"，走视口而不是 evenMin2 的 2
        val plan = AdbDeploy.plan(config(width = 1280, height = 720), phoneVdWidth = -1, phoneVdHeight = -1)
        assertTrue("负数应按未下发处理: ${plan.args}", plan.args.startsWith("1280 720 "))
    }

    // ─── D-01：握手响应里的 adbPort / vdServerJarPath 白名单 ───

    @Test
    fun `checkAdbPort 只接受标准 adb 端口`() {
        // 手机侧（app-client handleHandshake）写死 Ports.ADB_PORT，所以收窄无兼容代价
        AdbDeploy.checkAdbPort(Ports.ADB_PORT)
        for (bad in listOf(0, 1, 5554, 5556, 5037, -1, 65536, Int.MAX_VALUE)) {
            try {
                AdbDeploy.checkAdbPort(bad)
                fail("端口 $bad 应被拒绝")
            } catch (e: IOException) {
                assertTrue("拒绝原因应可读: ${e.message}", e.message!!.contains("adb 端口"))
            }
        }
    }

    @Test
    fun `sanitizeJarPath 空白回退默认路径`() {
        assertEquals(VdDeploy.JAR_PATH, AdbDeploy.sanitizeJarPath(""))
        assertEquals(VdDeploy.JAR_PATH, AdbDeploy.sanitizeJarPath("   "))
    }

    @Test
    fun `sanitizeJarPath 接受默认路径与同目录文件`() {
        assertEquals(VdDeploy.JAR_PATH, AdbDeploy.sanitizeJarPath(VdDeploy.JAR_PATH))
        assertEquals(
            "${VdDeploy.DIR_PATH}/vd-server-2.jar",
            AdbDeploy.sanitizeJarPath("${VdDeploy.DIR_PATH}/vd-server-2.jar"),
        )
        // 前后空白无所谓（协议上同一条字符串，手机侧可能带换行符）
        assertEquals(
            VdDeploy.JAR_PATH,
            AdbDeploy.sanitizeJarPath("  ${VdDeploy.JAR_PATH}\n"),
        )
    }

    @Test
    fun `sanitizeJarPath 拒绝白名单外的路径`() {
        val bad = listOf(
            "/data/local/tmp/evil.jar",              // 任意代码
            "/sdcard/evil.jar",                      // 同盘不同目录
            "${VdDeploy.DIR_PATH}../evil.jar",       // 目录穿越（前缀看似合法）
            "${VdDeploy.DIR_PATH}/../../data/x.jar", // 深层穿越
            "vd-server.jar",                         // 相对路径
            "/sdcard/DiLinkAutoX/vd-server.jar",     // 前缀不匹配（少了结尾斜杠的边界）
        )
        for (path in bad) {
            try {
                AdbDeploy.sanitizeJarPath(path)
                fail("路径应被拒绝: $path")
            } catch (e: IOException) {
                assertTrue("拒绝原因应可读（$path）: ${e.message}", e.message!!.contains("白名单"))
            }
        }
    }

    @Test
    fun `plan 收到的 jar 路径必须已过白名单`() {
        // D-01 的分工：白名单在 AdbDeploy.sanitizeJarPath（调用点 = DesktopApp /
        // ProbeRunner），plan 只负责把已校验的路径拼进 CLASSPATH。
        val plan = AdbDeploy.plan(config(), jarPath = AdbDeploy.sanitizeJarPath("/sdcard/DiLinkAuto/x.jar"))
        assertTrue(
            "CLASSPATH 应是校验后的路径: ${plan.launchCommand}",
            plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote("/sdcard/DiLinkAuto/x.jar")}"),
        )
    }
}
