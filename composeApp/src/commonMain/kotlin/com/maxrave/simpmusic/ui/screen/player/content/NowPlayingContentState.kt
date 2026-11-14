package com.maxrave.simpmusic.ui.screen.player.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.domain.lyrics.stripRichSyncTimestamps
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.simpmusic.viewModel.NowPlayingScreenData
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.flow.StateFlow

internal fun String.stripRichSyncTimestamps(): String = stripRichSyncTimestamps(this)

@Stable
class NowPlayingContentState(
    val screenData: NowPlayingScreenData,
    val controllerState: ControlState,
    val timelineFlow: StateFlow<TimeLine>,
    val isUserLoggedIn: Boolean,
    val artworkQueue: List<Track>,
    val currentOrderIndex: Int,
    val artworkPagerState: PagerState,
    val startColor: Animatable<Color, AnimationVector4D>,
    val dismissIcon: ImageVector,
)

@Stable
class NowPlayingContentActions(
    val onUIEvent: (UIEvent) -> Unit,
    val onSeekToQueueIndex: (Int) -> Unit,
    val onArtworkBitmap: (ImageBitmap) -> Unit,
    val onSeek: (percent: Float) -> Unit,
    val onNavigateToArtist: () -> Unit,
    val onShowMoreSheet: () -> Unit,
    val onShowAddToPlaylist: () -> Unit,
    val onDismiss: () -> Unit,
    val onMoveQueueItem: (from: Int, to: Int) -> Unit,
    val onOpenContent: (Content) -> Unit = {},
)
