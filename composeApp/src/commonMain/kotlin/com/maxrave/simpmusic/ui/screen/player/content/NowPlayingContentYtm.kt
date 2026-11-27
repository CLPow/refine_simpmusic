package com.maxrave.simpmusic.ui.screen.player.content

import com.maxrave.domain.mediaservice.PodcastPlayback
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.simpmusic.ui.icon.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.maxrave.domain.mediaservice.RenditionController
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.simpmusic.expect.ui.VideoSurface
import com.maxrave.simpmusic.expect.ui.FullscreenOrientation
import com.maxrave.simpmusic.ui.icon.Fullscreen
import com.maxrave.simpmusic.ui.icon.FullscreenExit
import simpmusic.composeapp.generated.resources.ytm_mode_song
import simpmusic.composeapp.generated.resources.ytm_mode_video
import simpmusic.composeapp.generated.resources.ytm_fullscreen
import simpmusic.composeapp.generated.resources.ytm_exit_fullscreen
import simpmusic.composeapp.generated.resources.ytm_comments
import com.maxrave.simpmusic.ui.icon.Comment
import com.maxrave.simpmusic.ui.component.ytm.rememberCommentsState
import com.maxrave.simpmusic.ui.component.ytm.CommentsSheet
import com.maxrave.simpmusic.ui.component.RefineControlPlayingOn
import com.maxrave.simpmusic.ui.component.RefineControlButton
import com.maxrave.simpmusic.ui.component.ytm.YtmChip
import com.maxrave.domain.data.model.queue.RadioChip
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.simpmusic.viewModel.SongSelectionViewModel
import com.maxrave.simpmusic.ui.component.AddToPlaylistModalBottomSheet
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import simpmusic.composeapp.generated.resources.ytm_autoplay
import simpmusic.composeapp.generated.resources.ytm_autoplay_summary
import simpmusic.composeapp.generated.resources.ytm_autoplay_off_message
import androidx.compose.foundation.gestures.detectTapGestures
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.delay
import simpmusic.composeapp.generated.resources.ytm_n_seconds
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
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
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

private enum class PlayerTab(val label: StringResource) {
    UpNext(Res.string.ytm_up_next),
    Lyrics(Res.string.ytm_lyrics),
    Related(Res.string.ytm_related),
}

