package com.maxrave.simpmusic.ui.screen

import com.maxrave.simpmusic.ui.component.RefineControlPlayingOn
import com.maxrave.simpmusic.ui.component.RefineControlButton
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.ThumbDown
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.ytm_dislike
import simpmusic.composeapp.generated.resources.ytm_like
import simpmusic.composeapp.generated.resources.ytm_liked
import simpmusic.composeapp.generated.resources.ytm_mute
import simpmusic.composeapp.generated.resources.ytm_next
import simpmusic.composeapp.generated.resources.ytm_open_player
import simpmusic.composeapp.generated.resources.ytm_pause
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_previous
import simpmusic.composeapp.generated.resources.ytm_repeat
import simpmusic.composeapp.generated.resources.ytm_shuffle
import simpmusic.composeapp.generated.resources.ytm_unmute

private val BarColor = Ytm.Surface
private val TextSecondary = Ytm.TextSecondary

/** YouTube Music's red, used on the web player's progress bar. */
private val YtmRedProgress = Color(0xFFFF0000)

/**
 * YouTube Music's mini player on a phone: a flat 64dp bar sitting on the navigation bar, with
 * cover, title and artist, play/pause and next, and a thin white progress line along its bottom.
 * A tap opens the player. A horizontal swipe changes the track; a downward swipe stops playback
 * and puts the bar away.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier,
    sharedViewModel: SharedViewModel = koinInject(),
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val openLabel = stringResource(Res.string.ytm_open_player)

    Column(
        modifier
            .background(BarColor)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .pointerInput(Unit) {
                // One gesture, locked to the direction it starts in: sideways changes the track,
                // up opens the player (as YouTube Music's bar does), down puts it away.
                var horizontal: Boolean? = null
                detectDragGestures(
                    onDragStart = { horizontal = null },
                    onDragEnd = {
                        when {
                            horizontal == true && dragX < -SWIPE_PX -> sharedViewModel.onUIEvent(UIEvent.Next)
                            // Always the previous song, never a restart of this one.
                            horizontal == true && dragX > SWIPE_PX -> sharedViewModel.onUIEvent(UIEvent.SkipToPrevious)
                            horizontal == false && dragY < -SWIPE_PX / 2 -> onClick()
                            horizontal == false && dragY > SWIPE_PX -> onClose()
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, amount ->
                    if (horizontal == null) horizontal = kotlin.math.abs(amount.x) > kotlin.math.abs(amount.y)
                    change.consume()
                    if (horizontal == true) dragX += amount.x else dragY += amount.y
                }
            },
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // The song follows the finger, so the swipe reads before it lets go.
                .graphicsLayer {
                    translationX = dragX * 0.6f
                    alpha = 1f - (kotlin.math.abs(dragX) / (SWIPE_PX * 4)).coerceIn(0f, 0.6f)
                }.padding(start = 8.dp, end = 4.dp),
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
            RefineControlButton(idleTint = TextSecondary)
            PlayPauseButton(controllerState.isPlaying, sharedViewModel)
            IconButton(
                onClick = { sharedViewModel.onUIEvent(UIEvent.Next) },
                enabled = controllerState.isNextAvailable,
            ) {
                Icon(
                    SimpIcons.SkipNext,
                    contentDescription = stringResource(Res.string.ytm_next),
                    tint = if (controllerState.isNextAvailable) Color.White else Color.White.copy(alpha = 0.38f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        ProgressLine(sharedViewModel, Color.White)
    }
}

/** How far a swipe on the mini player has to travel, in pixels. */
private const val SWIPE_PX = 120f

