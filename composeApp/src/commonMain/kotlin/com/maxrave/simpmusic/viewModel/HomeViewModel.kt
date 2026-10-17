package com.maxrave.simpmusic.viewModel

import com.maxrave.domain.data.model.home.HomeChip
import androidx.lifecycle.viewModelScope
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.HomeDataCombine
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
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
import kotlinx.coroutines.runBlocking
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.music_video
import simpmusic.composeapp.generated.resources.new_release
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.view_count
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.shelf_on_repeat
import simpmusic.composeapp.generated.resources.shelf_buried_favourites

class HomeViewModel(
    private val dataStoreManager: DataStoreManager,
    private val homeRepository: HomeRepository,
) : BaseViewModel() {
    private val _homeItemList: MutableStateFlow<List<HomeItem>> =
        MutableStateFlow(arrayListOf())
    val homeItemList: StateFlow<List<HomeItem>> = _homeItemList

    private var _homeListState = MutableStateFlow<ListState>(ListState.IDLE)
    val homeListState: StateFlow<ListState> = _homeListState

    private var _continuation = MutableStateFlow<String?>(null)
    val continuation: StateFlow<String?> = _continuation

    /**
     * True when YouTube's part of the home feed did not arrive.
     *
     * Distinct from an empty [homeItemList]: local shelves keep that non-empty, so without this the
     * screen cannot tell "nothing to show" from "recommendations failed to load" -- and the empty
     * state, with its retry, would never appear again.
     */
    private val _recommendationsFailed = MutableStateFlow(false)
    val recommendationsFailed: StateFlow<Boolean> = _recommendationsFailed

    private val _exploreMoodItem: MutableStateFlow<Mood?> = MutableStateFlow(null)
    val exploreMoodItem: StateFlow<Mood?> = _exploreMoodItem
    private val _accountInfo: MutableStateFlow<Pair<String?, String?>?> = MutableStateFlow(null)
    val accountInfo: StateFlow<Pair<String?, String?>?> = _accountInfo

    private var homeJob: Job? = null

    val showSnackBarErrorState = MutableSharedFlow<String>()

    private val _chart: MutableStateFlow<Chart?> = MutableStateFlow(null)
    val chart: StateFlow<Chart?> = _chart
    private val _newRelease: MutableStateFlow<List<HomeItem>> = MutableStateFlow(arrayListOf())
    val newRelease: StateFlow<List<HomeItem>> = _newRelease
    var regionCodeChart: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Ceiling on a home refresh before the spinner is released regardless of the sources. */
    private val HOME_LOAD_TIMEOUT_MS = 30_000L

    /**
     * When the feed last loaded. The music service keeps the process alive for days, so a feed
     * fetched once at start went stale: songs played since never showed up in it. YouTube Music
     * reloads Home when you come back to it; [refreshIfStale] does the same.
     */
    private var lastLoadedAtMs = 0L

    /** Reloads Home when the shown feed is older than [STALE_AFTER_MS] (YouTube Music's behaviour on return). */
    fun refreshIfStale() {
        if (lastLoadedAtMs != 0L && currentTimeMs() - lastLoadedAtMs > STALE_AFTER_MS && !loading.value) {
            getHomeItemList(params.value)
        }
    }

    private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private val STALE_AFTER_MS = 10 * 60_000L

    val loading = MutableStateFlow<Boolean>(true)
    val loadingChart = MutableStateFlow<Boolean>(true)
    private var regionCode: String = ""
    private var language: String = ""

    private val _songEntity: MutableStateFlow<SongEntity?> = MutableStateFlow(null)
    val songEntity: StateFlow<SongEntity?> = _songEntity

    private var _params: MutableStateFlow<String?> = MutableStateFlow(null)
    val params: StateFlow<String?> = _params

    /** The chips YouTube sent with the feed, in its order (they differ by account and region). */
    val homeChips: StateFlow<List<HomeChip>> = homeRepository.homeChips

    // For showing alert that should log in to YouTube
    private val _showLogInAlert: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLogInAlert: StateFlow<Boolean> = _showLogInAlert

    val dataSyncId =
        dataStoreManager
            .dataSyncId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _mainHomeThumbnail: MutableStateFlow<String?> = MutableStateFlow(null)
    val mainHomeThumbnail: StateFlow<String?> = _mainHomeThumbnail

    init {
        if (runBlocking { dataStoreManager.cookie.first() }.isEmpty() &&
            runBlocking {
                dataStoreManager.shouldShowLogInRequiredAlert.first() == TRUE
            }
        ) {
            _showLogInAlert.update { true }
        }
        homeJob = Job()
        viewModelScope.launch {
            regionCodeChart.value = dataStoreManager.chartKey.first()
            language = dataStoreManager.getString(SELECTED_LANGUAGE).first()
                ?: SUPPORTED_LANGUAGE.codes.first()
            // One load per change of region, language, account or chip. These used to be five
            // collectors, each starting (and cancelling) its own load at launch, one of them only
            // after a fixed one-second wait whenever a cookie existed: every cold start paid it.
            var lastCookie: String? = null
            val job1 =
                launch {
                    combine(
                        dataStoreManager.location.distinctUntilChanged(),
                        dataStoreManager.language.distinctUntilChanged(),
                        dataStoreManager.cookie.distinctUntilChanged(),
                        params,
                    ) { location, lang, cookie, chip -> Triple(location to lang, cookie, chip) }
                        .collectLatest { (locale, cookie, chip) ->
                            regionCode = locale.first
                            language = locale.second
                            val accountSwitched = lastCookie != null && lastCookie != cookie
                            lastCookie = cookie
                            if (accountSwitched) {
                                // A sign-in or account switch: the client picks the new cookie up
                                // from the same store, a moment after this flow sees it.
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
        language =
            runBlocking {
                dataStoreManager.getString(SELECTED_LANGUAGE).first()
                    ?: SUPPORTED_LANGUAGE.codes.first()
            }
        regionCode = runBlocking { dataStoreManager.location.first() }
        _recommendationsFailed.value = false
        homeJob?.cancel()
        homeJob =
            viewModelScope.launch {
                // The last feed stays on screen while YouTube answers, so the page is never blank.
                // No local shelves: YouTube Music's Home is YouTube's feed only, and the old
                // "On repeat" / "Buried favourites" shelves kept old songs pinned at the top.
                val previous = _homeItemList.value

                var homeDone = false
                launch {
                    delay(HOME_LOAD_TIMEOUT_MS)
                    if (!homeDone && loading.value) {
                        Logger.w("HomeViewModel", "Home load timed out after ${HOME_LOAD_TIMEOUT_MS}ms")
                        loading.value = false
                        _homeListState.value = ListState.IDLE
                        if (_homeItemList.value.isEmpty()) {
                            _recommendationsFailed.value = true
                        }
                    }
                }

                // Primary YouTube home feed. Must not use runCatching: it swallows
                // CancellationException and would mark a cancelled refresh as a failed load.
                try {
                    homeRepository
                        .getHomeData(
                            params,
                            getString(Res.string.view_count),
                            getString(Res.string.song),
                        ).collect { home ->
                            homeDone = true
                            when {
                                home is Resource.Success && !home.data?.second.isNullOrEmpty() -> {
                                    _recommendationsFailed.value = false
                                    _continuation.value = home.data?.first
                                    _homeItemList.value = home.data?.second ?: listOf()
                                    lastLoadedAtMs = currentTimeMs()
                                }

                                home is Resource.Success -> {
                                    Logger.w("HomeViewModel", "Home returned no shelves; treating as a failed load")
                                    _recommendationsFailed.value = true
                                    _continuation.value = null
                                    _homeItemList.value = previous
                                }

                                else -> {
                                    Logger.e("HomeViewModel", "Home load failed: ${home.message}")
                                    _recommendationsFailed.value = true
                                    _continuation.value = null
                                    _homeItemList.value = previous
                                }
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
                            if (home is Resource.Error) {
                                home.message?.let { showSnackBarErrorState.emit(it) }
                            }
                            loading.value = false
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("HomeViewModel", "Home collect failed: ${e.message}")
                    homeDone = true
                    loading.value = false
                    _recommendationsFailed.value = true
                    _homeListState.value = ListState.IDLE
                }
            }
    }

    fun getContinueHomeItem(continuation: String?) {
        viewModelScope.launch {
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
                                _homeItemList.update { it + newItems }
                                if (home.data?.first.isNullOrEmpty()) {
                                    _homeListState.value = ListState.PAGINATION_EXHAUST
                                } else {
                                    _homeListState.value = ListState.IDLE
                                }
                            }

                            is Resource.Error -> {
                                _continuation.value = null
                                Logger.w(tag, "getContinueHomeItem: ${home.message}")
                                showSnackBarErrorState.emit(home.message ?: "Unknown error")
                                _homeListState.value = ListState.PAGINATION_EXHAUST
                            }
                        }
                    }
            }
        }
    }

    fun exploreChart(region: String) {
        viewModelScope.launch {
            loadingChart.value = true
            homeRepository
                .getChartData(
                    region,
                ).collect { values ->
                    regionCodeChart.value = region
                    dataStoreManager.setChartKey(region)
                    when (values) {
                        is Resource.Success -> {
                            _chart.value = values.data
                        }

                        else -> {
                            _chart.value = null
                        }
                    }
                    loadingChart.value = false
                }
        }
    }

    fun setParams(params: String?) {
        _params.value = params
    }

    override fun onCleared() {
        super.onCleared()
        homeJob?.cancel()
    }

    companion object {
        // Home params
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