package com.dilinkauto.desktop

import com.dilinkauto.desktop.deploy.AdbDeploy
import com.dilinkauto.desktop.deploy.AdbDeployer
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.protocol.HandshakeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 无窗口探针模式（audit R3-SRP-13）——链路验证入口（不解码、不渲染）。
 *
 * 从 [main] 所在文件下沉：入口只做参数解析与模式选择，探针自己是一套完整的
 * 「建会话 → 挂回调 → 每秒打帧率」装配。与 [DesktopApp] 的窗口编排有意不共
 * 用同一套 wiring——探针没有解码/渲染/常亮，硬凑共享抽象只会让两侧互相牵制。
 */
internal class ProbeRunner(
    private val config: DesktopConfig,
    private val log: DesktopLog,
) {

    /**
     * **会话级持有**的 adb 部署器（audit WIN-02）。
     *
     * [AdbDeployer] 持有的本地 `adb shell` 进程**就是**设备侧引擎的存活锚点
     * （见 `AdbRunner` 的硬约束注释），`close()` 一调即等于杀引擎。此前它在
     * `deploy()` 返回时就被 `finally` 关掉 —— 而那一刻 VD server 刚启动，于是
     * 引擎在绑定 9638/9639 之前被回收，探针的 ADB 分支 100% 不可用。
     */
    private val deployer = AdbDeployer()

    fun run() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val frameCount = AtomicLong()
        val service = DesktopConnectionService(scope, config).apply {
            onStateChanged = { log.info("state", "$it") }
            onHandshakeResponse = { logHandshake(log, it) }
            onVideoFrame = { frameCount.incrementAndGet() }
            onLog = { log.info("session", it) }
            // 探针同样支持 ADB 部署路径，否则无 Shizuku 的手机根本走不到推流
            onVdDeployRequired = { response -> deployVdServer(response) }
        }

        // 每秒打印一次视频帧率，方便肉眼确认推流是否稳定。
        scope.launch {
            var last = 0L
            while (true) {
                delay(1_000)
                val now = frameCount.get()
                log.info("stats", "video fps=${now - last} total=$now")
                last = now
            }
        }

        log.info("session", "连接 ${config.phoneHost}:${config.controlPort} ...")
        try {
            runBlocking { service.runSession() }
        } catch (e: Exception) {
            log.error("session", "会话异常: ${e.message}")
        } finally {
            // 只有会话真正结束才释放 adb（audit WIN-02）——提前关会杀掉刚启动的引擎。
            // 顺序（2026-10-10）：先让引擎优雅退出（哨兵 + 有界等待 + -9 兜底），再放
            // 锚点 —— 直接 close() 等于强杀，设备侧 cleanup 被截断（同 DesktopApp.closeSession）。
            runCatching { runBlocking { deployer.gracefulStop { log.info(TAG, it) } } }
            deployer.close()
        }
        log.info("session", "会话结束，收到视频帧总数=${frameCount.get()}")
        scope.cancel()
    }

    /**
     * 探针模式没有配置面板，ADB 部署只能用环境变量 `DILINK_DEV_MODE=1` 显式开启
     * —— 不默认动用户的 adb（可能连到别的设备上）。
     *
     * 失败一律抛出终止会话（audit WIN-06）：手机侧只在成功时回 `VD_PORTS_BOUND`，
     * 静默返回会让探针一直挂在那里打印 `fps=0`，看不出原因。
     */
    private suspend fun deployVdServer(response: HandshakeResponse) {
        if (System.getenv("DILINK_DEV_MODE") != "1") {
            log.warn(TAG, "手机无 Shizuku，且未设 DILINK_DEV_MODE=1 —— 无法部署 VD server")
            throw IOException("未设 DILINK_DEV_MODE=1，无法部署 VD server")
        }
        // 对端可控字段的白名单校验（audit D-01，与 DesktopApp 同一套）：
        // adbPort 只允许标准端口，vdServerJarPath 必须落在 /sdcard/DiLinkAuto/ 下。
        AdbDeploy.checkAdbPort(response.adbPort)
        val jarPath = AdbDeploy.sanitizeJarPath(response.vdServerJarPath)
        val deployed = deployer.deploy(
            config = config,
            adbPort = response.adbPort,
            jarPath = jarPath,
            log = { log.info("adb", it) },
            phoneVdWidth = response.vdWidth,
            phoneVdHeight = response.vdHeight,
        )
        if (!deployed) {
            throw IOException("ADB 部署 VD server 失败 —— 详见上面的 adb 日志")
        }
    }

    private companion object {
        const val TAG = "probe"
    }
}
