package com.maxrave.simpmusic.viewModel

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

    val loading = MutableStateFlow<Boolean>(true)
    val loadingChart = MutableStateFlow<Boolean>(true)
    private var regionCode: String = ""
    private var language: String = ""

    private val _songEntity: MutableStateFlow<SongEntity?> = MutableStateFlow(null)
    val songEntity: StateFlow<SongEntity?> = _songEntity

    private var _params: MutableStateFlow<String?> = MutableStateFlow(null)
    val params: StateFlow<String?> = _params

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
            exploreChart(regionCodeChart.value ?: "ZZ")
            language = dataStoreManager.getString(SELECTED_LANGUAGE).first()
                ?: SUPPORTED_LANGUAGE.codes.first()
            //  refresh when region change
            val job1 =
                launch {
                    dataStoreManager.location.distinctUntilChanged().collect {
                        regionCode = it
                        getHomeItemList(params.value)
                    }
                }
            //  refresh when language change
            val job2 =
                launch {
                    dataStoreManager.language.distinctUntilChanged().collect {
                        language = it
                        getHomeItemList(params.value)
                    }
                }
            val job3 =
                launch {
                    dataStoreManager.cookie.distinctUntilChanged().collect {
                        getHomeItemList(params.value)
                        _accountInfo.emit(
                            Pair(
                                dataStoreManager.getString("AccountName").first(),
                                dataStoreManager.getString("AccountThumbUrl").first(),
                            ),
                        )
                    }
                }
            val job4 =
                launch {
                    params.collectLatest {
                        getHomeItemList(it)
                    }
                }
            val job5 =
                launch {
                    dataStoreManager
                        .cookie
                        .distinctUntilChanged()
                        .collectLatest {
                            if (it.isNotEmpty()) {
                                Logger.w(tag, "Cookie changed, refreshing home")
                                loading.value = true
                                delay(1000) // To wait for the cookie to be saved properly
                                getHomeItemList(params.value)
                            }
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
            job2.join()
            job3.join()
            job4.join()
            job5.join()
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
                // Instant: local shelves so the screen is never empty while YT answers.
                val localShelves =
                    homeRepository
                        .getLocalShelves(
                            onRepeatTitle = getString(Res.string.shelf_on_repeat),
                            buriedTitle = getString(Res.string.shelf_buried_favourites),
                        ).firstOrNull()
                        .orEmpty()
                // Keep the previous successful feed on screen while the next one loads —
                // missing shelves were the symptom of replacing a full list with nothing
                // while combine() waited for every source.
                val previous = _homeItemList.value
                if (previous.isNotEmpty()) {
                    _homeItemList.value = localShelves + previous.filterNot { it in localShelves }
                } else {
                    _homeItemList.value = localShelves
                }

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

                // Side channels — never block the home shelves.
                launch {
                    runCatching {
                        homeRepository.getMoodAndMomentsData().collect { mood ->
                            when (mood) {
                                is Resource.Success -> _exploreMoodItem.value = mood.data
                                else -> {}
                            }
                        }
                    }
                }
                launch {
                    runCatching {
                        homeRepository.getChartData(dataStoreManager.chartKey.first()).collect { chart ->
                            when (chart) {
                                is Resource.Success -> {
                                    _chart.value = chart.data
                                    regionCodeChart.value = dataStoreManager.chartKey.first()
                                }
                                else -> _chart.value = null
                            }
                        }
                    }
                }
                launch {
                    runCatching {
                        homeRepository
                            .getNewRelease(
                                getString(Res.string.new_release),
                                getString(Res.string.music_video),
                            ).collect { newRelease ->
                                when (newRelease) {
                                    is Resource.Success -> _newRelease.value = newRelease.data ?: arrayListOf()
                                    else -> {}
                                }
                            }
                    }
                }

                // Primary feed: emit as soon as it arrives.
                runCatching {
                    homeRepository
                        .getHomeData(
                            params,
                            getString(Res.string.view_count),
                            getString(Res.string.song),
                        ).collect { home ->
                            homeDone = true
                            when (home) {
                                is Resource.Success if !home.data?.second.isNullOrEmpty() -> {
                                    _recommendationsFailed.value = false
                                    _continuation.value = home.data?.first
                                    _homeItemList.value = localShelves + (home.data?.second ?: listOf())
                                }

                                is Resource.Success -> {
                                    Logger.w("HomeViewModel", "Home returned no shelves; treating as a failed load")
                                    _recommendationsFailed.value = true
                                    _continuation.value = null
                                    _homeItemList.value = localShelves + previous.filterNot { it in localShelves }
                                }

                                else -> {
                                    Logger.e("HomeViewModel", "Home load failed: ${home.message}")
                                    _recommendationsFailed.value = true
                                    _continuation.value = null
                                    _homeItemList.value = localShelves + previous.filterNot { it in localShelves }
                                }
                            }
                            if (continuation.value.isNullOrEmpty()) {
                                _homeListState.value = ListState.PAGINATION_EXHAUST
                            } else {
                                _homeListState.value = ListState.IDLE
                            }
                            dataStoreManager.cookie.first().let { c ->
                                if (c.isNotEmpty()) {
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
                }.onFailure {
                    Logger.e("HomeViewModel", "Home collect failed: ${it.message}")
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