private fun Color.forPlayerBackground(): Color {
    if (this == Color.Black) return Color(0xFF3A3A3A)
    val lum = luminance()
    return if (lum > 0.35f) lerp(this, Color.Black, ((lum - 0.35f) / 0.65f * 0.6f).coerceIn(0f, 0.6f)) else this
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingContentYtm(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    var openTab by rememberSaveable { mutableStateOf<PlayerTab?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val renditionController: RenditionController = koinInject()
    val renditions by renditionController.current.collectAsStateWithLifecycle()
    val showingVideo = renditions?.playingVideo == true
    LaunchedEffect(showingVideo) { if (!showingVideo) fullscreen = false }
    val tint = state.startColor.value.forPlayerBackground()
    val background =
        remember(tint) {
            Brush.verticalGradient(
                0f to lerp(tint, PageBlack, 0.35f),
                0.55f to lerp(tint, PageBlack, 0.85f),
                1f to PageBlack,
            )
        }

    if (fullscreen) {
        FullscreenVideo(state, actions, onExit = { fullscreen = false })
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(PageBlack).background(background)) {
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
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Artwork(state, actions, showingVideo, onFullscreen = { fullscreen = true })
                    }
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
                Artwork(state, actions, showingVideo, onFullscreen = { fullscreen = true })
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
private fun SongVideoSwitch(controller: RenditionController) {
    val renditions by controller.current.collectAsStateWithLifecycle()
    val videoMode by controller.videoMode.collectAsStateWithLifecycle()
    val current = renditions ?: return
    if (current.videoId == null) return
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false, true).forEach { video ->
            val selected = video == videoMode
            Text(
                text = stringResource(if (video) Res.string.ytm_mode_video else Res.string.ytm_mode_song),
                style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) Color.Black else Color.White.copy(alpha = 0.9f),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else Color.Transparent)
                        .clickable(enabled = !selected) { controller.setVideoMode(video) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FullscreenVideo(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    onExit: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3_000)
            controlsVisible = false
        }
    }
    PlatformBackHandler(enabled = true) { onExit() }
    FullscreenOrientation()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
    ) {
        VideoSurface(Modifier.fillMaxSize())
        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp),
                ) {
                    Icon(SimpIcons.FullscreenExit, contentDescription = stringResource(Res.string.ytm_exit_fullscreen), tint = Color.White)
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    SeekBar(state, actions)
                    Transport(state, actions)
                }
            }
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val control: RefineControlRepository = koinInject()
            val target by control.target.collectAsStateWithLifecycle()
            if (target is ControlTarget.Remote) RefineControlPlayingOn() else SongVideoSwitch(koinInject())
        }
        RefineControlButton()
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
    showingVideo: Boolean,
    onFullscreen: () -> Unit,
) {
    val queue = state.artworkQueue
    val dataStoreManager: DataStoreManager = koinInject()
    val seekSeconds by dataStoreManager.doubleTapSeekSeconds.collectAsState(10)
    var seekFlash by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(650)
            seekFlash = null
        }
    }
    Box(
        Modifier
            .widthIn(max = if (showingVideo) 960.dp else 520.dp)
            .fillMaxWidth()
            .aspectRatio(if (showingVideo) 16f / 9f else 1f, matchHeightConstraintsFirst = true)
            .pointerInput(seekSeconds) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val timeline = state.timelineFlow.value
                        if (timeline.total > 0) {
                            val forward = offset.x > size.width / 2f
                            val step = seekSeconds * 1000L * (if (forward) 1 else -1)
                            val target = (timeline.current + step).coerceIn(0L, timeline.total)
                            actions.onSeek(target * 100f / timeline.total)
                            seekFlash = forward to Clock.System.now().toEpochMilliseconds()
                        }
                    },
                )
            },
    ) {
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
                if (isCurrent && showingVideo) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                        VideoSurface(Modifier.fillMaxSize())
                    }
                } else {
                    CoverImage(url, onLoaded = if (isCurrent) actions.onArtworkBitmap else null)
                }
            }
        }
        if (showingVideo) {
            IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.BottomEnd)) {
                Icon(SimpIcons.Fullscreen, contentDescription = stringResource(Res.string.ytm_fullscreen), tint = Color.White)
            }
        }
        seekFlash?.let { (forward, _) ->
            Text(
                text = (if (forward) "+" else "−") + stringResource(Res.string.ytm_n_seconds, seekSeconds),
                style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier =
                    Modifier
                        .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
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

@Composable
private fun ActionPills(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = state.artworkQueue.getOrNull(state.currentOrderIndex)
        if (MusicVideoType.isPodcast(current?.videoType)) SpeedPill()
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
        val videoId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
        if (videoId != null) {
            val comments = rememberCommentsState(videoId)
            var showComments by remember { mutableStateOf(false) }
            if (!comments.turnedOff) {
                Pill(
                    icon = SimpIcons.Comment,
                    label = comments.header?.countShort ?: stringResource(Res.string.ytm_comments),
                    onClick = { showComments = true },
                )
            }
            if (showComments) CommentsSheet(comments, onDismiss = { showComments = false })
        }
        Pill(icon = SimpIcons.PlaylistAdd, label = stringResource(Res.string.ytm_save), onClick = actions.onShowAddToPlaylist)
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
private fun SpeedPill() {
    val dataStoreManager: DataStoreManager = koinInject()
    val scope = rememberCoroutineScope()
    val speed by dataStoreManager.playbackSpeed.collectAsState(1f)
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(icon = SimpIcons.Speed, label = speedLabel(speed), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PodcastPlayback.SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(speedLabel(option), fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        scope.launch {
                            dataStoreManager.putString(PodcastPlayback.SPEED_KEY, option.toString())
                            dataStoreManager.setPlaybackSpeed(option)
                        }
                    },
                )
            }
        }
    }
}

