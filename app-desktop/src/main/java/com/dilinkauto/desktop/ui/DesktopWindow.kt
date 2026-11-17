package com.dilinkauto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.dilinkauto.desktop.DesktopApp
import com.dilinkauto.desktop.SessionState
import com.dilinkauto.desktop.display.Screens
import javax.swing.SwingUtilities

/** 主视图：镜像（手机画面）/ 应用启动器 / 显示设置。 */
enum class DesktopView { MIRROR, APPS, DISPLAY }

/**
 * 桌面窗口外壳：左侧导航栏 + 主视图。
 *
 * 直接把 [DesktopApp] 交给外壳（而不是十几个回调参数）：窗口模式的所有编排都在
 * 那里，UI 只负责"读状态、调方法"。会话代（[DesktopApp.Session]）变化时，
 * 视频组件按 generation 重建。
 *
 * **视频面板只在"镜像"页组合**（audit WIN-01）：AWT 组件恒在 Compose 内容之上，
 * 常驻组合会让「应用」「显示」两页被它盖住。
 *
 * [DesktopApp.Session.sessionEnded] 变为 true 时自动关窗退出（手机断开/会话出错时
 * 不留一个空窗口）。这个信号只由**没有被下一代顶替**的会话发出，所以"应用并重连"
 * 不会误关窗口（audit WIN-04，判据在 [DesktopApp]）；且只由**到过 STREAMING** 的
 * 会话发出 —— 启动失败（手机端未启动、连接被拒）时窗口保留，用户可点
 * 「应用并重连」重试（2026-10-09：否则双击 exe 3 秒即整窗退出，形同闪退）。
 *
 * 导航三键与车机端 `PersistentNavBar` 语义一致，但走输入口发
 * [com.dilinkauto.protocol.ControlMsg]：主页 = GO_HOME、返回 = GO_BACK、最近 = GO_RECENT。
 */
