package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.dilinkauto.protocol.MediaAction
import com.dilinkauto.protocol.PlaybackState
import com.dilinkauto.server.service.CarConnectionService

/**
 * Home screen content: app grid (or connection status) + now playing bar.
 * No navigation controls — those are in the persistent nav bar.
 */
@Composable
fun HomeContent(
    service: CarConnectionService,
    onAppClick: (String) -> Unit
) {
    val state by service.state.collectAsState()
    val appList by service.appList.collectAsState()
    val mediaMetadata by service.mediaMetadata.collectAsState()
    val playbackState by service.playbackState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Main content
        if (state == CarConnectionService.State.STREAMING && appList.isNotEmpty()) {
            AppGrid(
                apps = appList,
                onAppClick = onAppClick,
                service = service,
                modifier = Modifier.weight(1f)
            )
        } else {
            ConnectionStatus(
                state = state,
                modifier = Modifier.weight(1f),
                onManualConnect = { ip -> service.connectManual(ip) }
            )
        }

        // Now playing bar
        mediaMetadata?.let { metadata ->
            NowPlayingBar(
                metadata = metadata,
                playbackState = playbackState,
                onPlayPause = {
                    val action = if (playbackState?.state == PlaybackState.PLAYING)
                        MediaAction.PAUSE else MediaAction.PLAY
                    service.sendMediaAction(action)
                },
                onNext = { service.sendMediaAction(MediaAction.NEXT) },
                onPrevious = { service.sendMediaAction(MediaAction.PREVIOUS) }
            )
        }
    }
}
