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

private val PageBlack = Color(0xFF030303)
private val TextSecondary = Color(0xFFAAAAAA)
private val SheetColor = Color(0xFF212121)

/** The three tabs along the bottom of YouTube Music's player, and of the sheet they open. */
private enum class PlayerTab(val label: String) { UpNext("UP NEXT"), Lyrics("LYRICS"), Related("RELATED") }

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
    val tint = state.startColor.value.let { if (it == Color.Black) Color(0xFF3A3A3A) else it }
    val background =
        remember(tint) {
            Brush.verticalGradient(
                0f to lerp(tint, PageBlack, 0.35f),
                0.55f to lerp(tint, PageBlack, 0.85f),
                1f to PageBlack,
            )
        }

    // The toolbar flag belongs to the old scrolling styles; this page never scrolls.
    LaunchedEffect(Unit) { actions.onToolbarVisibilityChange(false) }

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
            Icon(state.dismissIcon, contentDescription = "Close", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        PlatformCastButton(tint = Color.White)
        IconButton(onClick = actions.onShowMoreSheet) {
            Icon(SimpIcons.MoreVert, contentDescription = "More", tint = Color.White)
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
                key = { page -> queue.getOrNull(page)?.videoId ?: page },
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

/** YouTube Music's row of pills under the title: like, save to a playlist, share. */
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
            label = if (liked) "Liked" else "Like",
            active = liked,
            onClick = { actions.onUIEvent(UIEvent.ToggleLike) },
        )
        Pill(icon = SimpIcons.PlaylistAdd, label = "Save", onClick = actions.onShowAddToPlaylist)
        val videoId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
        if (videoId != null) {
            Pill(
                icon = SimpIcons.Share,
                label = "Share",
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
                .clip(CircleShape)
                .background(if (active) Color.White else Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
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
    val total = state.timelineState.total
    val shown = if (total > 0) (total * (state.sliderValue / 100f)).roundToLong() else 0L
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = (state.sliderValue / 100f).coerceIn(0f, 1f),
            onValueChange = { actions.onSliderChange(it * 100f) },
            onValueChangeFinished = actions.onSliderChangeFinished,
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
        IconButton(onClick = { actions.onUIEvent(UIEvent.Shuffle) }) {
            Icon(SimpIcons.Shuffle, contentDescription = "Shuffle", tint = if (controller.isShuffle) Color.White else TextSecondary)
        }
        IconButton(onClick = { actions.onUIEvent(UIEvent.Previous) }, modifier = Modifier.size(56.dp)) {
            Icon(SimpIcons.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
        }
        PlayDisc(state, actions, 72)
        IconButton(
            onClick = { actions.onUIEvent(UIEvent.Next) },
            enabled = controller.isNextAvailable,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                SimpIcons.SkipNext,
                contentDescription = "Next",
                tint = if (controller.isNextAvailable) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = { actions.onUIEvent(UIEvent.Repeat) }) {
            Icon(
                if (controller.repeatState is RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                contentDescription = "Repeat",
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
    Box(
        Modifier
            .size(side.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { actions.onUIEvent(UIEvent.PlayPause) },
        contentAlignment = Alignment.Center,
    ) {
        if (state.timelineState.loading) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size((side * 0.4f).dp))
        } else {
            Icon(
                if (state.controllerState.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                contentDescription = if (state.controllerState.isPlaying) "Pause" else "Play",
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
                    .clickable { onSelect(tab) }
                    .padding(top = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.label,
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
                    contentDescription = if (state.controllerState.isPlaying) "Pause" else "Play",
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
    var itemSheetIndex by remember { mutableStateOf(-1) }
    if (itemSheetIndex >= 0) {
        QueueItemBottomSheet(onDismiss = { itemSheetIndex = -1 }, index = itemSheetIndex)
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
                Text("Playing from", style = typo().bodySmall, color = TextSecondary)
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
                        onMore = { itemSheetIndex = index },
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
            Icon(SimpIcons.MoreVert, contentDescription = "More", tint = TextSecondary)
        }
        Icon(SimpIcons.DragHandle, contentDescription = "Reorder", tint = TextSecondary, modifier = Modifier.padding(end = 12.dp))
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
            Text("Lyrics not available", style = typo().bodyLarge, color = TextSecondary)
        }
        return
    }
    val synced = data.lyrics.syncType != null && data.lyrics.syncType != "UNSYNCED"
    val timeline by state.timelineFlow.collectAsState()
    val starts = remember(lines) { lines.map { it.startTimeMs.toLongOrNull() ?: 0L } }
    val active =
        if (!synced) {
            -1
        } else {
            val now = timeline.current
            starts.indexOfLast { it <= now }.takeIf { now > 0 } ?: -1
        }
    val listState = rememberLazyListState()
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(synced) {
        if (!synced) return@LaunchedEffect
        snapshotFlow { currentActive }.collect { index ->
            if (index >= 0) listState.animateScrollToItem(index, scrollOffset = -200)
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
            Text(
                text = line.words.stripRichSyncTimestamps(),
                style = typo().headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                color = Color.White.copy(alpha = alpha),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = synced && timeline.total > 0) {
                            val start = starts[index]
                            actions.onUIEvent(UIEvent.UpdateProgress(start * 100f / timeline.total))
                        }.padding(vertical = 8.dp),
            )
        }
        item(key = "source") {
            Text(
                "Source: ${data.lyricsProvider.displayName()}",
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
            Text("Nothing related to show", style = typo().bodyMedium, color = TextSecondary)
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
