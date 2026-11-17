package com.dilinkauto.desktop

import com.dilinkauto.desktop.input.InputSender
import com.dilinkauto.protocol.AppListMessage
import com.dilinkauto.protocol.CONNECTION_METHOD_SHIZUKU
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.HandshakeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** 会话状态机。与车机端一致：IDLE → CONNECTING → HANDSHAKING → WAITING_VD → STREAMING。 */
enum class SessionState { IDLE, CONNECTING, HANDSHAKING, WAITING_VD, STREAMING, DISCONNECTED }

/**
 * 桌面接收端的会话编排：控制连接 → 握手 → 等 `VD_PORTS_BOUND` → 连视频/输入口。
 *
 * 职责边界（SRP）：
 *  - 本类只管"会话生命周期与连接编排"，不碰解码与渲染；
 *  - 收到的 H.264 帧通过 [onVideoFrame] 原样抛给上层，由呈现层决定怎么解。
 *
 * 与手机侧的分工：手机在收到握手后自行部署 VD；本端只需等待 `VD_PORTS_BOUND`，
 * 再直连 VD server 绑定的视频（9638）/输入（9639）口——与车机当前架构完全一致。
 */
class DesktopConnectionService(
    private val scope: CoroutineScope,
    private val config: DesktopConfig,
) {
    @Volatile
    var state: SessionState = SessionState.IDLE
        private set

    // 会话回调都标 @Volatile（audit D-L2）：跨线程（lifecycle 线程设、IO 协程调）
    // 只靠 dispatcher 交接保证可见，Kotlin/JMM 上没有这个语义。代价为零（每会话只写一次）。
    @Volatile
    var onStateChanged: ((SessionState) -> Unit)? = null
    @Volatile
    var onHandshakeResponse: ((HandshakeResponse) -> Unit)? = null
    @Volatile
    var onVideoFrame: ((FrameCodec.Frame) -> Unit)? = null
    @Volatile
    var onAppList: ((AppListMessage) -> Unit)? = null
    @Volatile
    var onAppUninstalled: ((String) -> Unit)? = null
    @Volatile
    var onLog: ((String) -> Unit)? = null

    /**
     * 会话异常终止的原因（2026-10-10）：连接被拒、握手被拒、部署失败、等待 VD
     * 超时等最终都汇聚到 [runSession] 的 catch —— 那里调一次本回调。UI（镜像页
     * 失败面板）用它显示具体原因。取消（restart / 关闭窗口）不算错误，不触发。
     */
    @Volatile
    var onSessionError: ((String) -> Unit)? = null

    /**
     * 手机没有 Shizuku 时（`connectionMethod != CONNECTION_METHOD_SHIZUKU`），
     * 部署 VD server 的责任转到本端（Phase 5c，走本地 `adb.exe`）。
     *
     * 这是一个**挂起**回调：调用方在这里把 VD 部署完，本端才继续等 `VD_PORTS_BOUND`。
     * 为空则只能由手机侧自己部署，会话会停在 WAITING_VD。
     */
    @Volatile
    var onVdDeployRequired: (suspend (HandshakeResponse) -> Unit)? = null

    /**
     * 输入发送器：本类负责在连上输入口后注入 [InputSender.transport]、断开时清空，
     * UI 只依赖这个对象发触摸/命令（Phase 2）。
     */
    val inputSender = InputSender()

    private var control: Connection? = null
    private var video: Connection? = null
    private var input: Connection? = null

    // 握手响应 / 端口就绪 / 会话结束三个一次性信号。任意连接断开都会 endSession()。
    private val handshakeResponse = CompletableDeferred<HandshakeResponse>()
    private val vdPortsBound = CompletableDeferred<Unit>()
    private val sessionEnded = CompletableDeferred<Unit>()

    /**
     * teardown 真正跑完的信号（audit D-L3）。
     *
     * [runSession] 的 `finally` 里 complete。没有它，[stop] 只是把三个 await 的
     * 信号收尾，teardown 仍在 IO 协程上异步续跑 —— [DesktopApp.closeSession] 紧接着
     * 停解码器，"先停会话再停解码"这个顺序事实上没有被强制（会话协程可能还在
     * 往已关闭的连接上派发）。
     */
    private val tornDown = CompletableDeferred<Unit>()

    /**
     * 跑一次完整会话，挂起直到会话结束（断开 / 取消 / 出错）。
     * 调用方负责在合适时机取消；取消会走 [teardown] 释放所有连接。
     */
    suspend fun runSession() {
        try {
            val ctrl = openControl()
            control = ctrl

            setState(SessionState.HANDSHAKING)
            val request = HandshakeFactory.build(config)
            ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, request.encode())
            log("handshake sent: ${request.deviceName} ${request.screenWidth}x${request.screenHeight} @${request.screenDpi}dpi fps=${request.targetFps} bitrate=${request.bitrate}")

            val response = handshakeResponse.await()
            if (!response.accepted) throw IOException("handshake rejected by phone")
            log("handshake accepted: ${response.deviceName} vd=${response.displayWidth}x${response.displayHeight} method=${response.connectionMethod} vdDpi=${response.vdDpi}")
            onHandshakeResponse?.invoke(response)

            // 手机没有 Shizuku 时，VD server 得由本端部署（Phase 5c）。
            // 部署可能耗时几秒（每条 adb 命令都要起一个进程），但控制连接有自己的
            // 读/心跳协程，不会被这里挡住。
            if (response.connectionMethod != CONNECTION_METHOD_SHIZUKU) {
                log("method=${response.connectionMethod}（无 Shizuku）—— 由本端部署 VD server")
                onVdDeployRequired?.invoke(response)
            }

            setState(SessionState.WAITING_VD)
            awaitVdPortsBound()
            log("VD ports bound — connecting video(${config.videoPort}) / input(${config.inputPort})")

            setState(SessionState.STREAMING)
            connectVideoAndInput()

            sessionEnded.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("session error: ${e.message}")
            onSessionError?.invoke(e.message ?: e.javaClass.simpleName)
        } finally {
            teardown()
            // D-L3：teardown 的完成信号。runSession 被取消时 Kotlin 协程依然会跑
            // finally，所以这条路径对"取消"同样有效。
            tornDown.complete(Unit)
        }
    }

    /**
     * 等手机回 `VD_PORTS_BOUND`，**带超时**（audit WIN-06）。
     *
     * 手机侧只在**成功**时回这条（`app-client` 的 `handleHandshake` 在 display ready
     * 时才发），部署失败没有任何回报，而控制口心跳会一直把 TCP 保活 —— 没有超时就会
     * 永久停在 `WAITING_VD`，UI 上只有一句"正在连接 …"，看不出是在重试还是已经死了。
     *
     * 超时必须转成普通 [IOException]：`TimeoutCancellationException` 是
     * `CancellationException` 的子类，会被 [runSession] 原样抛出，上层就再也收不到
     * "会话结束"这个信号了。
     */
    private suspend fun awaitVdPortsBound() {
        try {
            withTimeout(VD_READY_TIMEOUT_MS) { vdPortsBound.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException(
                "等待手机侧 VD 就绪超时（${VD_READY_TIMEOUT_MS / 1000}s）—— " +
                    "手机侧只在成功绑定后才回 VD_PORTS_BOUND：请确认 Shizuku 可用" +
                    "（否则需在 config.json 里设 \"dev_mode\": true 由本端用 adb 部署），" +
                    "并检查手机侧 ${com.dilinkauto.protocol.VdDeploy.LOG_PATH}",
            )
        }
    }

    /**
     * 主动结束会话（触发上层挂起的 [runSession] 返回），并**等 teardown 跑完**。
     *
     * 等待是有界的（audit D-L3）：teardown 里只有 socket close（非阻塞）与状态置位，
     * 正常瞬间完成；上限只在"IO 协程被取消到永远跑不到 finally"这种病态情况下兜底,
     * 防止把调用线程（lifecycle）挂死。
     *
     * 用 `runBlocking + withTimeout` 而不是裸等待：[tornDown] 是挂起型信号，
     * 没有阻塞式的 `get(timeout)`；而且本方法**不能**是挂起函数 —— 它的调用方
     * （[DesktopApp.closeSession]，跑在 lifecycle 执行器线程上）需要同步的
     * "停完会话再停解码"顺序。
     */
    fun stop() {
        endSession("stopped by caller")
        runCatching {
            runBlocking {
                withTimeout(TEARDOWN_TIMEOUT_MS) { tornDown.await() }
            }
        }
    }

    private suspend fun openControl(): Connection {
        setState(SessionState.CONNECTING)
        val ctrl = connectWithTimeout(config.controlPort, "控制口")
        ctrl.onLog { log("control: $it") }
        ctrl.onFrames(Channel.CONTROL) { frame -> onControlFrame(frame) }
        ctrl.onFrames(Channel.DATA) { frame -> onDataFrame(frame) }
        ctrl.onDisconnect { endSession("control disconnected") }
        ctrl.start(enableHeartbeat = true)
        log("control connected to ${config.phoneHost}:${config.controlPort}")
        return ctrl
    }

    private suspend fun connectVideoAndInput() {
        val v = connectWithTimeout(config.videoPort, "视频口")
        video = v
        v.onFrames(Channel.VIDEO) { frame -> onVideoFrame?.invoke(frame) }
        v.onDisconnect { endSession("video disconnected") }
        // 视频/输入口无心跳（协议约定），仅靠读端 EOF 感知断开。
        v.start(enableHeartbeat = false)
        log("video connected to ${config.phoneHost}:${config.videoPort}")

        val i = connectWithTimeout(config.inputPort, "输入口")
        input = i
        i.onDisconnect { endSession("input disconnected") }
        i.start(enableHeartbeat = false)
        // 注入输入通道：Channel.INPUT 走触摸，Channel.CONTROL 走 GO_HOME/GO_BACK/LAUNCH_APP
        // （与手机侧 PipelineServer.readTouchAndCommands 的约定一致）。
        inputSender.reset()
        inputSender.transport = { channel, messageType, payload ->
            i.sendFrame(FrameCodec.Frame(channel, messageType, payload))
        }
        log("input connected to ${config.phoneHost}:${config.inputPort}")
    }

    /**
     * 带超时的连接建立。超时给可操作的排查提示——Phase 1a 联调时"静默卡住"
     * 无法区分"在重试"与"已死"，这里把它变成明确失败。
     */
    private suspend fun connectWithTimeout(port: Int, label: String): Connection {
        return try {
            withTimeout(config.connectTimeoutMs) {
                Connection.connect(config.phoneHost, port, scope)
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException(
                "连接$label ${config.phoneHost}:$port 超时（${config.connectTimeoutMs}ms）——" +
                    "请确认手机端 DiLink 已启动、IP/端口正确、且与 PC 在同一网络"
            )
        }
    }

    private fun onControlFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_RESPONSE -> handshakeResponse.complete(HandshakeResponse.decode(frame.payload))
            ControlMsg.VD_PORTS_BOUND -> vdPortsBound.complete(Unit)
            ControlMsg.DISCONNECT -> endSession("phone sent DISCONNECT")
            else -> Unit
        }
    }

    /**
     * 数据通道（与控制口同一条 TCP）。app list 只在握手后推一次，
     * 卸载/白名单变更时再推；解析交给上层，本类不做业务。
     */
    private fun onDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.APP_LIST -> onAppList?.invoke(AppListMessage.decode(frame.payload))
            DataMsg.APP_UNINSTALLED -> onAppUninstalled?.invoke(String(frame.payload, Charsets.UTF_8))
            else -> Unit
        }
    }

    /** 结束会话：把三个一次性信号都收尾（已完成的重复调用是安全的 no-op）。 */
    private fun endSession(reason: String) {
        log("session ended: $reason")
        handshakeResponse.completeExceptionally(IOException(reason))
        vdPortsBound.completeExceptionally(IOException(reason))
        sessionEnded.complete(Unit)
    }

    private fun teardown() {
        // 先摘掉输入通道，避免断开后仍有帧被投进已关闭的连接。
        inputSender.transport = null
        inputSender.reset()
        video?.disconnect()
        input?.disconnect()
        control?.disconnect()
        video = null
        input = null
        control = null
        setState(SessionState.DISCONNECTED)
    }

    private fun setState(next: SessionState) {
        if (state == next) return
        state = next
        log("state -> $next")
        onStateChanged?.invoke(next)
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    private companion object {
        /**
         * 等 VD 就绪的上限（audit WIN-06）。
         *
         * 要覆盖"本端 adb 部署（connect + kill/wait + launch 几条命令，各带超时）
         * → 手机起 VD → 编码器就绪"的总耗时，所以取 30s 而不是连接超时那种秒级值；
         * 又要远小于"永久卡住"，让失败尽早变成一条可读的原因。
         */
        const val VD_READY_TIMEOUT_MS = 30_000L

        /**
         * [stop] 等 teardown 的上限（audit D-L3）。
         *
         * teardown 本体只有 socket close + 置状态，正常瞬间完成；这个上限只兜
         * "IO 协程被取消到跑不到 finally"的病态情况。
         */
        const val TEARDOWN_TIMEOUT_MS = 5_000L
    }
}