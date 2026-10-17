package com.maxrave.simpmusic.ui.screen

import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import androidx.compose.ui.draw.rotate
import androidx.navigation.NavController
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.simpmusic.expect.ui.PlatformBackdrop
import com.maxrave.simpmusic.extension.formatDuration
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.KeyboardArrowDown
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Repeat
import com.maxrave.simpmusic.ui.icon.RepeatOne
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.icon.VolumeOff
import com.maxrave.simpmusic.ui.icon.VolumeUp
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.koin.compose.koinInject

private val BarColor = Color(0xFF212121)
private val TextSecondary = Color(0xFFAAAAAA)

/** YouTube Music's red, used on the web player's progress bar. */
private val YtmRedProgress = Color(0xFFFF0000)

/**
 * YouTube Music's mini player on a phone: a flat 64dp bar sitting on the navigation bar, with
 * cover, title and artist, play/pause and next, and a thin white progress line along its bottom.
 * A tap opens the player. A horizontal swipe changes the track; a downward swipe stops playback
 * and puts the bar away.
 *
 * [backdrop] is unused; it stays in the signature for callers that still pass one.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier,
    backdrop: PlatformBackdrop? = null,
    sharedViewModel: SharedViewModel = koinInject(),
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier
            .background(BarColor)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX < -120f -> sharedViewModel.onUIEvent(UIEvent.Next)
                            dragX > 120f -> sharedViewModel.onUIEvent(UIEvent.Previous)
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { _, amount -> dragX += amount }
            }.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragY > 120f) onClose()
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                ) { _, amount -> dragY += amount }
            },
    ) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(screenData.thumbnailURL, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    screenData.nowPlayingTitle,
                    style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    screenData.artistName,
                    style = typo().bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPauseButton(controllerState.isPlaying, sharedViewModel)
            IconButton(
                onClick = { sharedViewModel.onUIEvent(UIEvent.Next) },
                enabled = controllerState.isNextAvailable,
            ) {
                Icon(
                    SimpIcons.SkipNext,
                    contentDescription = "Next",
                    tint = if (controllerState.isNextAvailable) Color.White else Color.White.copy(alpha = 0.38f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        ProgressLine(sharedViewModel, Color.White)
    }
}

/**
 * YouTube Music's web player bar, used on Desktop: full width along the bottom of the window,
 * the progress bar in red across its top edge. Left: previous, play/pause, next and the time.
 * Centre: cover, title, artist, like. Right: volume, repeat, shuffle, and the chevron that opens
 * the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopPlayerBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    sharedViewModel: SharedViewModel = koinInject(),
    onOpenPlayer: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    if (showMenu) {
        NowPlayingBottomSheet(onDismiss = { showMenu = false }, navController = navController, song = null, setSleepTimerEnable = true)
    }
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }

    Column(modifier.fillMaxWidth().background(BarColor)) {
        val progress = if (timeline.total > 0) (timeline.current.toFloat() / timeline.total).coerceIn(0f, 1f) else 0f
        Slider(
            value = seeking ?: progress,
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.let { sharedViewModel.onUIEvent(UIEvent.UpdateProgress(it * 100f)) }
                seeking = null
            },
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(YtmRedProgress)) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(2.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor = YtmRedProgress,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                        ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(12.dp),
        )
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: transport and time.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Previous) }) {
                    Icon(SimpIcons.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.PlayPause) }, modifier = Modifier.size(48.dp)) {
                    if (timeline.loading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            if (controllerState.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                            contentDescription = if (controllerState.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Next) }) {
                    Icon(SimpIcons.SkipNext, contentDescription = "Next", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (timeline.total > 0) {
                        "${formatDuration(((seeking ?: progress) * timeline.total).toLong())} / ${formatDuration(timeline.total)}"
                    } else {
                        "0:00 / 0:00"
                    },
                    style = typo().bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            // Centre: the track.
            Row(
                Modifier.weight(1.4f).clickable(onClick = onOpenPlayer),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Cover(screenData.thumbnailURL, 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.widthIn(max = 360.dp).weight(1f, fill = false)) {
                    Text(
                        screenData.nowPlayingTitle,
                        style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        screenData.artistName,
                        style = typo().bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.ToggleLike) }) {
                    Icon(
                        SimpIcons.ThumbUp,
                        contentDescription = "Like",
                        tint = if (controllerState.isLiked) Color.White else TextSecondary,
                    )
                }
                // The web bar's action menu: the song menu for what is playing.
                IconButton(onClick = { showMenu = true }) {
                    Icon(SimpIcons.MoreVert, contentDescription = "Action menu", tint = Color.White)
                }
            }
            // Right: volume, repeat, shuffle, open.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                VolumeControl(controllerState.volume) { sharedViewModel.onUIEvent(UIEvent.UpdateVolume(it)) }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Repeat) }) {
                    Icon(
                        if (controllerState.repeatState is RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (controllerState.repeatState is RepeatState.None) TextSecondary else Color.White,
                    )
                }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Shuffle) }) {
                    Icon(
                        SimpIcons.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (controllerState.isShuffle) Color.White else TextSecondary,
                    )
                }
                IconButton(onClick = onOpenPlayer) {
                    Icon(
                        SimpIcons.KeyboardArrowDown,
                        contentDescription = "Open player",
                        tint = Color.White,
                        // Points up while the player is closed, as the web's does.
                        modifier = Modifier.size(28.dp).rotate(180f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(
    url: String?,
    side: Int,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCacheKey(url)
                .crossfade(200)
                .build(),
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(side.dp).clip(RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    sharedViewModel: SharedViewModel,
) {
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.PlayPause) }) {
        if (timeline.loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Icon(
                if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** The 2dp progress line. Reads the timeline itself so only it recomposes on each tick. */
@Composable
private fun ProgressLine(
    sharedViewModel: SharedViewModel,
    color: Color,
) {
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    val fraction = if (timeline.total > 0) (timeline.current.toFloat() / timeline.total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

/**
 * YouTube Music's web volume control: the speaker icon on its own, the slider sliding out beside
 * it while the pointer is over either. A click on the icon mutes, and a second click restores the
 * level from before. The slider follows the drag locally, so it never lags behind the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeControl(
    volume: Float,
    onVolume: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var beforeMute by remember { mutableFloatStateOf(volume.takeIf { it > 0f } ?: 1f) }
    val shown = hovered || dragging != null
    Row(Modifier.hoverable(interaction), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = shown, enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(), exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()) {
            Slider(
                value = dragging ?: volume,
                onValueChange = {
                    dragging = it
                    onVolume(it)
                },
                onValueChangeFinished = { dragging = null },
                thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier.height(3.dp),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                        colors =
                            SliderDefaults.colors(
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                    )
                },
                modifier = Modifier.width(100.dp).padding(start = 8.dp),
            )
        }
        IconButton(onClick = {
            if (volume > 0f) {
                beforeMute = volume
                onVolume(0f)
            } else {
                onVolume(beforeMute)
            }
        }) {
            Icon(
                if (volume > 0f) SimpIcons.VolumeUp else SimpIcons.VolumeOff,
                contentDescription = if (volume > 0f) "Mute" else "Unmute",
                tint = if (shown) Color.White else TextSecondary,
            )
        }
    }
}

