package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #183 的源码级回归钉子：断开态必须有显式「重新连接」入口。
 *
 * app-server 没有 Compose UI 测试依赖（无 ui-test-junit4 / Robolectric / androidTest），
 * 沿用 [CarLaunchScreenScrollTest] 的源码文本断言范式：只能钉「按钮存在、只在 IDLE
 * 渲染、接到了 service.reconnect()」，真机可达性由回签复核。
 */
class CarLaunchScreenReconnectTest {

    private val launchSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/ui/screen/CarLaunchScreen.kt")
    private val serviceSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt")
    private val coordinatorSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt")

    @Test
    fun `reconnect button is rendered only in the idle state`() {
        assertTrue(
            "连接卡片必须渲染 R.string.reconnect_button，否则断开后没有任何一键重连入口",
            launchSrc.contains("R.string.reconnect_button"),
        )
        assertTrue(
            "重连按钮必须受 state == IDLE 门控（CONNECTING/已连上时不该出现）",
            launchSrc.contains("if (state == CarConnectionService.State.IDLE)"),
        )
    }

    @Test
    fun `reconnect button is wired to the service entry point`() {
        assertTrue(
            "按钮回调必须接到 service.reconnect()，不能只渲染不接线",
            launchSrc.contains("onReconnect = { service.reconnect() }"),
        )
        assertTrue(
            "CarConnectionService 必须暴露 reconnect() 入口",
            serviceSrc.contains("fun reconnect() = sessionCoordinator.reconnect()"),
        )
        assertTrue(
            "sessionCoordinator.reconnect() 必须复用 startConnection() 的完整启动流程",
            coordinatorSrc.contains("fun reconnect()") && coordinatorSrc.contains("startConnection()"),
        )
    }

    @Test
    fun `user disconnect no longer relies on the socket listener side effect`() {
        // 根因：disconnectFromPhone 原先只调 disconnectAllConnections()，状态迁移完全
        // 依赖 Connection.performTeardown 的 listener；连接此前已 disconnect 过就不会回调。
        val body = coordinatorSrc
            .substringAfter("fun disconnectFromPhone()")
            .substringBefore("fun reconnect()")
        assertTrue(
            "disconnectFromPhone 必须显式调用 handleDisconnect() 完成拆除与状态迁移",
            body.contains("handleDisconnect()"),
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