@Composable
fun DesktopWindow(app: DesktopApp, onClose: () -> Unit) {
    val session by app.session.collectAsState()
    val ended by (session?.sessionEnded ?: NoSessionEnded).collectAsState()
    val keepAwake by app.keepAwakeOn.collectAsState()
    val hardwareInUse by (session?.hardwareDecode ?: NoHardwareDecode).collectAsState()
    val appList by (session?.apps ?: NoApps).collectAsState()
    // 失败面板（2026-10-10）：runSession 的 catch 把连接被拒/握手被拒/部署失败/
    // 等待 VD 超时等统一汇聚到 lastError；DISCONNECTED 且未自动关窗时，
    // 视频区整块换成提示 + 重试按钮（见下方 VideoFailurePanel）。
    val lastError by (session?.lastError ?: NoLastError).collectAsState()
    val sessionState by (session?.state ?: NoState).collectAsState()

    var view by remember { mutableStateOf(DesktopView.MIRROR) }
    // 窗口宽度额外加上导航栏（audit WIN-14）：视频面板占的是"窗口宽 − 导航栏宽"，
    // 只有把导航栏算进去，这块区域才与握手视口同比例，画面不出现上下黑边。
    val windowState = rememberWindowState(
        size = DpSize(app.viewportWidth.dp + RAIL_WIDTH, app.viewportHeight.dp),
    )
    var fullscreen by remember { mutableStateOf(false) }
    // 每次进入"显示"页重新枚举显示器（audit WIN-11）：拔插外接屏后列表要能刷出来；
    // 只在首次组合时枚举一次的话，用户不重启应用就看不到新屏。
    val screens = remember(view) { if (view == DesktopView.DISPLAY) Screens.list() else emptyList() }

    // 手机物理屏的"意图"状态。连上后 vd-server 会自动熄屏，所以每一代会话都从
    // "已熄屏"起步 —— 用 generation 当 key，重连后自动归位，不会留下上一代的残影。
    var phoneScreenOn by remember(session?.generation) { mutableStateOf(false) }

    // 命令没进入输入通道时的提示（audit WIN-12）：此时开关不翻转，但要让用户知道原因。
    var phoneScreenHint by remember(session?.generation) { mutableStateOf<String?>(null) }

    LaunchedEffect(ended) {
        if (ended) onClose()
    }

    // 退出全屏的尺寸还原要直接操作 AWT 窗口（见 [applyWindowed]），而 F11 的
    // onKeyEvent 回调不在 FrameWindowScope 里、拿不到 window；由窗口内容在
    // 组合时用 SideEffect 回填。
    val composeWindowRef = remember { java.util.concurrent.atomic.AtomicReference<ComposeWindow?>(null) }

    /**
     * 退出全屏，并把窗口尺寸还原成"视口 + 导航栏"的规范物理尺寸（2026-10-10）。
     *
     * 实机铁证：125% 缩放屏上 1720x900 → 全屏 → 还原为 1376x720。诊断打点
     * （JNA 直读 GetWindowRect + 窗口 user 尺寸，临时，已删）确认的完整机制：
     *
     *  1. 退出全屏最终走 JDK 的 `GraphicsDevice.setFullScreenWindow(null)`
     *     （Window → ComposeWindow → ComposeWindowPanel → ComposeContainer →
     *     ComposeSceneMediator → WindowSkiaLayerComponent → SkiaLayer →
     *     FullscreenAdapter → PlatformOperations，逐层核对字节码）；它在
     *     Compose 里是**异步**执行的（snapshotFlow 收集器下一帧才跑），所以这里
     *     直接调 JDK 把它变成同步动作（这正是那整条链的全部实现，且 Compose 侧的
     *     全屏查询都从 JDK 实时状态推导，不会失联）。
     *  2. JDK 的还原有 bug：把保存的 user 坐标**直接当物理值**应用（native 少乘
     *     一次 125%）。实测还原后 user=1376x720、物理也=1376x720。
     *  3. 此时窗口 internal 的 user 尺寸恰好还是 1376x720（规范 dp 值），而 AWT
     *     对"尺寸未变"的 setSize 整体**短路** —— 直接"设回规范值"是 no-op，native
     *     停在被写错的物理尺寸上。想靠 WindowState.size 补偿也不行：更新器
     *     （`Window$5`）固定**先 size 后 placement**且值相等即跳过，尺寸要么在窗口
     *     仍处全屏时被下发，要么被跳过，最后都被 JDK 的还原覆盖。
     *
     * 修复 = 在 EDT 事件队列尾部"先弹再设回"：+1 打破相等、迫使 native 重算
     * （换算即回到正常 ×1.25 管道，连位置也一并复原），再设回规范值。实测 F11
     * 往返后物理 (60,60,1720x900) 与初始建窗一致，重复往返稳定。窗口状态交给
     * 监听器自然回写（数值域一致，更新器再应用时幂等）。
     */
    fun applyWindowed() {
        fullscreen = false
        windowState.placement = WindowPlacement.Floating
        val win = composeWindowRef.get() ?: return
        win.graphicsConfiguration.device.setFullScreenWindow(null)
        SwingUtilities.invokeLater {
            val width = app.viewportWidth + RAIL_WIDTH_PX
            val height = app.viewportHeight
            win.setSize(width + 1, height + 1)
            win.setSize(width, height)
        }
    }

    fun setFullscreen(on: Boolean) {
        if (on) {
            fullscreen = true
            windowState.placement = WindowPlacement.Fullscreen
        } else {
            applyWindowed()
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "DiLink Desktop — ${app.phoneHost}",
        state = windowState,
        // F11 切全屏：窗口类应用的标准快捷键，不占导航栏。
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                setFullscreen(!fullscreen)
                true
            } else {
                false
            }
        },
    ) {
        // 回填窗口引用（见 composeWindowRef / applyWindowed 的注释）。
        SideEffect { composeWindowRef.set(window) }
        Row(Modifier.fillMaxSize().background(Palette.Backdrop)) {
            NavRail(
                view = view,
                onSelectView = { view = it },
                onGoHome = { session?.goHome() },
                onGoBack = { session?.goBack() },
                onGoRecent = { session?.goRecent() },
            )
            Box(Modifier.fillMaxSize()) {
                val current = session
                if (current == null) {
                    BasicText(
                        "正在连接 ${app.phoneHost} …",
                        style = TextStyle(color = Palette.TextDim, fontSize = 14.sp),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (view == DesktopView.MIRROR) {
                    // 只在"镜像"页组合视频面板（audit WIN-01）。
                    //
                    // AWT 组件恒在 Compose 内容之上，而 `SwingPanel` 的 background
                    // 是画在它自己那层 JPanel 上的（容器不透明，`setBackground` 见
                    // 1.6.2 的 `SwingPanel$5`），所以"把 videoView.isVisible 置 false"
                    // 治不了遮挡 —— 黑 JPanel 还在。唯一可靠的做法是这一页之外
                    // **根本不组合** SwingPanel。
                    //
                    // 摘挂是安全的：AWT 的 `Container.addImpl` 在 add 时会把组件从
                    // 旧父容器移走（自动 reparent），`FocusSwitcher` 也不注册全局
                    // 监听器（均以字节码核对过），因此不存在"失去父容器"或泄漏。
                    // 不组合期间 `videoView.setFrame` 只是 repaint 空转，回到本页
                    // 立刻显示最近一帧。
                    //
                    // 失败面板（2026-10-10）：会话异常终止（连接被拒/部署失败/等待
                    // VD 超时）时视频不会再有新帧 —— 整块换成提示 + 重试。必须
                    // "不组合 SwingPanel"而不是叠加（AWT 恒在 Compose 之上，WIN-01）。
                    // `!ended`：到过 STREAMING 的正常结束会随即自动关窗，不进入此
                    // 分支，避免正常断连瞬间闪一下面板。
                    val windowFailed = sessionState == SessionState.DISCONNECTED && !ended
                    if (windowFailed) {
                        VideoFailurePanel(
                            error = lastError,
                            onRetry = { app.restart(dpiOverride = null, hwaccelEnabled = null) },
                            onReconfigure = { view = DesktopView.DISPLAY },
                            modifier = Modifier.fillMaxSize().background(Palette.Backdrop),
                        )
                    } else {
                        // key(generation)：重连后换的是新的 videoView，必须让 SwingPanel 重建。
                        key(current.generation) {
                            SwingPanel(
                                background = Color.Black,
                                factory = { current.videoView },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                when (view) {
                    DesktopView.APPS -> AppGridView(
                        apps = appList,
                        onLaunch = { pkg ->
                            session?.launchApp(pkg)
                            // 点击后自动切回"镜像"页：启动命令走输入通道到手机侧
                            // vd-server，app 出现在镜像里而不是本页 —— 留在本页就是
                            // "点了没反应"的体感（2026-10-09 实测：用户点高德后以为
                            // 失败，手动切到镜像页才发现早已成功）。
                            view = DesktopView.MIRROR
                        },
                        modifier = Modifier.fillMaxSize().background(Palette.Backdrop),
                    )

                    DesktopView.DISPLAY -> DisplaySettingsView(
                        state = DisplayPanelState(
                            screens = screens,
                            fullscreen = fullscreen,
                            dpi = app.startupDpi,
                            hwaccel = app.startupHwaccel,
                            hardwareInUse = hardwareInUse,
                            keepAwake = keepAwake,
                            keepAwakeSupported = app.keepAwakeSupported,
                            phoneScreenOn = phoneScreenOn,
                            phoneScreenHint = phoneScreenHint,
                            phoneScreenEnabled = current != null,
                        ),
                        onToggleFullscreen = ::setFullscreen,
                        onMoveToScreen = { screen ->
                            // 全屏状态下位置被忽略，先退回浮动窗口（并还原规范尺寸）。
                            if (fullscreen) applyWindowed()
                            window.setLocation(screen.x, screen.y)
                            // 尺寸语义（2026-10-10 起）：先取规范尺寸（视口 + 导航栏，
                            // audit WIN-14 —— 视频区才与握手视口同比例），目标屏放不下
                            // 时按屏 bounds 收缩（audit D-L7 的原意：从大屏搬到小屏不能
                            // 让窗口大部分留在屏外）。直接 setSize 而不是赋值
                            // `windowState.size`：后者在"值相等"时不会下发，窗口被用户
                            // 手动改过尺寸后就搬不回规范尺寸。
                            val maxWidth = maxOf(MIN_MOVED_WINDOW_PX, screen.width - MOVED_WINDOW_MARGIN_PX)
                            val maxHeight = maxOf(MIN_MOVED_WINDOW_PX, screen.height - MOVED_WINDOW_MARGIN_PX)
                            val width = minOf(app.viewportWidth + RAIL_WIDTH_PX, maxWidth)
                            val height = minOf(app.viewportHeight, maxHeight)
                            window.setSize(width, height)
                            windowState.size = DpSize(width.dp, height.dp)
                        },
                        onToggleKeepAwake = { app.setKeepAwake(it) },
                        onTogglePhoneScreen = { on ->
                            // 命令真的进了输入通道才翻转本地开关（audit WIN-12）：
                            // 输入口未连上时 setDisplayPower 返回 false，若照翻，
                            // 显示的"意图"就会与手机的真实状态长期不一致。
                            if (session?.setDisplayPower(on) == true) {
                                phoneScreenOn = on
                                phoneScreenHint = null
                            } else {
                                phoneScreenHint = "开关未变：输入口尚未连上，请稍后重试"
                            }
                        },
                        onApplyAndReconnect = { dpi, hwaccel ->
                            app.restart(dpiOverride = dpi, hwaccelEnabled = hwaccel)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    DesktopView.MIRROR -> Unit
                }
            }
        }
    }
}

/**
 * 连接失败面板（2026-10-10）。
 *
 * 启动失败（连接被拒 / 握手被拒 / VD 部署失败 / 等待 VD 超时）不关窗
 * （WIN-06）—— 此前用户看到的是一片空白视频区，既不知道发生了什么，也没
 * 有就地重试的入口（只能去「显示」页点「应用并重连」）。这里显示
 * [error]（来自 `DesktopApp.Session.lastError`，由 `runSession` 的 catch
 * 汇聚）并提供「重试连接」，等价于「应用并重连」但不改任何参数。
 */
@Composable
private fun VideoFailurePanel(
    error: String?,
    onRetry: () -> Unit,
    onReconfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("连接未建立", style = TextStyle(color = Palette.Text, fontSize = 16.sp))
        Spacer(Modifier.height(8.dp))
        BasicText(
            error ?: "会话已断开",
            style = TextStyle(color = Palette.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("重试连接", onClick = onRetry, primary = true)
            ActionButton("打开显示设置", onClick = onReconfigure)
        }
    }
}

/** 左侧竖向导航栏：上方是视图切换，下方是发给手机的导航键。 */
@Composable
private fun NavRail(
    view: DesktopView,
    onSelectView: (DesktopView) -> Unit,
    onGoHome: () -> Unit,
    onGoBack: () -> Unit,
    onGoRecent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(Palette.Rail)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailButton("镜像", onClick = { onSelectView(DesktopView.MIRROR) }, selected = view == DesktopView.MIRROR)
        RailButton("应用", onClick = { onSelectView(DesktopView.APPS) }, selected = view == DesktopView.APPS)
        RailButton("显示", onClick = { onSelectView(DesktopView.DISPLAY) }, selected = view == DesktopView.DISPLAY)

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Divider))
        Spacer(Modifier.height(4.dp))

        RailButton("主页", onGoHome)
        RailButton("返回", onGoBack)
        RailButton("最近", onGoRecent)
    }
}

@Composable
private fun RailButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Palette.ButtonActive else Palette.Button)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Palette.Text, fontSize = 13.sp))
    }
}

private val RAIL_WIDTH = 96.dp

/**
 * [RAIL_WIDTH] 的 AWT 用户坐标数值（2026-10-10）。
 *
 * 供直接对窗口 `setSize` 的场合（[DesktopWindow] 的 `applyWindowed` /
 * `onMoveToScreen`）使用。数值语义：Compose 对 `WindowState.size` 的下发是
 * "dp 数值原样当 AWT 用户坐标"（`Windows_desktopKt.setSizeImpl` 字节码：
 * `window.setSize(size.width.roundToInt(), ...)`，无 density 乘法），所以两者
 * 同域 —— 初始建窗 `DpSize(1280+96 dp, 720 dp)` 实测即 1720x900 物理（125%
 * 缩放屏），直接传 dp 数值即可得到同样的物理尺寸。
 */
private val RAIL_WIDTH_PX = RAIL_WIDTH.value.toInt()

/** 搬窗口后允许的最小尺寸（audit D-L7）：再小就没法用了。 */
private const val MIN_MOVED_WINDOW_PX = 480

/** 搬窗口时给目标屏留的余量（任务条 / 屏边，audit D-L7）。 */
private const val MOVED_WINDOW_MARGIN_PX = 80