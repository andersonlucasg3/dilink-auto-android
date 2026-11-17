package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #203 的源码级回归钉子：帧率档位**不得**按静态结论禁用，设置页必须展示实测值。
 *
 * 评审结论（PR #211 REQUEST CHANGES）：A 方案（`Surface.setFrameRate`）的目的就是让
 * 30fps 真能跑，若在 A 未实车验证前就把 30/60 灰掉，`refreshRate=30` 永远不会发生，
 * A 也永远没有验证机会。所以这里钉住两件事：
 *  1. 三个预设档位都可点（没有任何禁用机制、也没有「不可选」文案）；
 *  2. 设置页把解码器**实测**帧率（[com.dilinkauto.server.decoder.VideoDecoder.measuredFps]）
 *     显示出来，让用户自己看到「设了 30 实际拿到多少」。
 *
 * app-server 没有 Compose UI 测试依赖，因此与 [CarLaunchScreenScrollTest] 同一套
 * 源码文本断言范式。
 */
class CarLaunchFpsGatingTest {

    private val screenPath = "app-server/src/main/java/com/dilinkauto/server/ui/screen/CarLaunchScreen.kt"
    private val settingsPath = "app-server/src/main/java/com/dilinkauto/server/ui/screen/CarLaunchSettings.kt"
    private val decoderPath = "app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt"
    private val screenSrc = sourceOf(screenPath)

    private val fpsSection: String by lazy {
        screenSrc.substringAfter("// FPS Settings & Presets").substringBefore("// VD 合成面积档位")
    }

    @Test
    fun `fps presets are never disabled`() {
        assertFalse(
            "取消静态置灰：30/60 是否可达由实车回签判定，不能提前禁用",
            fpsSection.contains("disabledPresets"),
        )
        assertFalse(
            "不得再写「暂不可选」之类的结论文案",
            fpsSection.contains("hint_limited"),
        )
        assertTrue("30fps 档必须仍在预设列表里", fpsSection.contains("preset_fps_30"))
        assertTrue("60fps 档必须仍在预设列表里", fpsSection.contains("preset_fps_60"))
    }

    @Test
    fun `SettingSection has no disable mechanism for presets`() {
        val settingsSrc = sourceOf(settingsPath)
        assertFalse(
            "SettingSection 不应保留 disabledPresets 参数（删干净，免得后人再接回去）",
            settingsSrc.contains("disabledPresets"),
        )
        assertFalse("预设按钮不得被 enabled 条件拦住", settingsSrc.contains("enabled = !isDisabled"))
    }

    @Test
    fun `settings page shows the measured fps`() {
        assertTrue(
            "设置页必须消费解码器实测帧率",
            screenSrc.contains("service.measuredFps.collectAsState()"),
        )
        assertTrue(
            "实测值必须传进设置卡片",
            screenSrc.contains("measuredFps = measuredFps"),
        )
        assertTrue(
            "有样本时必须显示实测帧率（而不是只显示目标值）",
            fpsSection.contains("R.string.video_fps_hint_measured"),
        )
    }

    @Test
    fun `measured fps is always on in the decoder`() {
        val decoderSrc = sourceOf(decoderPath)
        assertTrue(
            "实测帧率必须始终开启（不受 debugFrameStats 制约），否则 release 包里设置页永远没有数据",
            decoderSrc.contains("var measuredFps: Double"),
        )
        assertTrue(
            "窗口必须按墙钟差折算（30 帧 / 实际耗时），不能按目标 fps 反推",
            decoderSrc.contains("30_000.0 / elapsedMs"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }

    private fun sourceOf(relativePath: String): String = sourceFile(relativePath).readText()
}
