package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarViewport
import com.dilinkauto.server.ui.InfoChip
import com.dilinkauto.server.ui.InfoChipColumn
import com.dilinkauto.server.ui.theme.NavBarBackgroundColor

/**
 * Persistent navigation bar — always visible during streaming mode.
 *
 * Landscape: left rail. Portrait: bottom bar. Both render the same action set:
 * home, back, previous app (issue #152-PA) and disconnect (Eject). The bar width
 * is computed to guarantee an even viewport for the H.264 encoder.
 *
 * [rememberNavBarSize] and [NavActionButtons] are shared between the two
 * layouts so the nav-bar geometry and the button set stay in sync as the
 * viewport rotates. The action set / order / enabled-ness itself lives in
 * [NavBarLayout] so it is unit-testable without Compose.
 */

/** Compute the nav-bar size in dp from the current screen width. */
@Composable
private fun rememberNavBarSize(): Dp {
    val density = LocalDensity.current
    val screenWidthPx = LocalConfiguration.current.screenWidthDp.let {
        (it * density.density).toInt()
    }
    val navBarPx = CarViewport.navBarWidthPx(density.density, screenWidthPx)
    return with(density) { navBarPx.toDp() }
}

/**
 * The nav actions for both layouts (audit R3-DRY-16, issue #152-PA).
 *
 * The rail renders them Home/Back/Recent/Eject top-down; the bottom bar renders
 * them reversed (Eject/Recent/Back/Home) so that rotating the device keeps each
 * button on the same physical side. [modifier] supplies the per-layout sizing
 * (vertical padding for the rail, `weight(1f)` for the bar), so the button set
 * itself stays defined in one place.
 *
 * [connected] gates only the new Recent key (see [NavBarLayout.isEnabled]); the
 * other three buttons keep their pre-#152 behaviour.
 */
@Composable
private fun NavActionButtons(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecent: () -> Unit,
    onDisconnect: () -> Unit,
    connected: Boolean,
    reverse: Boolean = false,
    modifier: Modifier = Modifier
) {
    for (action in NavBarLayout.actions(reverse)) {
        val enabled = NavBarLayout.isEnabled(action, connected)
        when (action) {
            NavAction.Home -> NavActionButton(
                icon = Icons.Default.Home,
                label = stringResource(R.string.nav_home),
                onClick = onHome,
                enabled = enabled,
                modifier = modifier
            )
            NavAction.Back -> NavActionButton(
                icon = Icons.Default.ArrowBack,
                label = stringResource(R.string.nav_back),
                onClick = onBack,
                enabled = enabled,
                modifier = modifier
            )
            NavAction.Recent -> NavActionButton(
                icon = Icons.Default.History,
                label = stringResource(R.string.nav_recent),
                onClick = onRecent,
                enabled = enabled,
                modifier = modifier
            )
            NavAction.Eject -> NavActionButton(
                icon = Icons.Default.LinkOff,
                label = stringResource(R.string.nav_eject),
                onClick = onDisconnect,
                enabled = enabled,
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier
            )
        }
    }
}

/**
 * @param chips 常驻信息 chip（时间 / 海拔 / 天气）。issue #54 起横屏由 [CarShell] 传入，
 *   与导航键同栏渲染；因为它在导航栏里，镜像页（`Screen.APP`）也不会被裁掉。
 *   保持默认空列表可让其它调用点维持原样。
 * @param connected 真正连上手机（`STREAMING`/`CONNECTED`）时为 true，用于闸住「最近」键。
 */
@Composable
fun PersistentNavBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecent: () -> Unit,
    onDisconnect: () -> Unit,
    connected: Boolean,
    modifier: Modifier = Modifier,
    chips: List<InfoChip> = emptyList()
) {
    val navBarDp = rememberNavBarSize()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 有 chip 时 chip 与按键作为一个居中的整组，避免按键位置被顶到栏底后跳变。
        // issue #152-PA：第 4 个键加入后这里从 8dp 收到 6dp，配合按键 padding 收紧，
        // 保证 1280x800 车机（density 2.4 时仅约 333dp 可用高）里 4 键 + 3 个 chip 不越界。
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier
            .width(navBarDp)
            .fillMaxHeight()
            .background(NavBarBackgroundColor)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        InfoChipColumn(chips = chips, modifier = Modifier.padding(bottom = 4.dp))
        NavActionButtons(
            onBack = onBack, onHome = onHome, onRecent = onRecent,
            onDisconnect = onDisconnect, connected = connected,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
fun PersistentBottomNavBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecent: () -> Unit,
    onDisconnect: () -> Unit,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val navBarDp = rememberNavBarSize()

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(navBarDp)
            .background(NavBarBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Reversed order on the bottom bar (Eject, Recent, Back, Home) to mirror
        // the rail's top-down order when rotated; weight(1f) spreads the keys.
        NavActionButtons(
            onBack = onBack, onHome = onHome, onRecent = onRecent,
            onDisconnect = onDisconnect, connected = connected,
            reverse = true,
            modifier = Modifier.weight(1f)
        )
    }
}
