package com.maxrave.simpmusic.ui.screen.player.content

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import com.maxrave.simpmusic.ui.component.ytm.subtitle
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.component.ytm.YtmCard
import com.maxrave.domain.data.model.home.HomeItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.expect.ui.PlatformCastButton
import com.maxrave.simpmusic.expect.ui.toImageBitmap
import com.maxrave.simpmusic.extension.formatDuration
import com.maxrave.simpmusic.ui.component.DraggableItem
import com.maxrave.simpmusic.ui.component.QueueItemBottomSheet
import com.maxrave.simpmusic.ui.component.rememberDragDropState
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.DragHandle
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.Repeat
import com.maxrave.simpmusic.ui.icon.RepeatOne
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.LyricsProvider
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToLong
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.maxrave.simpmusic.expect.ui.PlatformBackHandler
import com.maxrave.simpmusic.extension.KeepScreenOn
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.ThumbDown
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.ytm_close_player
import simpmusic.composeapp.generated.resources.ytm_dislike
import simpmusic.composeapp.generated.resources.ytm_like
import simpmusic.composeapp.generated.resources.ytm_liked
import simpmusic.composeapp.generated.resources.ytm_lyrics
import simpmusic.composeapp.generated.resources.ytm_lyrics_source
import simpmusic.composeapp.generated.resources.ytm_lyrics_unavailable
import simpmusic.composeapp.generated.resources.ytm_next
import simpmusic.composeapp.generated.resources.ytm_nothing_related
import simpmusic.composeapp.generated.resources.ytm_off
import simpmusic.composeapp.generated.resources.ytm_on
import simpmusic.composeapp.generated.resources.ytm_pause
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_playing_from
import simpmusic.composeapp.generated.resources.ytm_previous
import simpmusic.composeapp.generated.resources.ytm_related
import simpmusic.composeapp.generated.resources.ytm_reorder
import simpmusic.composeapp.generated.resources.ytm_repeat
import simpmusic.composeapp.generated.resources.ytm_repeat_all
import simpmusic.composeapp.generated.resources.ytm_repeat_one
import simpmusic.composeapp.generated.resources.ytm_save
import simpmusic.composeapp.generated.resources.ytm_share
import simpmusic.composeapp.generated.resources.ytm_shuffle
import simpmusic.composeapp.generated.resources.ytm_up_next

private val PageBlack = Ytm.Background
private val TextSecondary = Ytm.TextSecondary
private val SheetColor = Ytm.Surface

/** The three tabs along the bottom of YouTube Music's player, and of the sheet they open. */
private enum class PlayerTab(val label: StringResource) {
    UpNext(Res.string.ytm_up_next),
    Lyrics(Res.string.ytm_lyrics),
    Related(Res.string.ytm_related),
}

/**
 * The artwork colour, darkened when it is so light that white icons over it would be hard to
 * read (a white or yellow cover). YouTube Music's player stays dark whatever the cover.
 */
private fun Color.forPlayerBackground(): Color {
    if (this == Color.Black) return Color(0xFF3A3A3A)
    val lum = luminance()
    return if (lum > 0.35f) lerp(this, Color.Black, ((lum - 0.35f) / 0.65f * 0.6f).coerceIn(0f, 0.6f)) else this
}

