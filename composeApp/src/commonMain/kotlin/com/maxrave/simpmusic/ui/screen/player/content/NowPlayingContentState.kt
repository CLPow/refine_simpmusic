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

// Word-by-word lyrics carry a timestamp per word; replace each with a space
// (not ""), then collapse — otherwise the words run together. Shared with the Android Auto
// overlay through the domain function this delegates to.
internal fun String.stripRichSyncTimestamps(): String = stripRichSyncTimestamps(this)

/**
 * Everything the player reads. The shell ([com.maxrave.simpmusic.ui.screen.player.NowPlayingScreenContent])
 * owns the ViewModel collection, the palette and the artwork pager's state machine; the content
 * only renders from this snapshot.
 *
 * The playback position is deliberately not in here, only its flow: the position ticks ten times
 * a second, and carrying it in this snapshot recomposed the whole player on every tick. The seek
 * bar, the play disc and the lyrics read [timelineFlow] themselves.
 */
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

/**
 * Everything the player can do. All callbacks land in the shell, which owns the ViewModel, the
 * navController and the sheet flags.
 */
@Stable
class NowPlayingContentActions(
    val onUIEvent: (UIEvent) -> Unit,
    val onSeekToQueueIndex: (Int) -> Unit,
    val onArtworkBitmap: (ImageBitmap) -> Unit,
    /** Seeks to [percent] (0–100) of the song. */
    val onSeek: (percent: Float) -> Unit,
    val onNavigateToArtist: () -> Unit,
    val onShowMoreSheet: () -> Unit,
    val onShowAddToPlaylist: () -> Unit,
    val onDismiss: () -> Unit,
    /** Reorders the queue. `from`/`to` are absolute indices into [NowPlayingContentState.artworkQueue]. */
    val onMoveQueueItem: (from: Int, to: Int) -> Unit,
    /** Opens a Related item: a song plays its radio; an album, artist or playlist closes the player and opens its page. */
    val onOpenContent: (Content) -> Unit = {},
)
