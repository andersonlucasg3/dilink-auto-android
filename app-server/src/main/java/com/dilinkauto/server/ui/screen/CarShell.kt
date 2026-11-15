package com.dilinkauto.server.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.InfoBar
import com.dilinkauto.server.ui.InfoChip
import com.dilinkauto.server.ui.nav.PersistentBottomNavBar
import com.dilinkauto.server.ui.nav.PersistentNavBar

/**
 * Top-level car shell: streaming layout (mirror + nav bar) or launch screen.
 *
 * Extracted from [com.dilinkauto.server.MainActivity] so the Activity holds
 * only its lifecycle concerns (service binding, USB intent forwarding,
 * immersive mode, rotation re-handshake) and this file owns the Compose
 * tree and screen-state routing.
 */
enum class Screen { HOME, APP }

/** UI 层日志 TAG：点击应用等交互记入 logcat（dev 模式下由 CarLogWriter 转发到手机） */
private const val TAG = "CarShell"

@Composable
fun CarShell(service: CarConnectionService) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    val state by service.state.collectAsState()
    val appList by service.appList.collectAsState()
    val statusMessage by service.statusMessage.collectAsState()
    val videoReady by service.videoReady.collectAsState()
    val timeState by service.timeState.collectAsState()
    val weatherState by service.weatherState.collectAsState()
    val phoneStatusState by service.phoneStatusState.collectAsState()
    val isConnected = state == CarConnectionService.State.STREAMING ||
            state == CarConnectionService.State.CONNECTED

    // issue #39 / #40 / #41 / #151：常驻信息 chip：时间 + 天气 + 手机电量。
    // 天气：state 为 null（从未成功）时也加 chip 渲染 `—`，让用户知道此栏存在；
    // issue #50：海拔 chip 已移除 —— 车机 GPS 不给垂直精度（`vAcc=none`），稳态读数
    // 与真实海拔差 22.6m，BYD 厂商接口也没有海拔来源，没有可信数据就不上屏。
    // `AltitudeProvider` 仍保留采集与 `[Altitude]` 诊断日志，将来有可信来源可直接恢复渲染。
    // issue #54：其余三项常驻 —— 横屏进左侧导航栏，竖屏进顶部信息条，不再随 Screen 切换隐藏。
    // issue #151：电量同样常驻，未收到/已断开渲染 `—`；文本由 protocol-core 的
    // BatteryFormat 生成（`86%` / `86%+`），受左栏 76dp 定宽约束，不加图标。
    val infoChips = remember(timeState, weatherState, phoneStatusState) {
        buildList {
            timeState?.let { add(InfoChip(it.text)) }
            // 天气：把 WeatherUiState 转成 chip 文本；null（从未成功）渲染 `—`。
            val weatherText = weatherState?.let {
                com.dilinkauto.protocol.WeatherFormat.formatWeather(
                    com.dilinkauto.protocol.WeatherData(
                        city = it.city,
                        condition = it.condition,
                        temperature = it.temperature,
                        updatedAtMs = it.updatedAtMs
                    ),
                    isStale = it.isStale
                )
            } ?: com.dilinkauto.protocol.WeatherFormat.formatWeather(null, isStale = false)
            add(InfoChip(weatherText))
            add(
                InfoChip(
                    com.dilinkauto.protocol.BatteryFormat.formatBattery(
                        level = phoneStatusState?.level,
                        charging = phoneStatusState?.charging
                    )
                )
            )
        }
    }

    // During a mid-stream rotation, onCarViewportChanged sets state to CONNECTING but
    // keeps the app list (a real disconnect clears it in handleDisconnect). Keep the
    // streaming layout visible so CarContentArea's video-wait overlay covers the ~2s
    // VD redeploy gap instead of flashing the full CarLaunchScreen.
    val showStreamingMode = appList.isNotEmpty() &&
            (isConnected || state == CarConnectionService.State.CONNECTING)

    // When VD stack empties (after back presses), go to home screen
    LaunchedEffect(Unit) {
        service.vdStackEmpty.collect {
            currentScreen = Screen.HOME
        }
    }

    val launchApp: (String) -> Unit = { pkg ->
        Log.i(TAG, "launchApp clicked: $pkg -> switch to Screen.APP")
        service.launchApp(pkg)
        currentScreen = Screen.APP
    }

    // App info dialog (layout in AppInfoDialog, audit R3-SRP-19)
    val appInfoData by service.appInfoData.collectAsState()
    appInfoData?.let { info ->
        AppInfoDialog(info = info, onDismiss = { service.clearAppInfoData() })
    }

    if (showStreamingMode) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

        // issue #54：chip 落位只由方向决定，镜像页也常驻；不再用 currentScreen 作闸门。
        val placement = ShellChrome.infoPlacement(isLandscape)

        if (placement == InfoPlacement.LEFT_RAIL) {
            Row(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
            ) {
                PersistentNavBar(
                    onBack = { service.goBack() },
                    onHome = {
                        service.goHome()
                        currentScreen = Screen.HOME
                    },
                    // issue #152-PA：切回上一个显示过的 APP（GO_RECENT，双击 recents 语义）。
                    // connected 传 isConnected：重握手（CONNECTING）期间该键禁用，不发无效指令。
                    //
                    // 为什么必须同时切到 Screen.APP：手机侧已把上一个 APP 拉到 VD 前台，
                    // 车机若还停在 Screen.HOME，会继续显示主页磁贴，用户看不到切换结果。
                    // 这是无条件副作用，以下是已知边界（评审要求记录，本 PR 不改行为）：
                    //  - 已经在 APP 屏时重复置位，多余但无害；
                    //  - quickSwitch() 找不到候选（只剩自家包 / launcher / systemui 等被过滤）
                    //    时，车机会离开主页、显示 VD 的当前画面，可按「主页」退回；
                    //  - videoReady = false 时会落进下面 CarContentArea 的「等待视频」无限
                    //    转圈 overlay（`CarShell.kt:220`），直到首帧就绪或重连。
                    onRecent = {
                        service.goRecent()
                        currentScreen = Screen.APP
                    },
                    onDisconnect = {
                        service.disconnectFromPhone()
                        currentScreen = Screen.HOME
                    },
                    connected = isConnected,
                    modifier = Modifier.zIndex(1f),
                    // issue #54：chip 常驻左栏，不占内容区高度 —— 顺带消除了切页时
                    // 内容区高度变化引起的视口重部署。
                    chips = infoChips
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CarContentArea(
                        service = service,
                        currentScreen = currentScreen,
                        videoReady = videoReady,
                        statusMessage = statusMessage,
                        launchApp = launchApp,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // issue #54：竖屏没有左栏，顶部信息条改为常驻（仅空 chip 时不渲染）。
                if (ShellChrome.showTopInfoBar(isLandscape, chipsEmpty = infoChips.isEmpty())) {
                    InfoBar(infoChips)
                }
                CarContentArea(
                    service = service,
                    currentScreen = currentScreen,
                    videoReady = videoReady,
                    statusMessage = statusMessage,
                    launchApp = launchApp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                PersistentBottomNavBar(
                    onBack = { service.goBack() },
                    onHome = {
                        service.goHome()
                        currentScreen = Screen.HOME
                    },
                    // issue #152-PA：同横屏左栏 —— 必须切到 Screen.APP 才能看到切换结果，
                    // 无条件置位的已知边界（已在 APP 冗余无害 / 无候选时显示 VD 当前画面、
                    // 可 Home 退回 / videoReady=false 时落进 :220 的等待视频转圈）见上方注释。
                    onRecent = {
                        service.goRecent()
                        currentScreen = Screen.APP
                    },
                    onDisconnect = {
                        service.disconnectFromPhone()
                        currentScreen = Screen.HOME
                    },
                    connected = isConnected
                )
            }
        }
    } else {
        // Full-screen launch / connection screen — no nav bar, connection-focused
        CarLaunchScreen(service = service, infoChips = infoChips)
    }
}

/**
 * Shared content area for streaming mode. Renders the mirror surface, the
 * "waiting for video" overlay when the stream isn't ready, and the home
 * screen. Used by both the landscape (Row) and portrait (Column) layouts in
 * [CarShell] so the two branches differ only in nav-bar placement.
 */
@Composable
private fun CarContentArea(
    service: CarConnectionService,
    currentScreen: Screen,
    videoReady: Boolean,
    statusMessage: String,
    launchApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        MirrorContent(service = service, visible = currentScreen == Screen.APP)
        val showVideoWaitOverlay = !videoReady && currentScreen == Screen.APP
        when {
            showVideoWaitOverlay -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            statusMessage.ifEmpty { stringResource(R.string.status_starting_vd) },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
            currentScreen == Screen.HOME -> HomeContent(service = service, onAppClick = launchApp)
        }
    }
}