/**
 * Now Playing, as YouTube Music lays it out:
 *
 * collapse · cast · more → square artwork, swipeable through the queue → title and artist →
 * action pills (like, save, share) → a thin white seek bar with times → shuffle · previous ·
 * the white play disc · next · repeat → UP NEXT · LYRICS · RELATED.
 *
 * A tab (or an upward drag on the tab row) slides YouTube Music's sheet over the player: a
 * collapsed player row on top, the three tabs with the selected one underlined, then that tab.
 * Lyrics are YouTube Music's: large bold left-aligned lines, the sung line white and the rest
 * dimmed, a tap on a line seeks to it, the source credited at the end.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingContentYtm(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    var openTab by rememberSaveable { mutableStateOf<PlayerTab?>(null) }
    val tint = state.startColor.value.forPlayerBackground()
    val background =
        remember(tint) {
            Brush.verticalGradient(
                0f to lerp(tint, PageBlack, 0.35f),
                0.55f to lerp(tint, PageBlack, 0.85f),
                1f to PageBlack,
            )
        }

    BoxWithConstraints(Modifier.fillMaxSize().background(PageBlack).background(background)) {
        // YouTube Music's web player on a wide window: the cover and controls on the left, the
        // Up next / Lyrics / Related panel always open on the right.
        if (maxWidth >= 840.dp) {
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(24.dp),
            ) {
                Column(
                    Modifier.weight(1.2f).fillMaxHeight().padding(end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TopBar(state, actions)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Artwork(state, actions) }
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.widthIn(max = 560.dp)) {
                        TitleBlock(state, actions)
                        Spacer(Modifier.height(16.dp))
                        ActionPills(state, actions)
                        Spacer(Modifier.height(16.dp))
                        SeekBar(state, actions)
                        Transport(state, actions)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val tab = openTab ?: PlayerTab.UpNext
                    TabRow(selected = tab, onSelect = { openTab = it })
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            PlayerTab.UpNext -> UpNextTab(state, actions)
                            PlayerTab.Lyrics -> LyricsTab(state, actions)
                            PlayerTab.Related -> RelatedTab(state, actions)
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(state, actions)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(state, actions)
            }
            Spacer(Modifier.height(24.dp))
            TitleBlock(state, actions)
            Spacer(Modifier.height(16.dp))
            ActionPills(state, actions)
            Spacer(Modifier.height(20.dp))
            SeekBar(state, actions)
            Spacer(Modifier.height(4.dp))
            Transport(state, actions)
            Spacer(Modifier.height(12.dp))
            TabRow(
                selected = null,
                onSelect = { openTab = it },
                modifier =
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f && openTab == null) openTab = PlayerTab.UpNext
                        }
                    },
            )
            Spacer(Modifier.height(4.dp))
        }

        // Back puts the Up next / Lyrics / Related sheet away before it closes the player.
        PlatformBackHandler(enabled = openTab != null) { openTab = null }
        AnimatedVisibility(
            visible = openTab != null,
            enter = slideInVertically(tween(280)) { it },
            exit = slideOutVertically(tween(240)) { it },
        ) {
            PlayerSheet(
                state = state,
                actions = actions,
                tab = openTab ?: PlayerTab.UpNext,
                tint = tint,
                onSelect = { openTab = it },
                onClose = { openTab = null },
            )
        }
    }
}

@Composable
private fun TopBar(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onDismiss) {
            Icon(state.dismissIcon, contentDescription = stringResource(Res.string.ytm_close_player), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        PlatformCastButton(tint = Color.White)
        IconButton(onClick = actions.onShowMoreSheet) {
            Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Color.White)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Artwork(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    val queue = state.artworkQueue
    // Square, as large as the space allows; YouTube Music keeps the cover and lets a horizontal
    // swipe skip, which is what the shared artwork pager already does.
    Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
        if (queue.isEmpty()) {
            CoverImage(state.screenData.thumbnailURL, onLoaded = actions.onArtworkBitmap)
        } else {
            HorizontalPager(
                state = state.artworkPagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                key = { page -> "$page-${queue.getOrNull(page)?.videoId}" },
            ) { page ->
                val isCurrent = page == state.currentOrderIndex
                val url =
                    if (isCurrent) {
                        state.screenData.thumbnailURL ?: queue.getOrNull(page)?.thumbnails?.lastOrNull()?.url
                    } else {
                        queue.getOrNull(page)?.thumbnails?.lastOrNull()?.url
                    }
                CoverImage(url, onLoaded = if (isCurrent) actions.onArtworkBitmap else null)
            }
        }
    }
}

@Composable
private fun CoverImage(
    url: String?,
    onLoaded: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)?,
    modifier: Modifier = Modifier.fillMaxSize(),
    corner: Int = 8,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCacheKey(url + "BIGGER")
                .size(ARTWORK_MAX_PX)
                .crossfade(300)
                .build(),
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { result -> onLoaded?.invoke(result.result.image.toImageBitmap()) },
        modifier = modifier.clip(RoundedCornerShape(corner.dp)),
    )
}

@Composable
private fun TitleBlock(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = state.screenData.nowPlayingTitle,
            style = typo().titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, animationMode = MarqueeAnimationMode.Immediately),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.screenData.artistName,
            style = typo().bodyLarge.copy(fontSize = 16.sp),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = actions.onNavigateToArtist),
        )
    }
}

/**
 * YouTube Music's row of pills under the title: thumbs up and down, save to a playlist, share.
 * The thumbs are the account's rating when signed in (see MediaPlayerHandler.toggleLike/dislike).
 */
