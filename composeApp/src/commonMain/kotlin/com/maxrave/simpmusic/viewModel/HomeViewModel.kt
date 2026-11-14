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
    /**
     * True when the saved sign-in no longer works. Home is then YouTube's signed-out feed, which
     * barely changes, so the screen says so and offers to sign in again instead of looking stuck.
     */
    val signInExpired: StateFlow<Boolean> = accountRepository.signInExpired

    /** True when the last load failed because there is no connection at all. */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    /** Asks the screen to scroll back to the top: a different chip is a different feed. */
    val scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The chip the shown feed belongs to; a load for another one clears the old feed first. */
    private var shownParams: String? = null
    private var shownAny = false

    private var continuationJob: Job? = null

    private val _homeItemList: MutableStateFlow<List<HomeItem>> =
        MutableStateFlow(arrayListOf())
    val homeItemList: StateFlow<List<HomeItem>> = _homeItemList

    /** The artwork YouTube sends with Home, drawn behind the top of the page; null when none. */
    private val _homeBackground = MutableStateFlow<String?>(null)
    val homeBackground: StateFlow<String?> = _homeBackground

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

    private val _accountInfo: MutableStateFlow<Pair<String?, String?>?> = MutableStateFlow(null)
    private var homeJob: Job? = null

    private val _chart: MutableStateFlow<Chart?> = MutableStateFlow(null)
    val chart: StateFlow<Chart?> = _chart
    var regionCodeChart: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Ceiling on one attempt at the feed; a slower answer counts as a failed attempt. */
    private val HOME_ATTEMPT_TIMEOUT_MS = 20_000L

    /** Attempts before Home gives up and shows its Retry card (about a minute and a half in all). */
    private val HOME_MAX_ATTEMPTS = 6

    /**
     * When the feed last loaded. The music service keeps the process alive for days, so a feed
     * fetched once at start went stale: songs played since never showed up in it. YouTube Music
     * reloads Home when you come back to it; [refreshIfStale] does the same.
     */
    private var lastLoadedAtMs = 0L

    /** Reloads Home when the shown feed is older than [STALE_AFTER_MS] (YouTube Music's behaviour on return), or when it failed. */
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
            // One load per change of region, account or chip. These used to be five
            // collectors, each starting (and cancelling) its own load at launch, one of them only
            // after a fixed one-second wait whenever a cookie existed: every cold start paid it.
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
        _recommendationsFailed.value = false
        _offline.value = false
        homeJob?.cancel()
        // A continuation still loading belongs to the old feed and must not land in the new one.
        continuationJob?.cancel()
        if (shownAny && params != shownParams) {
            // Another chip is another feed: YouTube Music clears the page and starts at the top
            // rather than leaving the previous chip's shelves up with no sign of loading.
            _homeItemList.value = emptyList()
            _continuation.value = null
            scrollToTop.tryEmit(Unit)
        }
        shownParams = params
        shownAny = true
        homeJob =
            viewModelScope.launch {
                regionCode = dataStoreManager.location.first()
                // A reload of the same feed keeps it on screen while YouTube answers, so the page
                // is never blank. No local shelves: YouTube Music's Home is YouTube's feed only.
                val previous = _homeItemList.value

                // YouTube Music never parks Home on an error at launch: it keeps the spinner and
                // asks again. A failed, empty or slow answer (a cold network, a 429 cooldown, the
                // cookie arriving late) is retried with a growing pause; only after every attempt
                // fails does the "Couldn't load" card with its Retry button show.
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
                    // No connection at all: YouTube Music says so at once instead of spinning for
                    // a minute and a half. Two attempts, so a network that is just waking counts.
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
                    // A dead cookie still gets a feed, only a signed-out one. Ask once per load.
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
                                // YouTube sometimes repeats a shelf across pages.
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
                                // Keep the continuation: the screen offers to try the next page again.
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
        /** How the network stack words "there is no network" on Android and the JVM. */
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

    /**
     * Speed dial (the web's "Listen again") shows what YouTube sends, and for a song that also has
     * an official video it sometimes sends the video: A2O's "NEON" came as KGK2KZOFAPE, its tile
     * a frame of the group, and playing it carried that frame into the player. Each such tile is
     * swapped for its song once found, so the tile shows the album cover and plays the song.
     * Tiles with no exact match stay as sent.
     */
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