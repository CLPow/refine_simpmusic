package com.maxrave.simpmusic.viewModel

import com.maxrave.domain.data.model.searchResult.SearchSummary
import androidx.lifecycle.viewModelScope
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toQueryList
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.all
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.featured_playlists
import simpmusic.composeapp.generated.resources.playlists
import simpmusic.composeapp.generated.resources.podcasts
import simpmusic.composeapp.generated.resources.songs
import simpmusic.composeapp.generated.resources.videos

// State cho tìm kiếm
data class SearchScreenState(
    val searchType: SearchType = SearchType.ALL,
    val searchAllResult: List<SearchResultType> = emptyList(),
    val searchSongsResult: List<SongsResult> = emptyList(),
    val searchVideosResult: List<VideosResult> = emptyList(),
    val searchAlbumsResult: List<AlbumsResult> = emptyList(),
    val searchArtistsResult: List<ArtistsResult> = emptyList(),
    val searchPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchFeaturedPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchPodcastsResult: List<PlaylistsResult> = emptyList(),
    val suggestQueries: List<String> = emptyList(),
    val suggestYTItems: List<SearchResultType> = emptyList(),
    /** YouTube Music's unfiltered results page for [query]: top result, chips, ranked rows. */
    val summary: SearchSummary? = null,
    val query: String = "",
)

// Loại tìm kiếm
enum class SearchType {
    ALL,
    SONGS,
    VIDEOS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
    FEATURED_PLAYLISTS,
    PODCASTS,
}

fun SearchType.toStringRes(): StringResource =
    when (this) {
        SearchType.ALL -> Res.string.all
        SearchType.SONGS -> Res.string.songs
        SearchType.VIDEOS -> Res.string.videos
        SearchType.ALBUMS -> Res.string.albums
        SearchType.ARTISTS -> Res.string.artists
        SearchType.PLAYLISTS -> Res.string.playlists
        SearchType.FEATURED_PLAYLISTS -> Res.string.featured_playlists
        SearchType.PODCASTS -> Res.string.podcasts
    }

// UI state cho tìm kiếm
sealed class SearchScreenUIState {
    object Empty : SearchScreenUIState()

    object Loading : SearchScreenUIState()

    object Success : SearchScreenUIState()

    object Error : SearchScreenUIState()
}