@Composable
private fun ActionPills(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val liked = state.controllerState.isLiked
        Pill(
            icon = SimpIcons.ThumbUp,
            label = stringResource(if (liked) Res.string.ytm_liked else Res.string.ytm_like),
            active = liked,
            onClick = { actions.onUIEvent(UIEvent.ToggleLike) },
        )
        Pill(
            icon = SimpIcons.ThumbDown,
            label = stringResource(Res.string.ytm_dislike),
            onClick = { actions.onUIEvent(UIEvent.Dislike) },
        )
        Pill(icon = SimpIcons.PlaylistAdd, label = stringResource(Res.string.ytm_save), onClick = actions.onShowAddToPlaylist)
        val videoId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
        if (videoId != null) {
            Pill(
                icon = SimpIcons.Share,
                label = stringResource(Res.string.ytm_share),
                onClick = { shareUrl(state.screenData.nowPlayingTitle, "https://music.youtube.com/watch?v=$videoId") },
            )
        }
    }
}

@Composable
private fun Pill(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(if (active) Color.White else Color.White.copy(alpha = 0.12f))
                .toggleableIf(active, onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (active) Color.Black else Color.White
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = content, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    val timeline by state.timelineFlow.collectAsState()
    val total = timeline.total
    // While dragging, the thumb follows the finger; otherwise the song. Plain remember: a
    // restored "dragging" after rotation never got its finish and froze the bar.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (total > 0) (timeline.current.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown = if (total > 0) (total * fraction).roundToLong() else 0L
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            // Nothing to seek in until the song's length is known.
            enabled = total > 0,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { actions.onSeek(it * 100f) }
                dragging = null
            },
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            thumb = {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(3.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                        ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (total > 0) formatDuration(shown) else "0:00",
                style = typo().bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (total > 0) formatDuration(total) else "--:--",
                style = typo().bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun Transport(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    val controller = state.controllerState
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val on = stringResource(Res.string.ytm_on)
        val off = stringResource(Res.string.ytm_off)
        IconButton(
            onClick = { actions.onUIEvent(UIEvent.Shuffle) },
            modifier = Modifier.semantics { stateDescription = if (controller.isShuffle) on else off },
        ) {
            Icon(SimpIcons.Shuffle, contentDescription = stringResource(Res.string.ytm_shuffle), tint = if (controller.isShuffle) Color.White else TextSecondary)
        }
        IconButton(onClick = { actions.onUIEvent(UIEvent.Previous) }, modifier = Modifier.size(56.dp)) {
            Icon(SimpIcons.SkipPrevious, contentDescription = stringResource(Res.string.ytm_previous), tint = Color.White, modifier = Modifier.size(36.dp))
        }
        PlayDisc(state, actions, 72)
        IconButton(
            onClick = { actions.onUIEvent(UIEvent.Next) },
            enabled = controller.isNextAvailable,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                SimpIcons.SkipNext,
                contentDescription = stringResource(Res.string.ytm_next),
                tint = if (controller.isNextAvailable) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(36.dp),
            )
        }
        val repeatState =
            when (controller.repeatState) {
                is RepeatState.One -> stringResource(Res.string.ytm_repeat_one)
                is RepeatState.None -> off
                else -> stringResource(Res.string.ytm_repeat_all)
            }
        IconButton(
            onClick = { actions.onUIEvent(UIEvent.Repeat) },
            modifier = Modifier.semantics { stateDescription = repeatState },
        ) {
            Icon(
                if (controller.repeatState is RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                contentDescription = stringResource(Res.string.ytm_repeat),
                tint = if (controller.repeatState is RepeatState.None) TextSecondary else Color.White,
            )
        }
    }
}

/** The white play disc, also drawn small in the sheet's collapsed player. */
@Composable
private fun PlayDisc(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    side: Int,
) {
    // Only the loading flag matters here, so the disc recomposes when it flips, not every tick.
    val timeline = state.timelineFlow.collectAsState()
    val loading by remember { derivedStateOf { timeline.value.loading } }
    val label = stringResource(if (state.controllerState.isPlaying) Res.string.ytm_pause else Res.string.ytm_play)
    Box(
        Modifier
            .size(side.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(role = Role.Button, onClickLabel = label) { actions.onUIEvent(UIEvent.PlayPause) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size((side * 0.4f).dp))
        } else {
            Icon(
                if (state.controllerState.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size((side * 0.55f).dp),
            )
        }
    }
}

/** UP NEXT · LYRICS · RELATED. [selected] is null on the player itself, where no tab is open. */
@Composable
private fun TabRow(
    selected: PlayerTab?,
    onSelect: (PlayerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        PlayerTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                    .padding(top = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(tab.label).uppercase(),
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = if (selected == null || isSelected) Color.White else TextSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) Color.White else Color.Transparent),
                )
            }
        }
    }
}

