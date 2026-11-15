package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #173 PA 的源码级回归钉子：车机启动页的**窄屏内容列必须可滚动**。
 *
 * 缺陷形态（S19 真机实证）：MI 9 竖屏 1080x2152 上，Branding + statusCard
 * （3 个 SettingSection + 3 个 Switch）+ [WifiAdbSetupCard] 已占满到 y≈2048，
 * 排在最后的 HowToConnect / ManualConnectBox 恒被挤出屏幕外，而整页没有滚动容器
 * ——于是 `scripts/lib/ops.sh` 的 `op_connect` 永远找不到「连接」按钮，S01 setup 必失败。
 *
 * 刻意做成源码文本级断言：app-server 没有 Compose UI 测试依赖（无 ui-test-junit4 /
 * Robolectric / androidTest），本测试不渲染任何 Composable、不依赖 Android 运行时，
 * 可在纯 JVM 的 `:app-server:testDebugUnitTest` 离线运行。仿
 * [SyncFrameRequestGateSourceTest] 的 sourceOf 范式。
 *
 * **验证边界**：只能钉「源码确实挂了滚动 + 键盘 inset + 卡片仍在」；真实像素可见性、
 * 软键盘是否仍遮挡、InfoBar 是否被卷走，必须真机 uiautomator 复核（回签项，见 #173）。
 */
class CarLaunchScreenScrollTest {

    private val path = "app-server/src/main/java/com/dilinkauto/server/ui/screen/CarLaunchScreen.kt"
    private val src = sourceOf(path)

    private val wide: String by lazy {
        src.substringAfter("Two-column layout for wide screens")
            .substringBefore("Single-column layout for narrow screens")
    }
    private val narrow: String by lazy {
        src.substringAfter("Single-column layout for narrow screens")
    }

    @Test
    fun `narrow content column is vertically scrollable`() {
        assertTrue(
            "窄屏内容列必须挂 .verticalScroll(rememberScrollState())，否则竖屏超高时 ManualConnectBox 恒被裁掉",
            narrow.contains(".verticalScroll(rememberScrollState())"),
        )
        assertTrue(
            "必须真的 import 了 verticalScroll / rememberScrollState（不是只写了注释）",
            src.contains("import androidx.compose.foundation.verticalScroll") &&
                src.contains("import androidx.compose.foundation.rememberScrollState"),
        )
    }

    @Test
    fun `narrow content column keeps the connect button reachable above the IME`() {
        assertTrue(
            "窄屏内容列必须挂 .imePadding()，否则软键盘弹出时「连接」按钮仍可能不可达",
            narrow.contains(".imePadding()"),
        )
        assertTrue(
            "必须真的 import 了 imePadding",
            src.contains("import androidx.compose.foundation.layout.imePadding"),
        )
    }

    @Test
    fun `long content starts from the top instead of being center-clipped`() {
        // 只看主内容 BoxWithConstraints：同文件后面别的 Composable 也有
        // contentAlignment = Alignment.Center（与本修复无关），不能扫全文件。
        val box = src.substringAfter("BoxWithConstraints(").substringBefore("val isWide")
        assertTrue(
            "主内容 BoxWithConstraints 的 contentAlignment 必须是 TopCenter（居中会把长列表首尾裁掉）",
            box.contains("contentAlignment = Alignment.TopCenter"),
        )
        assertFalse(
            "主内容 BoxWithConstraints 不得保留旧的 contentAlignment = Alignment.Center",
            box.contains("contentAlignment = Alignment.Center"),
        )
    }

    @Test
    fun `both layouts still render exactly one ManualConnectBox each`() {
        assertEquals(
            "宽屏 / 窄屏两处 ManualConnectBox 必须都在（修滚动时不能把卡片删掉）",
            2,
            Regex("""ManualConnectBox\(""").findAll(src).count(),
        )
        assertTrue("宽屏分支仍渲染 ManualConnectBox", wide.contains("ManualConnectBox("))
        assertTrue("窄屏分支仍渲染 ManualConnectBox", narrow.contains("ManualConnectBox("))
        assertTrue(
            "窄屏里 ManualConnectBox 必须排在 HowToConnect 之后（卡片顺序不能倒置）",
            narrow.indexOf("ManualConnectBox(") > narrow.indexOf("HowToConnect()"),
        )
    }

    @Test
    fun `connection cards keep the non-streaming gate`() {
        assertTrue(
            "ManualConnectBox 仍须受 state != STREAMING/CONNECTED 门控",
            src.contains("state != CarConnectionService.State.STREAMING") &&
                src.contains("state != CarConnectionService.State.CONNECTED"),
        )
    }

    @Test
    fun `wide two-column layout is not pulled into an unbounded scroll`() {
        // Row(fillMaxSize) + Column(weight(1f)) + CenterVertically 与无限高滚动约束耦合；
        // issue #173 的实证阻塞只在竖屏，无真机验证前不动横屏主形态。
        assertFalse(
            "宽屏分支不应引入 verticalScroll（会把产品横屏主形态变成无界测量）",
            wide.contains("verticalScroll("),
        )
        assertEquals(
            "整文件应只有窄屏一处 verticalScroll",
            1,
            Regex("""\.verticalScroll\(""").findAll(src).count(),
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
