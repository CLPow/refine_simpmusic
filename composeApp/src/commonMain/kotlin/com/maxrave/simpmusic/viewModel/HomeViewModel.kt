package com.maxrave.simpmusic.viewModel

import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withTimeoutOrNull
import com.maxrave.domain.data.model.home.HomeChip
import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.view_count
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.compose.resources.getString
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class HomeViewModel(
    private val dataStoreManager: DataStoreManager,
    private val homeRepository: HomeRepository,
    private val accountRepository: AccountRepository,
) : BaseViewModel() {
    val signInExpired: StateFlow<Boolean> = accountRepository.signInExpired

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    val scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var shownParams: String? = null
    private var shownAny = false

    private var continuationJob: Job? = null

    private val _homeItemList: MutableStateFlow<List<HomeItem>> =
        MutableStateFlow(arrayListOf())
    val homeItemList: StateFlow<List<HomeItem>> = _homeItemList

    private val _homeBackground = MutableStateFlow<String?>(null)
    val homeBackground: StateFlow<String?> = _homeBackground

    private var _homeListState = MutableStateFlow<ListState>(ListState.IDLE)
    val homeListState: StateFlow<ListState> = _homeListState

    private var _continuation = MutableStateFlow<String?>(null)
    val continuation: StateFlow<String?> = _continuation

    private val _recommendationsFailed = MutableStateFlow(false)
    val recommendationsFailed: StateFlow<Boolean> = _recommendationsFailed

    private val _accountInfo: MutableStateFlow<Pair<String?, String?>?> = MutableStateFlow(null)
    private var homeJob: Job? = null

    private val _chart: MutableStateFlow<Chart?> = MutableStateFlow(null)
    val chart: StateFlow<Chart?> = _chart
    var regionCodeChart: MutableStateFlow<String?> = MutableStateFlow(null)

    private val HOME_ATTEMPT_TIMEOUT_MS = 20_000L

    private val HOME_MAX_ATTEMPTS = 6

    private var lastLoadedAtMs = 0L

    fun refreshIfStale() {
        if (loading.value) return
        if (_recommendationsFailed.value || (lastLoadedAtMs != 0L && currentTimeMs() - lastLoadedAtMs > STALE_AFTER_MS)) {
            getHomeItemList(params.value)
        }
    }

    private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private val STALE_AFTER_MS = 10 * 60_000L

    val loading = MutableStateFlow<Boolean>(true)
    private var regionCode: String = ""

    private val _songEntity: MutableStateFlow<SongEntity?> = MutableStateFlow(null)
    val songEntity: StateFlow<SongEntity?> = _songEntity

    private var _params: MutableStateFlow<String?> = MutableStateFlow(null)
    val params: StateFlow<String?> = _params

    val homeChips: StateFlow<List<HomeChip>> = homeRepository.homeChips

    private val _showLogInAlert: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLogInAlert: StateFlow<Boolean> = _showLogInAlert

    val dataSyncId =
        dataStoreManager
            .dataSyncId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _mainHomeThumbnail: MutableStateFlow<String?> = MutableStateFlow(null)
    init {
        viewModelScope.launch {
            if (dataStoreManager.cookie.first().isEmpty() &&
                dataStoreManager.shouldShowLogInRequiredAlert.first() == TRUE
            ) {
                _showLogInAlert.update { true }
            }
        }
        homeJob = Job()
        viewModelScope.launch {
            regionCodeChart.value = dataStoreManager.chartKey.first()
            var lastCookie: String? = null
            val job1 =
                launch {
                    combine(
                        dataStoreManager.location.distinctUntilChanged(),
                        dataStoreManager.cookie.distinctUntilChanged(),
                        params,
                    ) { location, cookie, chip -> Triple(location, cookie, chip) }
                        .collectLatest { (location, cookie, chip) ->
                            regionCode = location
                            val accountSwitched = lastCookie != null && lastCookie != cookie
                            lastCookie = cookie
                            if (accountSwitched) {
                                Logger.w(tag, "Account changed, refreshing home")
                                loading.value = true
                                delay(1000)
                            }
                            getHomeItemList(chip)
                            _accountInfo.emit(
                                Pair(
                                    dataStoreManager.getString("AccountName").first(),
                                    dataStoreManager.getString("AccountThumbUrl").first(),
                                ),
                            )
                        }
                }
            val job6 =
                launch {
                    homeItemList.collectLatest { list ->
                        _mainHomeThumbnail.value =
                            list
                                .firstOrNull()
                                ?.contents
                                ?.firstOrNull()
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                    }
                }
            job1.join()
            job6.join()
        }
    }

    fun doneShowLogInAlert(neverShowAgain: Boolean = false) {
        viewModelScope.launch {
            _showLogInAlert.update { false }
            if (neverShowAgain) {
                dataStoreManager.setShouldShowLogInRequiredAlert(false)
            }
        }
    }

    fun getHomeItemList(params: String? = null) {
        loading.value = true
        _homeListState.value = ListState.LOADING
        _recommendationsFailed.value = false
        _offline.value = false
        homeJob?.cancel()
        // A continuation still loading belongs to the old feed and must not land in the new one.
        continuationJob?.cancel()
        if (shownAny && params != shownParams) {
            _homeItemList.value = emptyList()
            _continuation.value = null
            scrollToTop.tryEmit(Unit)
        }
        shownParams = params
        shownAny = true
        homeJob =
            viewModelScope.launch {
                regionCode = dataStoreManager.location.first()
                // A reload of the same feed keeps it on screen while YouTube answers, so the page is never blank.
                val previous = _homeItemList.value

                // YouTube Music never parks Home on an error at launch: it keeps the spinner and asks again.
                var home: Resource<Triple<String?, List<HomeItem>, String?>>? = null
                for (attempt in 0 until HOME_MAX_ATTEMPTS) {
                    if (attempt > 0) delay(minOf(1_000L shl (attempt - 1), 15_000L))
                    home =
                        try {
                            withTimeoutOrNull(HOME_ATTEMPT_TIMEOUT_MS) {
                                homeRepository
                                    .getHomeData(
                                        params,
                                        getString(Res.string.view_count),
                                        getString(Res.string.song),
                                    ).lastOrNull()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Resource.Error<Triple<String?, List<HomeItem>, String?>>(e.message ?: "Home unavailable")
                        }
                    if (home is Resource.Success && !home.data?.second.isNullOrEmpty()) break
                    if (home is Resource.Error && isOfflineError(home.message) && attempt >= 1) {
                        _offline.value = true
                        break
                    }
                    Logger.w(
                        "HomeViewModel",
                        "Home attempt ${attempt + 1}/$HOME_MAX_ATTEMPTS failed: " +
                            when (home) {
                                null -> "timed out"
                                is Resource.Error -> home.message
                                else -> "no shelves"
                            },
                    )
                }
                if (home is Resource.Success && !home.data?.second.isNullOrEmpty()) {
                    _recommendationsFailed.value = false
                    _offline.value = false
                    _continuation.value = home.data?.first
                    _homeItemList.value = home.data?.second ?: listOf()
                    _homeBackground.value = home.data?.third
                    lastLoadedAtMs = currentTimeMs()
                    launch { showSongVersionsOnSpeedDial() }
                    launch { accountRepository.verifySignIn() }
                } else {
                    _recommendationsFailed.value = true
                    _continuation.value = null
                    _homeItemList.value = previous
                }
                _homeListState.value =
                    if (continuation.value.isNullOrEmpty()) {
                        ListState.PAGINATION_EXHAUST
                    } else {
                        ListState.IDLE
                    }
                runCatching {
                    val cookie = dataStoreManager.cookie.first()
                    if (cookie.isNotEmpty()) {
                        _accountInfo.emit(
                            Pair(
                                dataStoreManager.getString("AccountName").first(),
                                dataStoreManager.getString("AccountThumbUrl").first(),
                            ),
                        )
                    }
                }
                loading.value = false
            }
    }

    fun getContinueHomeItem(continuation: String?) {
        continuationJob?.cancel()
        continuationJob = viewModelScope.launch {
            if (continuation.isNullOrEmpty()) {
                _homeListState.value = ListState.PAGINATION_EXHAUST
                return@launch
            } else {
                log("Get more home item with continuation: $continuation")
                _homeListState.value = ListState.PAGINATING
                homeRepository
                    .getHomeDataContinue(
                        continuation,
                        getString(Res.string.view_count),
                        getString(Res.string.song),
                    ).collect { home ->
                        when (home) {
                            is Resource.Success -> {
                                _continuation.value = home.data?.first
                                val newItems = home.data?.second ?: listOf()
                                _homeItemList.update { shown ->
                                    val seen = shown.map { it.title + "|" + it.subtitle.orEmpty() }.toSet()
                                    shown + newItems.filter { (it.title + "|" + it.subtitle.orEmpty()) !in seen }
                                }
                                if (home.data?.first.isNullOrEmpty()) {
                                    _homeListState.value = ListState.PAGINATION_EXHAUST
                                } else {
                                    _homeListState.value = ListState.IDLE
                                }
                            }

                            is Resource.Error -> {
                                Logger.w(tag, "getContinueHomeItem: ${home.message}")
                                _homeListState.value = ListState.ERROR
                            }
                        }
                    }
            }
        }
    }

    fun setParams(params: String?) {
        _params.value = params
    }

    private fun isOfflineError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return OfflineHints.ALL.any { it in m }
    }

    private object OfflineHints {
        val ALL =
            listOf(
                "unable to resolve host",
                "unresolvedaddress",
                "unknownhost",
                "no address associated",
                "network is unreachable",
                "nodename nor servname",
                "failed to connect",
                "no route to host",
                "software caused connection abort",
            )
    }

    override fun onCleared() {
        super.onCleared()
        homeJob?.cancel()
    }

    private suspend fun showSongVersionsOnSpeedDial() {
        val videos =
            _homeItemList.value
                .filter { it.isSpeedDial }
                .flatMap { it.contents.filterNotNull() }
                .filter { !it.videoId.isNullOrEmpty() && MusicVideoType.isOfficialVideo(it.videoType) }
                .distinctBy { it.videoId }
        if (videos.isEmpty()) return
        val songs =
            videos
                .map { tile -> viewModelScope.async { tile.videoId to songVersionLookup.findSongVersion(tile.toTrack()) } }
                .awaitAll()
                .mapNotNull { (videoId, song) -> song?.let { videoId to it } }
                .toMap()
        if (songs.isEmpty()) return
        _homeItemList.update { shelves ->
            shelves.map { shelf ->
                if (!shelf.isSpeedDial) {
                    shelf
                } else {
                    shelf.copy(
                        contents =
                            shelf.contents.map { tile ->
                                val song = tile?.videoId?.let(songs::get) ?: return@map tile
                                tile.copy(
                                    videoId = song.videoId,
                                    videoType = song.videoType,
                                    thumbnails = song.thumbnails?.takeIf { it.isNotEmpty() } ?: tile.thumbnails,
                                    album = song.album ?: tile.album,
                                    durationSeconds = song.durationSeconds ?: tile.durationSeconds,
                                )
                            },
                    )
                }
            }
        }
    }

    companion object {
        const val HOME_PARAMS_RELAX = "ggM8SgQIBxADSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SLEEP = "ggM8SgQIBxABSgQIBRADSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ENERGIZE = "ggM8SgQIBxABSgQIBRABSgQICRADSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SAD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChADSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ROMANCE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRADSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_FEEL_GOOD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBADSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_WORKOUT = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBADSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_PARTY = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhADSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_COMMUTE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxADSgQIBhAB"
        const val HOME_PARAMS_FOCUS = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAD"
    }
}