/** YouTube Music's sheet: collapsed player, tabs, then the selected tab's content. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerSheet(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    tab: PlayerTab,
    tint: Color,
    onSelect: (PlayerTab) -> Unit,
    onClose: () -> Unit,
) {
    val sheetBackground = remember(tint) { lerp(tint, SheetColor, 0.8f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(PageBlack)
            .background(sheetBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // The collapsed player. A tap, or a downward drag, puts the sheet away.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClose)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 12f) onClose() }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(state.screenData.thumbnailURL, onLoaded = null, modifier = Modifier.size(44.dp), corner = 4)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.screenData.nowPlayingTitle,
                    style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.screenData.artistName,
                    style = typo().bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { actions.onUIEvent(UIEvent.PlayPause) }) {
                Icon(
                    if (state.controllerState.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                    contentDescription = stringResource(if (state.controllerState.isPlaying) Res.string.ytm_pause else Res.string.ytm_play),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        TabRow(selected = tab, onSelect = onSelect)
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PlayerTab.UpNext -> UpNextTab(state, actions)
                PlayerTab.Lyrics -> LyricsTab(state, actions)
                PlayerTab.Related -> RelatedTab(state, actions)
            }
        }
    }
}

/** Up next: "Playing from", then the whole queue with the playing track marked; long-press to reorder. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UpNextTab(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    musicServiceHandler: MediaPlayerHandler = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentOrderIndex).coerceAtLeast(0))
    val dragDropState = rememberDragDropState(listState) { from, to -> actions.onMoveQueueItem(from, to) }
    var overscrollJob by remember { mutableStateOf<Job?>(null) }
    // The song, not just its row: the queue can change (radio paging in) while the menu is open.
    var itemSheet by remember { mutableStateOf<Pair<String, Int>?>(null) }
    itemSheet?.let { (videoId, index) ->
        QueueItemBottomSheet(onDismiss = { itemSheet = null }, videoId = videoId, indexHint = index)
    }

    // Radio and endless queues page in as the end comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { nearEnd }.collect {
            if (it && musicServiceHandler.queueData.value?.queueState == QueueData.StateSource.STATE_INITIALIZED) {
                musicServiceHandler.loadMore()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.screenData.playlistName.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(Res.string.ytm_playing_from), style = typo().bodySmall, color = TextSecondary)
                Text(
                    state.screenData.playlistName,
                    style = typo().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, offset ->
                                change.consume()
                                dragDropState.onDrag(offset = offset)
                                if (overscrollJob?.isActive == true) return@detectDragGesturesAfterLongPress
                                dragDropState
                                    .checkForOverScroll()
                                    .takeIf { it != 0f }
                                    ?.let { delta ->
                                        overscrollJob =
                                            scope.launch {
                                                dragDropState.state.animateScrollBy(delta * 1.3f, tween(easing = FastOutLinearInEasing))
                                            }
                                    } ?: run { overscrollJob?.cancel() }
                            },
                            onDragStart = { dragDropState.onDragStart(it) },
                            onDragEnd = {
                                dragDropState.onDragInterrupted(true)
                                overscrollJob?.cancel()
                            },
                            onDragCancel = {
                                dragDropState.onDragInterrupted()
                                overscrollJob?.cancel()
                            },
                        )
                    },
        ) {
            itemsIndexed(state.artworkQueue, key = { index, track -> "$index${track.videoId}" }) { index, track ->
                DraggableItem(dragDropState = dragDropState, index = index, modifier = Modifier) { _ ->
                    QueueRow(
                        track = track,
                        isCurrent = index == state.currentOrderIndex,
                        isPlaying = state.controllerState.isPlaying,
                        onClick = { actions.onSeekToQueueIndex(index) },
                        onMore = { itemSheet = track.videoId to index },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = track.thumbnails?.lastOrNull()?.url,
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            )
            if (isCurrent) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) SimpIcons.GraphicEq else SimpIcons.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = typo().bodyLarge.copy(fontSize = 15.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(track.artists?.toListName()?.connectArtists(), track.duration).filter { it.isNotBlank() }.joinToString(" • "),
                style = typo().bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMore) {
            Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = TextSecondary)
        }
        Icon(SimpIcons.DragHandle, contentDescription = stringResource(Res.string.ytm_reorder), tint = TextSecondary, modifier = Modifier.padding(end = 12.dp))
    }
}

/**
 * YouTube Music's lyrics: large bold lines, left-aligned. Synced lyrics follow the song: the line
 * being sung is white, the rest are dimmed, the page keeps the sung line in view, and a tap on a
 * line plays from it. Unsynced lyrics are the same text, all white, scrolled by hand.
 */
