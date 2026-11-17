package com.dilinkauto.desktop.config

import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [DesktopSettings] 单测：默认值、文件往返（经 [DesktopSettingsStore]）、容错与区间夹紧。 */
class DesktopSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaults_matchProtocolConstants() {
        val s = DesktopSettings()
        assertEquals("", s.devPhoneIp)
        assertFalse(s.devMode)
        assertEquals(0, s.startupDpi)
        assertEquals(VideoConfig.TARGET_FPS, s.startupFps)
        assertEquals(VideoConfig.DEFAULT_BITRATE, s.startupBitrate)
        assertTrue(s.logEnabled)
        assertFalse("硬解默认关（首次跑更稳妥）", s.startupHwaccel)
        assertTrue("默认保持本机常亮", s.keepAwake)
    }

    @Test
    fun save_thenLoad_roundTrips() {
        val file = temp.newFile("config.json")
        val original = DesktopSettings(
            devPhoneIp = "192.168.3.206",
            devMode = true,
            startupDpi = 240,
            startupFps = 30,
            startupBitrate = 8_000_000,
            logEnabled = false,
            startupHwaccel = true,
            keepAwake = false,
        )

        assertTrue(DesktopSettingsStore(file).save(original))
        assertEquals(original, DesktopSettingsStore(file).load())
    }

    @Test
    fun load_missingFile_returnsDefaults() {
        val file = temp.newFile("nope.json").apply { delete() }
        assertEquals(DesktopSettings(), DesktopSettingsStore(file).load())
    }

    @Test
    fun load_corruptFile_reportsErrorAndReturnsDefaults() {
        val file = temp.newFile("config.json")
        file.writeText("{ this is not json")
        val errors = mutableListOf<String>()

        val settings = DesktopSettingsStore(file).load { errors.add(it) }

        assertEquals(DesktopSettings(), settings)
        assertTrue("应回报解析失败：$errors", errors.single().contains("解析"))
    }

    /**
     * D-L6：损坏的 config.json 必须**自愈**。
     *
     * 旧实现只是回报一行错误然后回默认值 —— 文件一直坏在那里，之后每次启动都静默
     * 回默认（用户发现 `dev_phone_ip` 丢了却不知道原因）。现在解析失败即用默认值
     * 重写文件，让它恢复可编辑状态。
     */
    @Test
    fun load_corruptFile_rewritesFileSoItSelfHeals() {
        val file = temp.newFile("config.json")
        file.writeText("{ this is not json")
        val errors = mutableListOf<String>()

        val settings = DesktopSettingsStore(file).load { errors.add(it) }

        assertEquals(DesktopSettings(), settings)
        assertTrue("应回报解析失败：$errors", errors.any { it.contains("解析") })
        // 文件被重写成合法配置：再读一次拿回默认值且不再报错
        val rereadErrors = mutableListOf<String>()
        assertEquals(DesktopSettings(), DesktopSettingsStore(file).load { rereadErrors.add(it) })
        assertTrue("自愈后不应再有解析错误: $rereadErrors", rereadErrors.isEmpty())
    }

    /** D-L6 的另一半：自愈后的文件必须还能正常保存/读回（不能留下半残状态）。 */
    @Test
    fun load_corruptFile_rewriteKeepsFileWritable() {
        val file = temp.newFile("config.json")
        file.writeText("not json at all")
        DesktopSettingsStore(file).load()

        assertTrue(DesktopSettingsStore(file).save(DesktopSettings(devPhoneIp = "10.0.0.9")))
        assertEquals("10.0.0.9", DesktopSettingsStore(file).load().devPhoneIp)
    }

    /**
     * D-02：保存必须是原子写（tmp + Files.move）。
     *
     * 直接 file.writeText 是原地截断读：并发保存交错时另一方会读到半截文件，
     * 用户拿到一份损坏的 config.json。这里断言三件事：
     *  1. 保存成功后目标文件存在且内容正确；
     *  2. **不留** `.tmp` 残留（泄漏的 tmp 会让目录越来越乱）；
     *  3. 连续多次保存（模拟两次快速修改）后文件仍是合法配置。
     */
    @Test
    fun save_isAtomic_andLeavesNoTmpBehind() {
        val file = temp.newFile("config.json")
        val store = DesktopSettingsStore(file)

        assertTrue(store.save(DesktopSettings(devPhoneIp = "1.1.1.1")))
        assertTrue(store.save(DesktopSettings(devPhoneIp = "2.2.2.2")))

        assertEquals("2.2.2.2", DesktopSettingsStore(file).load().devPhoneIp)
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        assertFalse("atomic write must not leave .tmp behind", tmp.exists())
    }

    /** 原子写失败时也要清理 tmp，并且回报原因。 */
    @Test
    fun save_cleansUpTmpWhenMoveFails() {
        val dir = temp.newFolder("blocked2")
        val file = java.io.File(dir, "sub/config.json")
        // 用一个文件占住父目录位置：mkdirs 失败、tmp 也写不进去
        java.io.File(dir, "sub").writeText("occupied")
        val errors = mutableListOf<String>()

        val ok = DesktopSettingsStore(file).save(DesktopSettings()) { errors.add(it) }

        assertFalse(ok)
        assertNotNull(errors.firstOrNull())
        assertFalse(
            "失败不应留 .tmp 残留",
            java.io.File(dir, "sub/config.json.tmp").exists(),
        )
    }

    @Test
    fun load_readsTheDocumentedKeysFromHandwrittenJson() {
        // 手写（而非 save 产出）的配置文件也必须能读——用户会直接编辑它
        val file = temp.newFile("config.json")
        file.writeText(
            """
            {
              "dev_phone_ip": "192.168.3.206",
              "dev_mode": true,
              "startup_dpi": 320,
              "startup_fps": 25,
              "startup_bitrate": 6000000,
              "log_enabled": false
            }
            """.trimIndent()
        )

        val s = DesktopSettingsStore(file).load()

        assertEquals("192.168.3.206", s.devPhoneIp)
        assertTrue(s.devMode)
        assertEquals(320, s.startupDpi)
        assertEquals(25, s.startupFps)
        assertEquals(6_000_000, s.startupBitrate)
        assertFalse(s.logEnabled)
    }

    @Test
    fun load_clampsOutOfRangeNumbers() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_DPI to 9999L,
                DesktopSettings.KEY_STARTUP_FPS to 500L,
                DesktopSettings.KEY_STARTUP_BITRATE to 1L,
            )
        )
        // DPI 按协议区间夹紧（WIN-08）：上限是 VdDeployArgs.DPI_OVERRIDE_MAX，不是本端自定的 640
        assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, s.startupDpi)
        assertEquals(60, s.startupFps)
        assertEquals(500_000, s.startupBitrate)
    }

    @Test
    fun load_wrongTypesFallBackToDefaults() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_DEV_PHONE_IP to 12345L, // 不是字符串
                DesktopSettings.KEY_DEV_MODE to "true", // 字符串不是布尔
                DesktopSettings.KEY_LOG_ENABLED to "yes",
                DesktopSettings.KEY_STARTUP_FPS to "abc",
            )
        )
        val defaults = DesktopSettings()
        assertEquals(defaults.devPhoneIp, s.devPhoneIp)
        assertEquals(defaults.devMode, s.devMode)
        assertEquals(defaults.logEnabled, s.logEnabled)
        assertEquals(defaults.startupFps, s.startupFps)
    }

    @Test
    fun from_acceptsNumericStringsForNumbers() {
        // 手写配置里把数字写成 "240" 很常见，容忍它
        val s = DesktopSettings.from(mapOf(DesktopSettings.KEY_STARTUP_DPI to "240"))
        assertEquals(240, s.startupDpi)
    }

    @Test
    fun save_reportsErrorForUnwritablePath() {
        val dir = temp.newFolder("blocked")
        val file = java.io.File(dir, "sub/config.json")
        // 用一个文件占住父目录位置，让 mkdirs 失败
        java.io.File(dir, "sub").writeText("occupied")
        val errors = mutableListOf<String>()

        val ok = DesktopSettingsStore(file).save(DesktopSettings()) { errors.add(it) }

        assertFalse(ok)
        assertNotNull(errors.firstOrNull())
    }

    @Test
    fun toJson_containsAllDocumentedKeys() {
        val json = DesktopSettings().toJson()
        for (key in listOf(
            DesktopSettings.KEY_DEV_PHONE_IP,
            DesktopSettings.KEY_DEV_MODE,
            DesktopSettings.KEY_STARTUP_DPI,
            DesktopSettings.KEY_STARTUP_FPS,
            DesktopSettings.KEY_STARTUP_BITRATE,
            DesktopSettings.KEY_LOG_ENABLED,
            DesktopSettings.KEY_STARTUP_HWACCEL,
            DesktopSettings.KEY_KEEP_AWAKE,
        )) {
            assertTrue("缺少键 $key", json.contains("\"$key\""))
        }
    }

    @Test
    fun from_readsHardwareAccelAndKeepAwakeKeys() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_HWACCEL to true,
                DesktopSettings.KEY_KEEP_AWAKE to false,
            )
        )
        assertTrue(s.startupHwaccel)
        assertFalse(s.keepAwake)
    }

    @Test
    fun from_wrongTypesForBooleanKeysFallBackToDefaults() {
        // 手写配置常把布尔写成字符串；类型不符必须退回默认而不是崩
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_HWACCEL to "true",
                DesktopSettings.KEY_KEEP_AWAKE to "no",
            )
        )
        val defaults = DesktopSettings()
        assertEquals(defaults.startupHwaccel, s.startupHwaccel)
        assertEquals(defaults.keepAwake, s.keepAwake)
    }
}
