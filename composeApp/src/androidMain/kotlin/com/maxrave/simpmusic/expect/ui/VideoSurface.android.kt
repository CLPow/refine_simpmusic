package com.maxrave.simpmusic.expect.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.maxrave.common.Config
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

@OptIn(UnstableApi::class)
@Composable
actual fun VideoSurface(modifier: Modifier) {
    val player = koinInject<Player>(named(Config.MAIN_PLAYER))
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    ContentFrame(player = player, modifier = modifier, contentScale = ContentScale.Fit)
}
