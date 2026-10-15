package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.added_local_playlist

/** YouTube Music's Library chips. */
enum class LibraryChip(val label: String) {
    Playlists("Playlists"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Downloads("Downloads"),
}

/** YouTube Music's sort menu. */
enum class LibrarySort(val label: String) {
    RecentActivity("Recent activity"),
    RecentlyAdded("Recently added"),
    Alphabetical("A–Z"),
}

/**
 * YouTube Music's Library: playlists (the account's YouTube playlists and the ones made here),
 * liked songs, saved albums, subscribed artists and downloads. The local lists follow the database
 * live; the account's playlists are fetched on open and on pull-to-refresh.
 */
class LibraryViewModel(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
) : BaseViewModel() {
    private val _chip = MutableStateFlow(LibraryChip.Playlists)
    val chip: StateFlow<LibraryChip> = _chip.asStateFlow()

    private val _sort = MutableStateFlow(LibrarySort.RecentActivity)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    private val _grid = MutableStateFlow(false)
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    val loggedIn: StateFlow<Boolean> =
        dataStoreManager.loggedIn
            .map { it == DataStoreManager.TRUE }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _youTubePlaylists = MutableStateFlow<List<PlaylistsResult>?>(null)
    val youTubePlaylists: StateFlow<List<PlaylistsResult>?> = _youTubePlaylists.asStateFlow()

    val localPlaylists: StateFlow<List<LocalPlaylistEntity>> =
        localPlaylistRepository.getAllLocalPlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val likedSongs: StateFlow<List<SongEntity>> =
        songRepository.getLikedSongs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumEntity>> =
        albumRepository.getLikedAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistEntity>> =
        artistRepository.getFollowedArtists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<List<SongEntity>> =
        songRepository
            .getDownloadedSongs()
            .map { it.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refreshYouTubePlaylists()
    }

    fun setChip(chip: LibraryChip) {
        _chip.value = chip
    }

    fun setSort(sort: LibrarySort) {
        _sort.value = sort
    }

    fun toggleGrid() {
        _grid.value = !_grid.value
    }

    /** The account's playlists, from YouTube. Nothing to fetch while signed out. */
    fun refreshYouTubePlaylists() {
        viewModelScope.launch {
            _refreshing.value = true
            _youTubePlaylists.value =
                if (dataStoreManager.loggedIn.first() == DataStoreManager.TRUE) {
                    runCatching { playlistRepository.getLibraryPlaylist().lastOrNull() }.getOrNull().orEmpty()
                } else {
                    emptyList()
                }
            _refreshing.value = false
        }
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch {
            localPlaylistRepository
                .insertLocalPlaylist(LocalPlaylistEntity(title = title), getString(Res.string.added_local_playlist))
                .lastOrNull()
        }
    }
}