class SearchViewModel(
    private val dataStoreManager: DataStoreManager,
    private val searchRepository: SearchRepository,
    private val homeRepository: HomeRepository,
) : BaseViewModel() {
    private val _searchScreenUIState = MutableStateFlow<SearchScreenUIState>(SearchScreenUIState.Empty)
    val searchScreenUIState: StateFlow<SearchScreenUIState> get() = _searchScreenUIState.asStateFlow()

    private val _searchScreenState = MutableStateFlow(SearchScreenState())
    val searchScreenState: StateFlow<SearchScreenState> get() = _searchScreenState.asStateFlow()

    private val _searchHistory: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val searchHistory: StateFlow<List<String>> get() = _searchHistory.asStateFlow()

    var regionCode: String? = null
    var language: String? = null

    /** The filtered search in flight; a newer chip or query replaces it. */
    private var filterJob: Job? = null

    /** The suggestion request in flight; each keystroke replaces it. */
    private var suggestJob: Job? = null

    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            regionCode = dataStoreManager.location.first()
            language = dataStoreManager.getString(SELECTED_LANGUAGE).first()
        }
        getSearchHistory()
    }

    private fun getSearchHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            searchRepository.getSearchHistory().collect { values ->
                if (values.isNotEmpty()) {
                    values.toQueryList().reversed().let { list ->
                        _searchHistory.value = list
                        log("Search history updated: $list")
                    }
                } else {
                    _searchHistory.value = emptyList()
                    log("Search history is empty")
                }
            }
        }
    }

    fun insertSearchHistory(query: String) {
        viewModelScope.launch {
            searchRepository.insertSearchHistory(SearchHistory(query = query)).collectLatest {
                Logger.d(tag, "Inserted search history: $query, $it")
            }
            getSearchHistory()
        }
    }

    fun deleteSearchHistory() {
        viewModelScope.launch {
            searchRepository.deleteSearchHistory()
            getSearchHistory()
        }
    }

    /** YouTube Music removes one recent search at a time, from a long press on it. */
    fun deleteSearchHistoryItem(query: String) {
        _searchHistory.update { list -> list.filter { it != query } }
        viewModelScope.launch {
            searchRepository.deleteSearchHistoryItem(query)
            getSearchHistory()
        }
    }

    fun searchSongs(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataSong(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { songsList ->
                            _searchScreenState.update { state ->
                                state.copy(searchSongsResult = songsList)
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    /**
     * YouTube Music's search: one unfiltered request, answered with the top result, the chips in
     * the order YouTube ranks them for this query, and the ranked rows. The filtered lists are
     * fetched only when their chip is tapped. This used to fire five filtered searches of three
     * pages each before showing anything, and invent the top result from the first artist.
     */
    fun searchAll(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        _searchScreenState.update {
            SearchScreenState(
                searchType = SearchType.ALL,
                query = query,
                suggestQueries = it.suggestQueries,
                suggestYTItems = it.suggestYTItems,
            )
        }
        viewModelScope.launch {
            searchRepository.getSearchSummary(query).collect { result ->
                if (_searchScreenState.value.query != query) return@collect
                when (result) {
                    is Resource.Success -> {
                        _searchScreenState.update { it.copy(summary = result.data) }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> _searchScreenUIState.value = SearchScreenUIState.Error
                }
            }
        }
    }

    /** A chip: the filtered list is fetched the first time it is shown for this query. */
    fun selectFilter(type: SearchType) {
        val state = _searchScreenState.value
        setSearchType(type)
        val query = state.query.takeIf { it.isNotBlank() } ?: return
        val loaded =
            when (type) {
                SearchType.ALL -> true
                SearchType.SONGS -> state.searchSongsResult.isNotEmpty()
                SearchType.VIDEOS -> state.searchVideosResult.isNotEmpty()
                SearchType.ALBUMS -> state.searchAlbumsResult.isNotEmpty()
                SearchType.ARTISTS -> state.searchArtistsResult.isNotEmpty()
                SearchType.PLAYLISTS -> state.searchPlaylistsResult.isNotEmpty()
                SearchType.FEATURED_PLAYLISTS -> state.searchFeaturedPlaylistsResult.isNotEmpty()
                SearchType.PODCASTS -> true
            }
        if (loaded) {
            _searchScreenUIState.value = SearchScreenUIState.Success
            return
        }
        when (type) {
            SearchType.SONGS -> searchSongs(query)
            SearchType.VIDEOS -> searchVideos(query)
            SearchType.ALBUMS -> searchAlbums(query)
            SearchType.ARTISTS -> searchArtists(query)
            SearchType.PLAYLISTS -> searchPlaylists(query)
            SearchType.FEATURED_PLAYLISTS -> searchFeaturedPlaylist(query)
            else -> Unit
        }
    }

    fun suggestQuery(query: String) {
        // A slow answer for an older prefix must never replace the newer one.
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            searchRepository.getSuggestQuery(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { suggestData ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    suggestQueries = suggestData.queries,
                                    suggestYTItems = suggestData.recommendedItems,
                                )
                            }
                        }
                    }

                    is Resource.Error -> {
                        // Không cần xử lý lỗi đặc biệt cho gợi ý
                        log("Error fetching suggest queries: ${values.message}", LogLevel.ERROR)
                    }
                }
            }
        }
    }

    fun searchAlbums(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataAlbum(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { albumsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ALBUMS,
                                    searchAlbumsResult = albumsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchFeaturedPlaylist(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataFeaturedPlaylist(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { featuredPlaylistList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.FEATURED_PLAYLISTS,
                                    searchFeaturedPlaylistsResult = featuredPlaylistList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchPodcast(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataPodcast(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { podcastList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PODCASTS,
                                    searchPodcastsResult = podcastList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchArtists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataArtist(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { artistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ARTISTS,
                                    searchArtistsResult = artistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchPlaylists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataPlaylist(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { playlistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PLAYLISTS,
                                    searchPlaylistsResult = playlistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchVideos(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            searchRepository.getSearchDataVideo(query).collect { values ->
                if (_searchScreenState.value.query.isNotBlank() && _searchScreenState.value.query != query) return@collect
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { videosList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.VIDEOS,
                                    searchVideosResult = videosList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun setSearchType(searchType: SearchType) {
        _searchScreenState.update { state ->
            state.copy(searchType = searchType)
        }
    }
}