@Composable
private fun LyricsTab(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    val data = state.screenData.lyricsData
    val lines = data?.lyrics?.lines.orEmpty()
    if (data == null || lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ytm_lyrics_unavailable), style = typo().bodyLarge, color = TextSecondary)
        }
        return
    }
    // Reading along: the screen stays on while lyrics are shown and the song plays.
    if (state.controllerState.isPlaying) KeepScreenOn()
    val synced = data.lyrics.syncType != null && data.lyrics.syncType != "UNSYNCED"
    val timeline = state.timelineFlow.collectAsState()
    val starts = remember(lines) { lines.map { it.startTimeMs.toLongOrNull() ?: 0L } }
    // Present only when translation is on in Settings. Matched to the original by start time,
    // or by position when the translation carries no usable times.
    val translatedLines = data.translatedLyrics?.first?.lines.orEmpty()
    val translations =
        remember(lines, translatedLines) {
            val byStart = translatedLines.associateBy { it.startTimeMs }
            lines.mapIndexed { index, line ->
                (byStart[line.startTimeMs] ?: translatedLines.getOrNull(index)?.takeIf { translatedLines.size == lines.size })
                    ?.words
                    ?.stripRichSyncTimestamps()
                    ?.takeIf { it.isNotBlank() && it.trim() != line.words.stripRichSyncTimestamps().trim() }
            }
        }
    // The sung line, recomputed per tick but only read (and recomposed) when it changes.
    val active by remember(starts, synced) {
        derivedStateOf {
            if (!synced) {
                -1
            } else {
                val now = timeline.value.current
                starts.indexOfLast { it <= now }.takeIf { now > 0 } ?: -1
            }
        }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // A hand scroll pauses following the song for a few seconds, as in YouTube Music, instead of
    // being yanked back on the next tick.
    var lastUserScroll by remember { mutableStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) lastUserScroll = Clock.System.now().toEpochMilliseconds()
        }
    }
    LaunchedEffect(synced) {
        if (!synced) return@LaunchedEffect
        snapshotFlow { active }.collect { index ->
            val sinceUser = Clock.System.now().toEpochMilliseconds() - lastUserScroll
            if (index >= 0 && (autoScrolling || sinceUser > LYRICS_FOLLOW_PAUSE_MS)) {
                autoScrolling = true
                try {
                    listState.animateScrollToItem(index, scrollOffset = -with(density) { 120.dp.roundToPx() })
                } finally {
                    autoScrolling = false
                }
            }
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val alpha by animateFloatAsState(
                when {
                    !synced -> 1f
                    index == active -> 1f
                    else -> 0.5f
                },
                label = "lyricAlpha",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = synced) {
                        val total = timeline.value.total
                        if (total > 0) actions.onSeek(starts[index] * 100f / total)
                    }.padding(vertical = 8.dp),
            ) {
                Text(
                    text = line.words.stripRichSyncTimestamps(),
                    style = typo().headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = alpha),
                )
                translations.getOrNull(index)?.let { translated ->
                    Text(
                        text = translated,
                        style = typo().bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                        color = Color.White.copy(alpha = alpha * 0.7f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        item(key = "source") {
            Text(
                stringResource(Res.string.ytm_lyrics_source, data.lyricsProvider.displayName()),
                style = typo().bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

private fun LyricsProvider.displayName(): String =
    when (this) {
        LyricsProvider.YOUTUBE -> "YouTube Music"
        LyricsProvider.LRCLIB -> "LRCLIB"
        LyricsProvider.SIMPMUSIC -> "SimpMusic Lyrics"
        LyricsProvider.BETTER_LYRICS -> "Better Lyrics"
        else -> name.lowercase().replaceFirstChar { it.uppercase() }
    }

/**
 * Related, as YouTube Music shows it: the song's own Related page from YouTube, shelf by shelf in
 * its order ("You might also like" as song rows, then playlists, similar artists, "More from").
 * A song starts its radio; anything else closes the player and opens its page.
 */
@Composable
private fun RelatedTab(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    songRepository: SongRepository = koinInject(),
) {
    val videoId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
    val related by produceState<List<HomeItem>?>(initialValue = null, videoId) {
        value =
            if (videoId == null) {
                emptyList()
            } else {
                (songRepository.getRelatedPage(videoId).lastOrNull() as? Resource.Success)?.data.orEmpty()
            }
    }
    val shelves = related
    if (shelves == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    if (shelves.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ytm_nothing_related), style = typo().bodyMedium, color = TextSecondary)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        shelves.forEach { shelf ->
            val contents = shelf.contents.filterNotNull()
            Text(
                shelf.title,
                style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
            if (shelf.isListShelf) {
                contents.forEach { content ->
                    YtmSongRow(
                        title = content.title,
                        subtitle = content.subtitle(),
                        thumbnail = content.thumbnails.lastOrNull()?.url,
                        onClick = { actions.onOpenContent(content) },
                    )
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(contents) { content ->
                        YtmCard(content = content, onClick = { actions.onOpenContent(content) }, width = 136.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Largest artwork decode, in pixels: enough for any phone or a desktop window's cover. */
private const val ARTWORK_MAX_PX = 1080

/** How long a hand scroll of the lyrics stops them following the song. */
private const val LYRICS_FOLLOW_PAUSE_MS = 3_000L

/** A pill that is on or off (Like) reads as a toggle; the others are plain buttons. */
private fun Modifier.toggleableIf(
    active: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(role = Role.Button, onClick = onClick).semantics { selected = active }
