package com.maxrave.simpmusic.viewModel

import org.jetbrains.compose.resources.getString
import com.maxrave.domain.data.model.browse.album.Track
import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.collectResource
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_song_play_next
import simpmusic.composeapp.generated.resources.ytm_songs_play_next
import simpmusic.composeapp.generated.resources.ytm_song_added_to_queue
import simpmusic.composeapp.generated.resources.ytm_songs_added_to_queue
import simpmusic.composeapp.generated.resources.added_to_playlist
import simpmusic.composeapp.generated.resources.delete_song_from_playlist
import simpmusic.composeapp.generated.resources.downloading
import simpmusic.composeapp.generated.resources.error
import simpmusic.composeapp.generated.resources.error_occurred
import simpmusic.composeapp.generated.resources.removed_from_YouTube_playlist

class SongSelectionViewModel(
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
) : BaseViewModel() {
    private val downloadUtils: DownloadHandler by inject()

    val listLocalPlaylist: StateFlow<List<LocalPlaylistEntity>> =
        localPlaylistRepository
            .getAllLocalPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playNext(videoIds: List<String>) {
        viewModelScope.launch {
            val songs = songsOf(videoIds)
            if (songs.isEmpty()) {
                makeToast(getString(Res.string.error_occurred))
                return@launch
            }
            songs.asReversed().forEach { mediaPlayerHandler.playNext(it.toTrack()) }
            makeToast(
                if (songs.size == 1) getString(Res.string.ytm_song_play_next) else getString(Res.string.ytm_songs_play_next, songs.size),
            )
        }
    }

    fun addToQueue(videoIds: List<String>) {
        viewModelScope.launch {
            val songs = songsOf(videoIds)
            if (songs.isEmpty()) {
                makeToast(getString(Res.string.error_occurred))
                return@launch
            }
            mediaPlayerHandler.addToQueue(songs.map { it.toTrack() })
            makeToast(
                if (songs.size == 1) getString(Res.string.ytm_song_added_to_queue) else getString(Res.string.ytm_songs_added_to_queue, songs.size),
            )
        }
    }

    fun download(videoIds: List<String>) {
        viewModelScope.launch {
            val pending =
                songsOf(videoIds).filter {
                    it.downloadState == DownloadState.STATE_NOT_DOWNLOADED
                }
            if (pending.isEmpty()) return@launch
            pending.forEach { song ->
                songRepository.updateDownloadState(
                    videoId = song.videoId,
                    downloadState = DownloadState.STATE_PREPARING,
                )
                downloadUtils.downloadTrack(
                    videoId = song.videoId,
                    title = song.title,
                    thumbnail = song.thumbnails ?: "",
                )
            }
            makeToast(getString(Res.string.downloading))
        }
    }

    fun addToFavorite(videoIds: List<String>) {
        viewModelScope.launch {
            songsOf(videoIds)
                .filterNot { it.liked }
                .forEach { songRepository.updateLikeStatus(it.videoId, 1) }
        }
    }

    fun addToPlaylist(
        playlistId: Long,
        videoIds: List<String>,
    ) {
        viewModelScope.launch {
            val playlist = localPlaylistRepository.getAllLocalPlaylists().firstOrNull()?.find { it.id == playlistId }
            val already = playlist?.tracks.orEmpty()
            val toAdd = songsOf(videoIds).filterNot { already.contains(it.videoId) }
            if (toAdd.isEmpty()) return@launch
            var added = 0
            toAdd.forEach { song ->
                localPlaylistRepository
                    .addTrackToLocalPlaylist(
                        id = playlistId,
                        song = song,
                        successMessage = getString(Res.string.added_to_playlist),
                        updatedYtMessage = getString(Res.string.added_to_playlist),
                        errorMessage = getString(Res.string.error),
                    ).collectResource(
                        onSuccess = { added++ },
                        onError = { },
                    )
            }
            makeToast(
                if (added > 0) getString(Res.string.added_to_playlist) else getString(Res.string.error),
            )
        }
    }

    // Queue tracks that were never played are not in the song table yet, and [addToPlaylist] reads from it, so they are stored first.
    fun saveTracksToPlaylist(
        playlistId: Long,
        tracks: List<Track>,
    ) {
        viewModelScope.launch {
            tracks.forEach { songRepository.insertSong(it.toSongEntity()).firstOrNull() }
            addToPlaylist(playlistId, tracks.map { it.videoId }.distinct())
        }
    }

    fun playNextTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            tracks.asReversed().forEach { mediaPlayerHandler.playNext(it) }
            makeToast(
                if (tracks.size == 1) getString(Res.string.ytm_song_play_next) else getString(Res.string.ytm_songs_play_next, tracks.size),
            )
        }
    }

    fun addTracksToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            mediaPlayerHandler.addToQueue(tracks)
            makeToast(
                if (tracks.size == 1) getString(Res.string.ytm_song_added_to_queue) else getString(Res.string.ytm_songs_added_to_queue, tracks.size),
            )
        }
    }

    fun downloadTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            tracks.forEach { songRepository.insertSong(it.toSongEntity()).firstOrNull() }
            download(tracks.map { it.videoId })
        }
    }

    fun removeFromLocalPlaylist(
        playlistId: Long,
        videoIds: List<String>,
    ) {
        viewModelScope.launch {
            val songs = songsOf(videoIds)
            if (songs.isEmpty()) {
                makeToast(getString(Res.string.error_occurred))
                return@launch
            }
            songs.forEach { song ->
                localPlaylistRepository
                    .removeTrackFromLocalPlaylist(
                        id = playlistId,
                        song = song,
                        successMessage = getString(Res.string.delete_song_from_playlist),
                        updatedYtMessage = getString(Res.string.removed_from_YouTube_playlist),
                        errorMessage = getString(Res.string.error_occurred),
                    ).collectResource(
                        onSuccess = { },
                        onError = { },
                    )
            }
            makeToast(getString(Res.string.delete_song_from_playlist))
        }
    }

    private suspend fun songsOf(videoIds: List<String>): List<SongEntity> =
        songRepository
            .getSongsByListVideoId(videoIds)
            .firstOrNull()
            .orEmpty()
            .sortedBy { videoIds.indexOf(it.videoId) }
}