/**
 * YouTube Music's web player bar, used on Desktop: full width along the bottom of the window,
 * the progress bar in red across its top edge. Left: previous, play/pause, next and the time.
 * Centre: cover, title, artist, thumbs down and up. Right: volume, repeat, shuffle, and the
 * chevron that opens the player.
 *
 * The position is read only by the seek bar and the time ([DesktopSeek]), so the rest of the bar
 * does not recompose ten times a second.
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
    // Shared by the seek bar and the time text while dragging.
    val seeking = remember { mutableStateOf<Float?>(null) }

    Column(modifier.fillMaxWidth().background(BarColor)) {
        DesktopSeekBar(sharedViewModel, seeking)
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: transport and time.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Previous) }) {
                    Icon(SimpIcons.SkipPrevious, contentDescription = stringResource(Res.string.ytm_previous), tint = Color.White)
                }
                PlayPauseButton(controllerState.isPlaying, sharedViewModel, iconSize = 36)
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Next) }) {
                    Icon(SimpIcons.SkipNext, contentDescription = stringResource(Res.string.ytm_next), tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                DesktopTime(sharedViewModel, seeking.value)
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
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Dislike) }) {
                    Icon(SimpIcons.ThumbDown, contentDescription = stringResource(Res.string.ytm_dislike), tint = TextSecondary)
                }
                IconButton(
                    onClick = { sharedViewModel.onUIEvent(UIEvent.ToggleLike) },
                    modifier = Modifier.semantics { selected = controllerState.isLiked },
                ) {
                    Icon(
                        SimpIcons.ThumbUp,
                        contentDescription = stringResource(if (controllerState.isLiked) Res.string.ytm_liked else Res.string.ytm_like),
                        tint = if (controllerState.isLiked) Color.White else TextSecondary,
                    )
                }
                // The web bar's action menu: the song menu for what is playing.
                IconButton(onClick = { showMenu = true }) {
                    Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Color.White)
                }
            }
            // Right: volume, repeat, shuffle, open.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                RefineControlPlayingOn(Modifier.padding(end = 4.dp))
                RefineControlButton(idleTint = TextSecondary)
                VolumeControl(controllerState.volume) { sharedViewModel.onUIEvent(UIEvent.UpdateVolume(it)) }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Repeat) }) {
                    Icon(
                        if (controllerState.repeatState is RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                        contentDescription = stringResource(Res.string.ytm_repeat),
                        tint = if (controllerState.repeatState is RepeatState.None) TextSecondary else Color.White,
                    )
                }
                IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.Shuffle) }) {
                    Icon(
                        SimpIcons.Shuffle,
                        contentDescription = stringResource(Res.string.ytm_shuffle),
                        tint = if (controllerState.isShuffle) Color.White else TextSecondary,
                    )
                }
                IconButton(onClick = onOpenPlayer) {
                    Icon(
                        SimpIcons.KeyboardArrowDown,
                        contentDescription = stringResource(Res.string.ytm_open_player),
                        tint = Color.White,
                        // Points up while the player is closed, as the web's does.
                        modifier = Modifier.size(28.dp).rotate(180f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopSeekBar(
    sharedViewModel: SharedViewModel,
    seeking: androidx.compose.runtime.MutableState<Float?>,
) {
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    val progress = if (timeline.total > 0) (timeline.current.toFloat() / timeline.total).coerceIn(0f, 1f) else 0f
    Slider(
        value = seeking.value ?: progress,
        enabled = timeline.total > 0,
        onValueChange = { seeking.value = it },
        onValueChangeFinished = {
            seeking.value?.let { sharedViewModel.onUIEvent(UIEvent.UpdateProgress(it * 100f)) }
            seeking.value = null
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
                        disabledActiveTrackColor = YtmRedProgress,
                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
            )
        },
        modifier = Modifier.fillMaxWidth().height(12.dp),
    )
}

@Composable
private fun DesktopTime(
    sharedViewModel: SharedViewModel,
    seeking: Float?,
) {
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    val progress = if (timeline.total > 0) (timeline.current.toFloat() / timeline.total).coerceIn(0f, 1f) else 0f
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
    iconSize: Int = 30,
) {
    // Only the loading flag is read, so the button recomposes when it flips, not on every tick.
    val timeline = sharedViewModel.timeline.collectAsStateWithLifecycle()
    val loading by remember { derivedStateOf { timeline.value.loading } }
    IconButton(onClick = { sharedViewModel.onUIEvent(UIEvent.PlayPause) }, modifier = Modifier.size(48.dp)) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size((iconSize * 0.7f).dp))
        } else {
            Icon(
                if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                contentDescription = stringResource(if (isPlaying) Res.string.ytm_pause else Res.string.ytm_play),
                tint = Color.White,
                modifier = Modifier.size(iconSize.dp),
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
 * The mouse wheel over the icon or the slider moves the volume 5% per notch.
 */
private const val VOLUME_WHEEL_STEP = 0.05f

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
    val currentVolume by rememberUpdatedState(volume)
    val currentOnVolume by rememberUpdatedState(onVolume)
    Row(
        Modifier
            .hoverable(interaction)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val notches = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                        if (notches == 0f) continue
                        // Wheel up (negative delta) turns it up, as in YouTube Music on the web.
                        currentOnVolume((currentVolume - notches * VOLUME_WHEEL_STEP).coerceIn(0f, 1f))
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                contentDescription = stringResource(if (volume > 0f) Res.string.ytm_mute else Res.string.ytm_unmute),
                tint = if (shown) Color.White else TextSecondary,
            )
        }
    }
}

