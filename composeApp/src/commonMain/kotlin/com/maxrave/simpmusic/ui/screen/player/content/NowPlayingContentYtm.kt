package com.maxrave.simpmusic.ui.screen.player.content

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.expect.ui.toImageBitmap
import com.maxrave.simpmusic.extension.formatDuration
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.Info
import com.maxrave.simpmusic.ui.icon.Lyrics
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.Repeat
import com.maxrave.simpmusic.ui.icon.RepeatOne
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.flow.lastOrNull
import org.koin.compose.koinInject
import kotlin.math.roundToLong

private val PageBlack = Color(0xFF030303)
private val TextSecondary = Color(0xFFAAAAAA)

/**
 * Now Playing, laid out like YouTube Music's player:
 *
 * top bar (collapse · what is playing from · more) → square artwork, swipeable through the queue
 * → title and artist → a row of action pills (like, save, lyrics, info) → a thin white slider
 * with times → shuffle · previous · the white play disc · next · repeat → the UP NEXT · LYRICS ·
 * RELATED tabs along the bottom.
 *
 * The page is a dark gradient that starts in the artwork's colour and settles into YouTube
 * Music's near-black, so the controls always sit on dark.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingContentYtm(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    val sharedViewModel: SharedViewModel = koinInject()
    var showRelated by rememberSaveable { mutableStateOf(false) }
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

    Box(Modifier.fillMaxSize().background(PageBlack).background(background)) {
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
            Spacer(Modifier.height(20.dp))
            TitleBlock(state, actions)
            Spacer(Modifier.height(16.dp))
            ActionPills(state, actions)
            Spacer(Modifier.height(20.dp))
            SeekBar(state, actions)
            Spacer(Modifier.height(8.dp))
            Transport(state, actions)
            Spacer(Modifier.height(16.dp))
            BottomTabs(
                hasLyrics = state.screenData.lyricsData != null,
                onUpNext = actions.onShowQueue,
                onLyrics = actions.onShowFullscreenLyrics,
                onRelated = { showRelated = true },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showRelated) {
        RelatedSheet(
            videoId = state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId,
            onDismiss = { showRelated = false },
            onPlay = { track ->
                showRelated = false
                sharedViewModel.setQueueData(
                    QueueData.Data(
                        listTracks = arrayListOf(track),
                        firstPlayedTrack = track,
                        playlistId = "RDAMVM${track.videoId}",
                        playlistName = "\"${track.title}\" Radio",
                        playlistType = PlaylistType.RADIO,
                        continuation = null,
                    ),
                )
                sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
            },
        )
    }
}

@Composable
private fun TopBar(
    state: NowPlayingContentState,
    actions: NowPlayingContentActions,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onDismiss) {
            Icon(state.dismissIcon, contentDescription = null, tint = Color.White)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.screenData.playlistName.isNotBlank()) {
                Text(
                    text = "PLAYING FROM",
                    style = typo().bodySmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                    color = TextSecondary,
                )
                Text(
                    text = state.screenData.playlistName,
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = actions.onShowMoreSheet) {
            Icon(SimpIcons.MoreVert, contentDescription = null, tint = Color.White)
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
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
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
        val liked = state.controllerState.isLiked
        Pill(
            icon = SimpIcons.ThumbUp,
            label = if (liked) "Liked" else "Like",
            active = liked,
            onClick = { actions.onUIEvent(UIEvent.ToggleLike) },
        )
        Pill(icon = SimpIcons.PlaylistAdd, label = "Save", onClick = actions.onShowAddToPlaylist)
        if (state.screenData.lyricsData != null) {
            Pill(icon = SimpIcons.Lyrics, label = "Lyrics", onClick = actions.onShowFullscreenLyrics)
        }
        Pill(icon = SimpIcons.Info, label = "Info", onClick = actions.onShowInfo)
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
        Text(label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = content)
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
                    activeTrackColor = if (state.timelineState.isCrossfading) state.sliderTrackColor else Color.White,
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
                            activeTrackColor = if (state.timelineState.isCrossfading) state.sliderTrackColor else Color.White,
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
            Icon(SimpIcons.Shuffle, contentDescription = null, tint = if (controller.isShuffle) Color.White else TextSecondary)
        }
        IconButton(onClick = { actions.onUIEvent(UIEvent.Previous) }, modifier = Modifier.size(56.dp)) {
            Icon(SimpIcons.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable { actions.onUIEvent(UIEvent.PlayPause) },
            contentAlignment = Alignment.Center,
        ) {
            if (state.timelineState.loading) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            } else {
                Icon(
                    if (controller.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(
            onClick = { actions.onUIEvent(UIEvent.Next) },
            enabled = controller.isNextAvailable,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                SimpIcons.SkipNext,
                contentDescription = null,
                tint = if (controller.isNextAvailable) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = { actions.onUIEvent(UIEvent.Repeat) }) {
            Icon(
                if (controller.repeatState is RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                contentDescription = null,
                tint = if (controller.repeatState is RepeatState.None) TextSecondary else Color.White,
            )
        }
    }
}

@Composable
private fun BottomTabs(
    hasLyrics: Boolean,
    onUpNext: () -> Unit,
    onLyrics: () -> Unit,
    onRelated: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TabLabel("UP NEXT", enabled = true, onClick = onUpNext)
        TabLabel("LYRICS", enabled = hasLyrics, onClick = onLyrics)
        TabLabel("RELATED", enabled = true, onClick = onRelated)
    }
}

@Composable
private fun TabLabel(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = typo().bodyMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** YouTube Music's Related tab: songs like the one playing, fetched on open; a tap starts its radio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedSheet(
    videoId: String?,
    onDismiss: () -> Unit,
    onPlay: (Track) -> Unit,
) {
    val songRepository: SongRepository = koinInject()
    val related by produceState<List<Track>?>(initialValue = null, videoId) {
        value =
            if (videoId == null) {
                emptyList()
            } else {
                (songRepository.getRelatedData(videoId).lastOrNull() as? Resource.Success)?.data?.first.orEmpty()
            }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = getPlatform() == Platform.Desktop),
        containerColor = Color(0xFF212121),
    ) {
        Text(
            "Related",
            style = typo().titleMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val list = related
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.videoId }) { track ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(track) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = track.thumbnails?.lastOrNull()?.url,
                            placeholder = rememberHolderPainter(),
                            error = rememberHolderPainter(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                        )
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
                                track.artists?.toListName()?.connectArtists().orEmpty(),
                                style = typo().bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
