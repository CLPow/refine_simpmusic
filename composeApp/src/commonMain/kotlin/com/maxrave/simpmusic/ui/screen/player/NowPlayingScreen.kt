@file:OptIn(ExperimentalMaterial3Api::class)

package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.animation.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kmpalette.rememberPaletteState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.extension.getColorFromPalette
import com.maxrave.simpmusic.ui.component.AddToPlaylistModalBottomSheet
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.openContent
import com.maxrave.simpmusic.ui.icon.KeyboardArrowDown
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.screen.player.content.NowPlayingContentActions
import com.maxrave.simpmusic.ui.screen.player.content.NowPlayingContentState
import com.maxrave.simpmusic.ui.screen.player.content.NowPlayingContentYtm
import com.maxrave.simpmusic.viewModel.NowPlayingBottomSheetUIEvent
import com.maxrave.simpmusic.viewModel.NowPlayingBottomSheetViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "NowPlayingScreen"

@OptIn(ExperimentalFoundationApi::class)
@ExperimentalMaterial3Api
@Composable
fun NowPlayingScreen(
    sharedViewModel: SharedViewModel = koinInject(),
    navController: NavController,
    onDismiss: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )

    val hideSheet: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        modifier =
            Modifier
                .fillMaxHeight(),
        onDismissRequest = {
            onDismiss()
        },
        containerColor = Color.Black,
        dragHandle = {},
        // Full window on every screen size: YouTube Music's player covers the whole window, and
        // the default 640dp cap left desktop with a narrow strip instead of the wide layout.
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        scrimColor = Color.Black.copy(alpha = .5f),
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RectangleShape,
    ) {
        NowPlayingScreenContent(
            sharedViewModel = sharedViewModel,
            navController = navController,
            isExpanded = sheetState.currentValue == SheetValue.Expanded,
            dismissIcon = SimpIcons.KeyboardArrowDown,
            onDismiss = {
                hideSheet()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreenContent(
    sharedViewModel: SharedViewModel = koinInject(),
    mediaPlayerHandler: MediaPlayerHandler = koinInject(),
    navController: NavController,
    isExpanded: Boolean,
    dismissIcon: ImageVector,
    onDismiss: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    // ViewModel state. The playback position is not collected here (see NowPlayingContentState).
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    val screenDataState by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val isUserLoggedIn by sharedViewModel
        .isUserLoggedInFlow()
        .collectAsStateWithLifecycle(initialValue = false)

    // Artwork Pager state — Spotify-style horizontal swipe between queue tracks.
    // The pager wraps the Canvas + Thumbnail layers. Controller layout below stays fixed.
    val nowPlayingState by sharedViewModel.nowPlayingState.collectAsStateWithLifecycle()
    val queueDataState by sharedViewModel.getQueueDataState().collectAsStateWithLifecycle()
    val artworkQueue by remember {
        derivedStateOf { queueDataState?.data?.listTracks ?: emptyList() }
    }
    // ⚠️ Use track.videoId (already prefix-stripped at MediaServiceHandlerImpl.kt:386).
    // Do NOT use mediaItem.mediaId — it carries the "Video" prefix for video items.
    val nowPlayingVideoId: String? = nowPlayingState?.track?.videoId
    // currentOrderIndex() is a plain getter over the player, NOT Compose state, so it is read
    // inside this remember block — whose keys (the queue, and the track now playing) are exactly
    // the two things that can move the player's position. nowPlayingState is published FROM the
    // player's own transition callback, so by the time nowPlayingVideoId changes here the player
    // index has already moved; reading it any earlier would sample the outgoing track.
    val currentOrderIndex by remember(artworkQueue, nowPlayingVideoId) {
        derivedStateOf {
            deriveOrderIndex(
                queue = artworkQueue,
                nowPlayingVideoId = nowPlayingVideoId,
                playerOrderIndex = mediaPlayerHandler.currentOrderIndex(),
            )
        }
    }
    // Single PagerState — the unified ArtworkPager renders BOTH the fullscreen canvas
    // background and the centered square thumbnail in each page, so we don't need two
    // pagers + state mirroring.
    val artworkPagerState =
        rememberPagerState(
            initialPage = currentOrderIndex.coerceAtLeast(0),
            pageCount = { artworkQueue.size.coerceAtLeast(1) },
        )
    var isAnimatingFromPlayer by remember { mutableStateOf(false) }
    var isUserDraggingActive by remember { mutableStateOf(false) }
    // Whether a real finger-drag is waiting to be turned into a seek. See the latch below.
    var pendingUserSwipe by remember { mutableStateOf(false) }

    // Drag detection — `isScrollInProgress` is `true` for both user drags (forwarded
    // by the outer Modifier.scrollable on the Column) and programmatic
    // `animateScrollToPage`. We disambiguate via `isAnimatingFromPlayer`, which we
    // set explicitly around the player → pager animation (try/finally).
    LaunchedEffect(artworkPagerState) {
        snapshotFlow {
            artworkPagerState.isScrollInProgress to isAnimatingFromPlayer
        }.collect { (scrolling, animating) ->
            isUserDraggingActive = scrolling && !animating
            // Latched, and cleared only once a seek has been dispatched for it. settledPage can
            // move for reasons that are NOT a swipe — the pager being composed for the first time,
            // or re-composed after the Apple Music style spent a while on its Queue/Lyrics tab,
            // where the pager does not exist at all and its settledPage stays frozen on the track
            // that was playing when the user left MAIN. Without this latch, coming back from that
            // tab replays a stale page as though it had just been swiped to.
            if (isUserDraggingActive) pendingUserSwipe = true
        }
    }

    // ① Player → Pager: animate to new track when player advances.
    //
    // Keyed on the track ALONE. It used to be keyed on the queue SIZE as well, and a changing key
    // cancels the running effect — including the animateScrollToPage in flight. A cancelled scroll
    // simply stops where it is; nothing snaps it to a page afterwards, because snapping belongs to
    // the gesture path, not to a programmatic scroll. Turning on the radio appends to the queue
    // repeatedly, so the size changed again and again and cut the animation short each time,
    // leaving the pager parked between two pages. The size is still read below — just as a value,
    // not as a trigger; ③ handles the case where the queue shrinks under the current page.
    LaunchedEffect(currentOrderIndex) {
        val target = currentOrderIndex
        if (!isUserDraggingActive &&
            artworkQueue.isNotEmpty() &&
            target in 0 until artworkQueue.size &&
            target != artworkPagerState.currentPage
        ) {
            isAnimatingFromPlayer = true
            try {
                artworkPagerState.animateScrollToPage(target)
            } finally {
                isAnimatingFromPlayer = false
            }
        }
    }

    // ② Pager → Player: seek when user settles on a different page.
    // Adjacent (±1) → Next/Previous (preserves crossfade flow on Android).
    // Far skip → playMediaItemInMediaSource (handles unshuffling internally).
    //
    // Keyed on artworkPagerState ALONE — deliberately NOT on currentOrderIndex/queue size, which
    // are read through rememberUpdatedState instead. This effect exists to catch a user SWIPE, and
    // the only thing that moves settledPage is the pager. Re-keying it on the track built a NEW
    // snapshotFlow on every track change, and a new flow's FIRST emission is whatever settledPage
    // happens to hold — distinctUntilChanged has no previous value to suppress it against — so a
    // STALE page was dispatched as though the user had just swiped to it.
    //
    // That is what broke the Apple Music queue: its pager lives inside MAIN, so while QUEUE is on
    // screen the pager is not composed at all and settledPage still points at the previous track.
    // Tapping a row seeked correctly, the track changed, this effect was rebuilt, and it
    // immediately read the stale page — computeSeekAction(previous, current) == Previous — which
    // sent the player straight back to the song that had just been playing.
    // AppleMusicQueueView already uses rememberUpdatedState for exactly this hazard (its offset).
    val latestOrderIndex by rememberUpdatedState(currentOrderIndex)
    val latestQueueSize by rememberUpdatedState(artworkQueue.size)
    LaunchedEffect(artworkPagerState) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                if (isAnimatingFromPlayer) return@collect
                // The seek is a response to a SWIPE, so there must have been one. This is what
                // stops the Apple Music queue from being overruled: tapping a row seeks correctly,
                // the track changes, and then this effect would otherwise notice the pager sitting
                // on the old page and "correct" it right back.
                if (!pendingUserSwipe) return@collect
                pendingUserSwipe = false
                val queueSize = latestQueueSize
                val orderIndex = latestOrderIndex
                if (queueSize == 0) return@collect
                if (settled !in 0 until queueSize) return@collect
                if (settled == orderIndex) return@collect

                runCatching {
                    when (val action = computeSeekAction(settled, orderIndex)) {
                        ArtworkSeekAction.Next -> {
                            sharedViewModel.onUIEvent(UIEvent.Next)
                        }
                        // Use SkipToPrevious so a swipe always goes to the previous track —
                        // UIEvent.Previous would seek to 0 of the current track once the
                        // playhead has passed the 3-second mark.
                        ArtworkSeekAction.Previous -> {
                            sharedViewModel.onUIEvent(UIEvent.SkipToPrevious)
                        }
                        is ArtworkSeekAction.Skip -> {
                            mediaPlayerHandler.playMediaItemInMediaSource(action.index)
                        }
                        ArtworkSeekAction.NoOp -> {
                            Unit
                        }
                    }
                }.onFailure { error ->
                    Logger.w(TAG, "ArtworkPager seek failed: ${error.message}")
                }
            }
    }

    // ③ Queue mutation guard — when queue shrinks below currentPage, scroll to last index
    // to avoid IndexOutOfBoundsException during recomposition.
    LaunchedEffect(artworkQueue.size) {
        if (artworkQueue.isNotEmpty() && artworkPagerState.currentPage >= artworkQueue.size) {
            runCatching { artworkPagerState.scrollToPage(artworkQueue.lastIndex) }
        }
    }

    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showAddToPlaylistDirectly by rememberSaveable { mutableStateOf(false) }

    // The page takes its colour from the cover. Generation lives in its own never-restarting
    // effect: keyed on the screen data it was cancelled whenever another field arrived, and
    // kmpalette parks on Loading when generate() is cancelled.
    val paletteState = rememberPaletteState()
    val startColor = remember { Animatable(Color.Black) }
    LaunchedEffect(Unit) {
        snapshotFlow { screenDataState.bitmap }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { paletteState.generate(it) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { paletteState.palette }
            .distinctUntilChanged()
            .collectLatest { startColor.animateTo(it.getColorFromPalette()) }
    }

    if (showSheet) {
        NowPlayingBottomSheet(
            onDismiss = { showSheet = false },
            navController = navController,
            onNavigateToOtherScreen = { onDismiss() },
            song = null, // Auto set now playing
            setSleepTimerEnable = true,
            changeMainLyricsProviderEnable = true,
        )
    }

    if (showAddToPlaylistDirectly) {
        val viewModel: NowPlayingBottomSheetViewModel = koinViewModel(key = "player-save")
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            viewModel.resetPlaylists()
            viewModel.setSongEntity(null) // Uses current playing song
        }

        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = true,
            listLocalPlaylist = uiState.listLocalPlaylist,
            listYouTubePlaylist = uiState.listYouTubePlaylist,
            onDismiss = { showAddToPlaylistDirectly = false },
            onClick = { playlist ->
                viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.AddToPlaylist(playlist.id))
                showAddToPlaylistDirectly = false
            },
            onYTPlaylistClick = { playlist ->
                viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.AddToYouTubePlaylist(playlist.browseId))
                showAddToPlaylistDirectly = false
            },
            videoId = uiState.songUIState.videoId,
        )
    }

    val state =
        NowPlayingContentState(
            screenData = screenDataState,
            controllerState = controllerState,
            timelineFlow = sharedViewModel.timeline,
            isUserLoggedIn = isUserLoggedIn,
            artworkQueue = artworkQueue,
            currentOrderIndex = currentOrderIndex,
            artworkPagerState = artworkPagerState,
            startColor = startColor,
            dismissIcon = dismissIcon,
        )
    // Built once: the callbacks read what they need when they run, so a new set per
    // recomposition only made every child see "new" parameters.
    val latestScreenData by rememberUpdatedState(screenDataState)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val actions =
        remember(sharedViewModel, mediaPlayerHandler, navController) {
            NowPlayingContentActions(
                onUIEvent = { sharedViewModel.onUIEvent(it) },
                onSeekToQueueIndex = { index -> mediaPlayerHandler.playMediaItemInMediaSource(index) },
                onArtworkBitmap = { sharedViewModel.setBitmap(it) },
                onSeek = { percent -> sharedViewModel.onUIEvent(UIEvent.UpdateProgress(percent)) },
                onNavigateToArtist = {
                    val song = sharedViewModel.nowPlayingState.value?.songEntity
                    (
                        song?.artistId?.firstOrNull()?.takeIf { it.isNotEmpty() }
                            ?: latestScreenData.songInfoData?.authorId
                    )?.let { channelId ->
                        latestOnDismiss()
                        navController.navigate(ArtistDestination(channelId = channelId))
                    }
                },
                onShowMoreSheet = { showSheet = true },
                onShowAddToPlaylist = { showAddToPlaylistDirectly = true },
                onDismiss = { latestOnDismiss() },
                onMoveQueueItem = { from, to ->
                    coroutineScope.launch { mediaPlayerHandler.swap(from, to) }
                },
                onOpenContent = { content ->
                    if (content.videoId.isNullOrEmpty()) latestOnDismiss()
                    openContent(content, navController, sharedViewModel)
                },
            )
        }
    NowPlayingContentYtm(
        state = state,
        actions = actions,
    )
}
