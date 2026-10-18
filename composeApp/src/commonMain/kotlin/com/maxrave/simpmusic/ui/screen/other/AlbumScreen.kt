package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kmpalette.rememberPaletteState
import com.maxrave.simpmusic.extension.getColorFromPalette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.PlaylistBottomSheet
import com.maxrave.simpmusic.ui.component.YtmCollectionHeader
import com.maxrave.simpmusic.ui.component.YtmRoundAction
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.Download
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.Downloading
import com.maxrave.simpmusic.ui.icon.Explicit
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.AlbumViewModel
import com.maxrave.simpmusic.viewModel.LocalPlaylistState
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import com.maxrave.simpmusic.viewModel.UIEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.ytm_download
import simpmusic.composeapp.generated.resources.ytm_downloaded
import simpmusic.composeapp.generated.resources.ytm_downloading
import com.maxrave.simpmusic.ui.component.YtmCollectionTopBar
import simpmusic.composeapp.generated.resources.ytm_save_to_library
import simpmusic.composeapp.generated.resources.ytm_saved_to_library
import simpmusic.composeapp.generated.resources.ytm_explicit
import simpmusic.composeapp.generated.resources.more

/**
 * YouTube Music's album page: the cover centred on a wash of its colour, title, artist and
 * "Album • year", the action row (download, save, the white play button, shuffle, more), the
 * numbered tracks, the song count and length, then other versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    browseId: String,
    navController: NavController,
    viewModel: AlbumViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(browseId) { viewModel.updateBrowseId(browseId) }

    // The page takes its colour from the cover, as YouTube Music's does.
    val paletteState = rememberPaletteState()
    var artwork by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(artwork) { artwork?.let { paletteState.generate(it) } }
    LaunchedEffect(Unit) {
        snapshotFlow { paletteState.palette }.collect { palette ->
            palette?.let { viewModel.setBrush(listOf(it.getColorFromPalette(), Color.Black)) }
        }
    }

    menuTrack?.let { NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = it.toSongEntity()) }
    if (albumMenu) {
        PlaylistBottomSheet(
            onDismiss = { albumMenu = false },
            playlistId = browseId,
            playlistName = uiState.title,
            isYourYouTubePlaylist = false,
            onSaveToLocal = {},
            onAddToQueue = { sharedViewModel.addListToQueue(ArrayList(uiState.listTrack)) },
        )
    }

    val tint = uiState.colors.firstOrNull() ?: Color.Black
    val wash = remember(tint) { Brush.verticalGradient(listOf(lerp(tint, Ytm.Background, 0.45f), Ytm.Background)) }
    val isPlayingThis by remember(uiState.listTrack, playingVideoId) {
        derivedStateOf { uiState.listTrack.any { it.videoId == playingVideoId } }
    }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        when (uiState.loadState) {
            LocalPlaylistState.PlaylistLoadState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            LocalPlaylistState.PlaylistLoadState.Error ->
                YtmErrorPage(onBack = { navController.navigateUp() }) { viewModel.updateBrowseId(browseId) }
            else ->
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item(key = "header") {
                        Box(Modifier.fillMaxWidth().background(wash)) {
                            YtmCollectionHeader(
                                thumbnail = uiState.thumbnail,
                                title = uiState.title,
                                subtitle = uiState.artist.name,
                                meta = listOf(uiState.type.ifBlank { stringResource(Res.string.album) }, uiState.year).filter { it.isNotBlank() }.joinToString(" • "),
                                isPlaying = isPlayingThis && controllerState.isPlaying,
                                onBack = { navController.navigateUp() },
                                onMore = { albumMenu = true },
                                // Already playing this album: the button pauses and resumes it, as in YouTube Music.
                                onPlay = {
                                    if (isPlayingThis) {
                                        sharedViewModel.onUIEvent(UIEvent.PlayPause)
                                    } else {
                                        uiState.listTrack.firstOrNull()?.let { viewModel.playTrack(it) }
                                    }
                                },
                                onShuffle = { viewModel.shuffle() },
                                onSubtitleClick = uiState.artist.id?.let { id -> { navController.navigate(ArtistDestination(channelId = id)) } },
                                onArtworkLoaded = { artwork = it },
                                leadingAction = {
                                    YtmRoundAction(
                                        when (uiState.downloadState) {
                                            DownloadState.STATE_DOWNLOADED -> SimpIcons.DownloadForOffline
                                            DownloadState.STATE_DOWNLOADING -> SimpIcons.Downloading
                                            else -> SimpIcons.Download
                                        },
                                        onClick = {
                                            if (uiState.downloadState != DownloadState.STATE_DOWNLOADED &&
                                                uiState.downloadState != DownloadState.STATE_DOWNLOADING
                                            ) {
                                                viewModel.downloadFullAlbum()
                                            }
                                        },
                                        label = stringResource(
                                            when (uiState.downloadState) {
                                                DownloadState.STATE_DOWNLOADED -> Res.string.ytm_downloaded
                                                DownloadState.STATE_DOWNLOADING -> Res.string.ytm_downloading
                                                else -> Res.string.ytm_download
                                            },
                                        ),
                                    )
                                },
                                secondAction = {
                                    YtmRoundAction(
                                        if (uiState.liked) SimpIcons.Check else SimpIcons.LibraryMusic,
                                        onClick = { viewModel.setAlbumLike() },
                                        label = stringResource(if (uiState.liked) Res.string.ytm_saved_to_library else Res.string.ytm_save_to_library),
                                    )
                                },
                            )
                        }
                    }
                    if (!uiState.description.isNullOrBlank()) {
                        item(key = "description") {
                            Text(
                                uiState.description.orEmpty(),
                                style = typo().bodyMedium,
                                color = Ytm.TextSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(uiState.listTrack, key = { index, t -> "$index${t.videoId}" }) { index, track ->
                        NumberedTrackRow(
                            number = index + 1,
                            track = track,
                            isCurrent = track.videoId == playingVideoId,
                            showArtist = track.artists?.firstOrNull()?.name != uiState.artist.name,
                            onClick = { viewModel.playTrack(track) },
                            onMore = { menuTrack = track },
                        )
                    }
                    item(key = "footer") {
                        Text(
                            listOf(pluralStringResource(Res.plurals.n_song, uiState.trackCount, uiState.trackCount), uiState.length).filter { it.isNotBlank() }.joinToString(" • "),
                            style = typo().bodyMedium,
                            color = Ytm.TextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                    if (uiState.otherVersion.isNotEmpty()) {
                        item(key = "other") {
                            Text(
                                "Other versions",
                                style = typo().headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
                            )
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.otherVersion) { album ->
                                    Column(Modifier.width(152.dp).clickable { navController.navigate(AlbumDestination(browseId = album.browseId)) }) {
                                        Thumb(album.thumbnails.lastOrNull()?.url, 152.dp, RoundedCornerShape(4.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(album.title, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(stringResource(Res.string.album), album.year).filter { it.isNotBlank() }.joinToString(" • "), style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
        }
        if (uiState.loadState == LocalPlaylistState.PlaylistLoadState.Success) {
            YtmCollectionTopBar(listState, uiState.title, onBack = { navController.navigateUp() }, onMore = { albumMenu = true })
        }
    }
}

/** An album track row: its number in place of a cover, as YouTube Music lists album tracks. */
@Composable
fun NumberedTrackRow(
    number: Int,
    track: Track,
    isCurrent: Boolean,
    showArtist: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString(),
            style = typo().bodyLarge,
            color = if (isCurrent) YtmRed else Ytm.TextSecondary,
            modifier = Modifier.width(40.dp).padding(start = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = typo().bodyLarge.copy(fontSize = 15.sp),
                color = if (isCurrent) YtmRed else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub =
                listOfNotNull(
                    if (showArtist) track.artists?.joinToString(", ") { it.name } else null,
                    track.duration?.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            if (sub.isNotBlank() || track.isExplicit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.isExplicit) {
                        Icon(SimpIcons.Explicit, contentDescription = stringResource(Res.string.ytm_explicit), tint = Ytm.TextSecondary, modifier = Modifier.padding(end = 4.dp).height(14.dp))
                    }
                    Text(sub, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        androidx.compose.material3.IconButton(onClick = onMore) {
            Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Ytm.TextSecondary)
        }
    }
}
