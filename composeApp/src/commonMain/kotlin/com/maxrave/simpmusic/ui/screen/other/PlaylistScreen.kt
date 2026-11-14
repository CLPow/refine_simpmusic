package com.maxrave.simpmusic.ui.screen.other

import com.maxrave.simpmusic.ui.component.selection.SongSelectionOverlay
import com.maxrave.simpmusic.ui.component.selection.rememberSongSelectionState
import com.maxrave.simpmusic.viewModel.QueueItemKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kmpalette.rememberPaletteState
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.simpmusic.extension.getColorFromPalette
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.PlaylistBottomSheet
import com.maxrave.simpmusic.ui.component.YtmCollectionHeader
import com.maxrave.simpmusic.ui.component.YtmRoundAction
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.Download
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.Downloading
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.ListState
import com.maxrave.simpmusic.viewModel.PlaylistUIEvent
import com.maxrave.simpmusic.viewModel.PlaylistUIState
import com.maxrave.simpmusic.viewModel.PlaylistViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import com.maxrave.simpmusic.viewModel.UIEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.ytm_download
import simpmusic.composeapp.generated.resources.ytm_downloaded
import simpmusic.composeapp.generated.resources.ytm_downloading
import simpmusic.composeapp.generated.resources.ytm_save_to_library
import simpmusic.composeapp.generated.resources.ytm_saved_to_library
import com.maxrave.simpmusic.ui.component.YtmCollectionTopBar