private fun speedLabel(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "${text}\u00D7"
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
    // Plain remember, not rememberSaveable: a drag restored after rotation never finishes.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (total > 0) (timeline.current.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown = if (total > 0) (total * fraction).roundToLong() else 0L
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
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

@Composable
private fun PlayDisc(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    side: Int,
) {
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UpNextTab(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
    musicServiceHandler: MediaPlayerHandler = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val dataStoreManager: DataStoreManager = koinInject()
    val songRepository: SongRepository = koinInject()
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    val queueData by musicServiceHandler.queueData.collectAsState()
    val autoplayStart = queueData?.data?.autoplayStartIndex
    val radioId = queueData?.data?.playlistId?.takeIf { it.isRadioQueueId() }
    val autoplayOn by dataStoreManager.endlessQueue.map { it == DataStoreManager.TRUE }.collectAsState(true)
    val autoplayOffMessage = stringResource(Res.string.ytm_autoplay_off_message)
    val currentId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
    val seed = remember(radioId) { radioId?.removePrefix("RDAMVM")?.takeIf { radioId.startsWith("RDAMVM") && it.length == 11 } ?: currentId }
    val chips by produceState(emptyList<RadioChip>(), radioId) {
        value = if (radioId != null && seed != null) songRepository.getRadioChips(seed, radioId) else emptyList()
    }
    var tuning by remember(radioId) { mutableStateOf<String?>(null) }
    val showAutoplayHeader = queueData?.data?.isRadio != true && !state.controllerState.isShuffle
    var savingQueue by remember { mutableStateOf(false) }
    val onAutoplayChange: (Boolean) -> Unit = { enabled ->
        scope.launch { dataStoreManager.setEndlessQueue(enabled) }
        if (!enabled) showToast(autoplayOffMessage, ToastGravity.Bottom)
    }
    if (savingQueue) {
        val localPlaylists by selectionViewModel.listLocalPlaylist.collectAsStateWithLifecycle()
        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = true,
            listLocalPlaylist = localPlaylists,
            listYouTubePlaylist = emptyList(),
            onDismiss = { savingQueue = false },
            onClick = { playlist ->
                selectionViewModel.saveTracksToPlaylist(playlist.id, state.artworkQueue)
                savingQueue = false
            },
            onYTPlaylistClick = {},
        )
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentOrderIndex).coerceAtLeast(0))
    val dragDropState = rememberDragDropState(listState) { from, to -> actions.onMoveQueueItem(from, to) }
    var overscrollJob by remember { mutableStateOf<Job?>(null) }
    var itemSheet by remember { mutableStateOf<Pair<String, Int>?>(null) }
    itemSheet?.let { (videoId, index) ->
        QueueItemBottomSheet(onDismiss = { itemSheet = null }, videoId = videoId, indexHint = index)
    }

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
        if (chips.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                itemsIndexed(chips, key = { index, chip -> "$index-${chip.title}" }) { _, chip ->
                    val selected = if (tuning != null) chip.title == tuning else chip.selected
                    YtmChip(label = chip.title, selected = selected && !chip.isSave) {
                        if (chip.isSave) {
                            savingQueue = true
                        } else if (!selected) {
                            tuning = chip.title
                            scope.launch {
                                val tuned =
                                    musicServiceHandler.replaceUpcomingRadio(
                                        YouTubeWatchEndpoint(videoId = seed, playlistId = chip.playlistId, params = chip.params),
                                    )
                                if (!tuned) tuning = null
                            }
                        }
                    }
                }
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
                    Column {
                        if (showAutoplayHeader && index == autoplayStart) AutoplayHeader(autoplayOn, onAutoplayChange)
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
            if (showAutoplayHeader && radioId == null && (autoplayStart == null || autoplayStart >= state.artworkQueue.size)) {
                item(key = "autoplay") { AutoplayHeader(autoplayOn, onAutoplayChange) }
            }
        }
    }
}

@Composable
private fun AutoplayHeader(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.ytm_autoplay),
                style = typo().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(stringResource(Res.string.ytm_autoplay_summary), style = typo().bodySmall, color = TextSecondary)
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                ),
        )
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
    if (state.controllerState.isPlaying) KeepScreenOn()
    val synced = data.lyrics.syncType != null && data.lyrics.syncType != "UNSYNCED"
    val timeline = state.timelineFlow.collectAsState()
    val starts = remember(lines) { lines.map { it.startTimeMs.toLongOrNull() ?: 0L } }
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
            }
        }
        item(key = "source") {
            Text(
                data.sourceText ?: stringResource(Res.string.ytm_lyrics_source, data.lyricsProvider.displayName()),
                style = typo().bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

private fun LyricsProvider.displayName(): String =
    when (this) {
        LyricsProvider.YOUTUBE_MUSIC, LyricsProvider.YOUTUBE -> "YouTube Music"
        LyricsProvider.LRCLIB -> "LRCLIB"
        LyricsProvider.SIMPMUSIC -> "SimpMusic Lyrics"
        LyricsProvider.BETTER_LYRICS -> "Better Lyrics"
        else -> name.lowercase().replaceFirstChar { it.uppercase() }
    }

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

private const val ARTWORK_MAX_PX = 1080

private const val LYRICS_FOLLOW_PAUSE_MS = 3_000L

private fun Modifier.toggleableIf(
    active: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(role = Role.Button, onClick = onClick).semantics { selected = active }
