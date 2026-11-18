package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.PodcastShow
import com.maxrave.domain.data.model.podcast.toPodcastEpisode
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class PodcastLoadState { Loading, Success, Error }

data class PodcastUiState(
    val loadState: PodcastLoadState = PodcastLoadState.Loading,
    val show: PodcastShow? = null,
    val loadingMore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModel(
    private val podcastRepository: PodcastRepository,
) : BaseViewModel() {
    private val podcastId = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(PodcastUiState())
    val uiState: StateFlow<PodcastUiState> = _uiState.asStateFlow()

    val saved: StateFlow<Boolean> =
        podcastId
            .flatMapLatest { id -> if (id == null) flowOf(false) else podcastRepository.isPodcastSaved(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val progress: StateFlow<Map<String, EpisodeEntity>> =
        _uiState
            .map { state -> state.show?.episodes?.map { it.videoId }.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(ids.chunked(500).map { podcastRepository.observeEpisodes(it) }) { lists ->
                        lists.flatMap { it }.associateBy { it.videoId }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var loadJob: Job? = null

    fun load(id: String) {
        if (podcastId.value == id && _uiState.value.loadState == PodcastLoadState.Success) return
        podcastId.value = id
        loadJob?.cancel()
        _uiState.value = PodcastUiState()
        loadJob =
            viewModelScope.launch {
                when (val result = podcastRepository.getPodcast(id).first()) {
                    is Resource.Success -> _uiState.value = PodcastUiState(PodcastLoadState.Success, result.data)
                    is Resource.Error -> _uiState.value = PodcastUiState(PodcastLoadState.Error)
                }
            }
    }

    fun retry() {
        val id = podcastId.value ?: return
        podcastId.value = null
        load(id)
    }

    fun loadMore() {
        val state = _uiState.value
        val show = state.show ?: return
        val continuation = show.continuation ?: return
        if (state.loadingMore) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = podcastRepository.getMoreEpisodes(show.id, show.title, continuation)
            _uiState.update { current ->
                val currentShow = current.show ?: return@update current.copy(loadingMore = false)
                if (page == null) {
                    current.copy(loadingMore = false, show = currentShow.copy(continuation = null))
                } else {
                    val known = currentShow.episodes.map { it.videoId }.toHashSet()
                    current.copy(
                        loadingMore = false,
                        show =
                            currentShow.copy(
                                episodes = currentShow.episodes + page.episodes.filter { it.videoId !in known }.map { it.copy(author = currentShow.author) },
                                continuation = page.continuation,
                            ),
                    )
                }
            }
        }
    }

    fun toggleSaved() {
        val show = _uiState.value.show ?: return
        viewModelScope.launch {
            if (saved.value) {
                podcastRepository.removePodcast(show.id)
            } else {
                podcastRepository.savePodcast(
                    PodcastsEntity(
                        podcastId = show.id,
                        title = show.title,
                        authorId = show.author?.id,
                        authorName = show.author?.name.orEmpty(),
                        description = show.description,
                        thumbnail = show.thumbnails.lastOrNull()?.url,
                        savedAt = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
    }

    fun setSavedForLater(
        episode: PodcastEpisode,
        saved: Boolean,
    ) {
        viewModelScope.launch { podcastRepository.setSavedForLater(episode, saved) }
    }
}

data class EpisodeUiState(
    val loadState: PodcastLoadState = PodcastLoadState.Loading,
    val episode: PodcastEpisode? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModel(
    private val podcastRepository: PodcastRepository,
) : BaseViewModel() {
    private val videoId = MutableStateFlow<String?>(null)
    private val remote = MutableStateFlow<PodcastEpisode?>(null)
    private val failed = MutableStateFlow(false)

    val entity: StateFlow<EpisodeEntity?> =
        videoId
            .flatMapLatest { id -> if (id == null) flowOf(null) else podcastRepository.observeEpisode(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<EpisodeUiState> =
        combine(entity, remote, failed) { local, fetched, error ->
            val base = local?.toPodcastEpisode()
            val merged =
                when {
                    fetched == null -> base
                    base == null -> fetched
                    else ->
                        base.copy(
                            podcastId = base.podcastId ?: fetched.podcastId,
                            podcastTitle = base.podcastTitle ?: fetched.podcastTitle,
                            description = base.description ?: fetched.description,
                            createdDay = base.createdDay ?: fetched.createdDay,
                            thumbnails = fetched.thumbnails.ifEmpty { base.thumbnails },
                        )
                }
            when {
                merged != null -> EpisodeUiState(PodcastLoadState.Success, merged)
                error -> EpisodeUiState(PodcastLoadState.Error)
                else -> EpisodeUiState()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeUiState())

    fun load(id: String) {
        if (videoId.value == id && !failed.value) return
        videoId.value = id
        remote.value = null
        failed.value = false
        viewModelScope.launch {
            val fetched = podcastRepository.getEpisode(id)
            if (videoId.value != id) return@launch
            remote.value = fetched
            failed.value = fetched == null
        }
    }

    fun retry() {
        val id = videoId.value ?: return
        failed.value = false
        videoId.value = null
        load(id)
    }

    fun setSavedForLater(saved: Boolean) {
        val episode = uiState.value.episode ?: return
        viewModelScope.launch { podcastRepository.setSavedForLater(episode, saved) }
    }
}

class EpisodesForLaterViewModel(
    private val podcastRepository: PodcastRepository,
) : BaseViewModel() {
    val episodes: StateFlow<List<EpisodeEntity>> =
        podcastRepository.episodesForLater().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(episode: EpisodeEntity) {
        viewModelScope.launch { podcastRepository.setSavedForLater(episode.toPodcastEpisode(), false) }
    }
}
