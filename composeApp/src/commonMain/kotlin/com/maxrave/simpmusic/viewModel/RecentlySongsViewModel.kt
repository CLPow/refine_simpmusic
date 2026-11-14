package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.pagination.RecentPagingSource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch

/**
 * History, as YouTube Music shows it: the account's YouTube history when signed in (grouped Today,
 * Yesterday, This week…), and the songs played on this device when signed out.
 */
class RecentlySongsViewModel(
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
) : BaseViewModel() {
    val recentlySongs =
        Pager(
            PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                initialLoadSize = 20,
            ),
        ) {
            RecentPagingSource(songRepository)
        }.flow.cachedIn(viewModelScope)

    /** Null while loading or signed out; the groups once YouTube answers. */
    private val _youTubeHistory = MutableStateFlow<List<Pair<String, List<Track>>>?>(null)
    val youTubeHistory: StateFlow<List<Pair<String, List<Track>>>?> = _youTubeHistory.asStateFlow()

    private val _signedIn = MutableStateFlow<Boolean?>(null)
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val loggedIn = dataStoreManager.loggedIn.first() == DataStoreManager.TRUE
            _signedIn.value = loggedIn
            if (!loggedIn) return@launch
            val result = songRepository.getYouTubeHistory().lastOrNull()
            _youTubeHistory.value = (result as? Resource.Success)?.data.orEmpty()
        }
    }
}
