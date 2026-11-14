package com.maxrave.simpmusic.ui.component.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.simpmusic.expect.ui.PlatformBackHandler
import com.maxrave.simpmusic.ui.component.AddToPlaylistModalBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.viewModel.SongSelectionViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * YouTube Music's multi-select for a song list: while [state] is active, the "N selected" bar over
 * the page (close, select all, actions), the actions sheet (play next, add to queue, save to a
 * playlist, download) and the playlist picker. Back leaves the selection before the page.
 *
 * [tracks] is the list as shown, in order: select-all takes it, and the actions use these tracks
 * directly, since songs from an album or playlist page are often not in the database yet.
 */
@Composable
fun SongSelectionOverlay(
    state: SongSelectionState,
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    selectionViewModel: SongSelectionViewModel = koinViewModel(),
) {
    var showActions by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = state.isActive) { state.exit() }
    if (!state.isActive) return

    val picked = { tracks.filter { state.isSelected(it.videoId) }.distinctBy { it.videoId } }
    SongSelectionTopAppBar(
        state = state,
        onSelectAll = { state.toggleSelectAll(tracks.map { it.videoId }) },
        onOpenActions = { showActions = true },
        modifier = modifier.fillMaxWidth().background(Ytm.Background),
    )
    if (showActions) {
        SelectedSongsBottomSheet(
            count = state.count,
            onDismiss = { showActions = false },
            onPlayNext = {
                selectionViewModel.playNextTracks(picked())
                state.exit()
            },
            onAddToQueue = {
                selectionViewModel.addTracksToQueue(picked())
                state.exit()
            },
            onAddToPlaylist = { showPlaylistPicker = true },
            onDownload = {
                selectionViewModel.downloadTracks(picked())
                state.exit()
            },
        )
    }
    if (showPlaylistPicker) {
        val localPlaylists by selectionViewModel.listLocalPlaylist.collectAsStateWithLifecycle()
        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = true,
            listLocalPlaylist = localPlaylists,
            listYouTubePlaylist = emptyList(),
            onDismiss = { showPlaylistPicker = false },
            onClick = { playlist ->
                selectionViewModel.saveTracksToPlaylist(playlist.id, picked())
                showPlaylistPicker = false
                state.exit()
            },
            onYTPlaylistClick = {},
        )
    }
}
