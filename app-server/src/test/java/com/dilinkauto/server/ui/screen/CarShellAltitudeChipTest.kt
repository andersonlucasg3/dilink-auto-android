package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #50 的源码级回归钉子：信息栏**不再渲染海拔**。
 *
 * 现场证据（2026-11-15 17:21–17:28，车机侧 GPS）：`alt=-17.6 vAcc=none`，
 * 与用户实测海拔差 22.6m；车机不给垂直精度，精度闸门在这条路径上形同不存在，
 * BYD 厂商接口（BYD-dev）也没有海拔/气压来源。结论：没有可信来源就不上屏。
 *
 * 采集层 [com.dilinkauto.server.service.AltitudeProvider] 保留（继续打 `[Altitude]`
 * 诊断日志），所以这里只钉 UI 层不再消费 `altitudeState`，不钉 provider 的存废。
 *
 * app-server 没有 Compose UI 测试依赖，沿用 [CarLaunchScreenReconnectTest] 的
 * 源码文本断言范式。
 */
class CarShellAltitudeChipTest {

    private val shellSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/ui/screen/CarShell.kt")
    private val providerSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/AltitudeProvider.kt")

    @Test
    fun `info bar no longer renders the altitude chip`() {
        assertFalse(
            "CarShell 不得再调用 AltitudeFormat.formatAltitude 生成 chip（issue #50：车机 GPS 无垂直精度）",
            shellSrc.contains("AltitudeFormat.formatAltitude"),
        )
        assertFalse(
            "CarShell 不得再消费 service.altitudeState（采集保留、上屏移除）",
            shellSrc.contains("service.altitudeState"),
        )
    }

    @Test
    fun `altitude provider keeps collecting for future trustworthy sources`() {
        assertTrue(
            "AltitudeProvider 必须保留采集与 [Altitude] 诊断日志，将来有可信来源可直接恢复渲染",
            providerSrc.contains("[Altitude]"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }
}
