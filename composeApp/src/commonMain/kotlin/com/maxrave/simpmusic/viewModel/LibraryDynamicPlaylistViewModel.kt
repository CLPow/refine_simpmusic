package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.repository.SongRepository
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The songs behind the Library's built-in lists: Liked music (while signed out) and Downloads. */
class LibraryDynamicPlaylistViewModel(
    songRepository: SongRepository,
) : BaseViewModel() {
    val liked: StateFlow<List<SongEntity>?> =
        songRepository.getLikedSongs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloaded: StateFlow<List<SongEntity>?> =
        songRepository
            .getDownloadedSongs()
            .map { it.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