/**
 * YouTube Music's playlist page: the cover centred on a wash of its colour, title, owner,
 * "Playlist • year • N songs", the action row (download, save, the white play button, shuffle,
 * more), then the songs with covers, paging in as the list scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    playlistId: String,
    isYourYouTubePlaylist: Boolean,
    navController: NavController,
) {
    // Shelves navigate with the browse id ("VL" + playlist id); everything below wants the bare id.
    val id = playlistId.removePrefix("VL")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val continuation by viewModel.continuation.collectAsStateWithLifecycle()
    val listColors by viewModel.listColors.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val tracksListState by viewModel.tracksListState.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val selection = rememberSongSelectionState()
    var playlistMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(id) { if (id != uiState.data?.id) viewModel.getData(id) }

    // The page takes its colour from the cover.
    val paletteState = rememberPaletteState()
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artwork) { artwork?.let { paletteState.generate(it) } }
    LaunchedEffect(Unit) {
        snapshotFlow { paletteState.palette }.collect { palette ->
            palette?.let { viewModel.setBrush(listOf(it.getColorFromPalette(), Color.Black)) }
        }
    }

    // Page in more songs as the end comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, tracksListState) {
        if (nearEnd && tracksListState == ListState.IDLE && !continuation.isNullOrEmpty()) {
            viewModel.getContinuationTrack(id, continuation)
        }
    }

    menuTrack?.let { NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = it.toSongEntity()) }
    val data = uiState.data
    if (playlistMenu && data != null) {
        PlaylistBottomSheet(
            onDismiss = { playlistMenu = false },
            playlistId = data.id,
            playlistName = data.title,
            isYourYouTubePlaylist = isYourYouTubePlaylist && !data.isRadio,
            onSaveToLocal = { viewModel.getFullTracks { viewModel.saveToLocal(it) } },
            onEditTitle = { viewModel.updatePlaylistTitle(it, data.id) },
            onAddToQueue =
                if (data.isRadio) {
                    null
                } else {
                    { viewModel.getFullTracks { sharedViewModel.addListToQueue(ArrayList(it), QueueItemKind.PLAYLIST) } }
                },
        )
    }

    val tint = listColors.firstOrNull() ?: Color.Black
    val wash = remember(tint) { Brush.verticalGradient(listOf(lerp(tint, Ytm.Background, 0.45f), Ytm.Background)) }
    val isPlayingThis by remember(tracks, playingVideoId) { derivedStateOf { tracks.any { it.videoId == playingVideoId } } }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        when (val state = uiState) {
            is PlaylistUIState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            is PlaylistUIState.Error ->
                YtmErrorPage(onBack = { navController.navigateUp() }) { viewModel.getData(id) }
            is PlaylistUIState.Success -> {
                val playlist =
                    state.data ?: run {
                        YtmErrorPage(onBack = { navController.navigateUp() }) { viewModel.getData(id) }
                        return@Box
                    }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item(key = "header") {
                        Box(Modifier.fillMaxWidth().background(wash)) {
                            YtmCollectionHeader(
                                thumbnail = playlist.thumbnail,
                                title = playlist.title,
                                subtitle = playlist.author.name,
                                meta =
                                    listOf(
                                        stringResource(if (playlist.isRadio) Res.string.radio else Res.string.playlist),
                                        playlist.year,
                                        if (playlist.trackCount > 0) pluralStringResource(Res.plurals.n_song, playlist.trackCount, playlist.trackCount) else "",
                                    ).filter { it.isNotBlank() }.joinToString(" • "),
                                isPlaying = isPlayingThis && controllerState.isPlaying,
                                onBack = { navController.navigateUp() },
                                onMore = { playlistMenu = true },
                                onPlay = { if (isPlayingThis) sharedViewModel.onUIEvent(UIEvent.PlayPause) else viewModel.onUIEvent(PlaylistUIEvent.PlayAll) },
                                onShuffle = { viewModel.onUIEvent(PlaylistUIEvent.Shuffle) },
                                onArtworkLoaded = { artwork = it },
                                leadingAction = {
                                    YtmRoundAction(
                                        when (downloadState) {
                                            DownloadState.STATE_DOWNLOADED -> SimpIcons.DownloadForOffline
                                            DownloadState.STATE_DOWNLOADING -> SimpIcons.Downloading
                                            else -> SimpIcons.Download
                                        },
                                        onClick = { viewModel.onUIEvent(PlaylistUIEvent.Download) },
                                        label = stringResource(
                                            when (downloadState) {
                                                DownloadState.STATE_DOWNLOADED -> Res.string.ytm_downloaded
                                                DownloadState.STATE_DOWNLOADING -> Res.string.ytm_downloading
                                                else -> Res.string.ytm_download
                                            },
                                        ),
                                    )
                                },
                                secondAction = {
                                    YtmRoundAction(
                                        if (liked) SimpIcons.Check else SimpIcons.LibraryMusic,
                                        onClick = { viewModel.onUIEvent(PlaylistUIEvent.Favorite) },
                                        label = stringResource(if (liked) Res.string.ytm_saved_to_library else Res.string.ytm_save_to_library),
                                    )
                                },
                            )
                        }
                    }
                    if (!playlist.description.isNullOrBlank()) {
                        item(key = "description") {
                            Text(
                                playlist.description.orEmpty(),
                                style = typo().bodyMedium,
                                color = Ytm.TextSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(tracks, key = { index, t -> "$index${t.videoId}" }) { _, track ->
                        YtmSongRow(
                            title = track.title,
                            subtitle = listOfNotNull(track.artists?.toListName()?.connectArtists(), track.album?.name).filter { it.isNotBlank() }.joinToString(" • "),
                            thumbnail = track.thumbnails?.lastOrNull()?.url,
                            isCurrent = track.videoId == playingVideoId,
                            onClick = {
                                if (selection.isActive) selection.toggle(track.videoId) else viewModel.onUIEvent(PlaylistUIEvent.ItemClick(track.videoId))
                            },
                            onMore = { menuTrack = track },
                            onLongClick = { selection.start(track.videoId) },
                            onSwipeQueue = { sharedViewModel.addSongToQueue(track) },
                            selected = if (selection.isActive) selection.isSelected(track.videoId) else null,
                        )
                    }
                    if (tracksListState == ListState.PAGINATING) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                YtmCollectionTopBar(listState, playlist.title, onBack = { navController.navigateUp() }, onMore = { playlistMenu = true })
                SongSelectionOverlay(selection, tracks)
            }
        }
    }
}
