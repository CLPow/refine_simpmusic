package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.music_video
import simpmusic.composeapp.generated.resources.new_release
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.view_count

/**
 * YouTube Music's Explore tab: its own Explore page (FEmusic_explore), plus the three pages its
 * buttons open: new releases, charts for a country, and moods & genres. Each loads once, on
 * demand, and independently, so one failing never blanks the others.
 */
class ExploreViewModel(
    private val homeRepository: HomeRepository,
    private val dataStoreManager: DataStoreManager,
) : BaseViewModel() {
    private val _shelves = MutableStateFlow<List<HomeItem>?>(null)
    val shelves: StateFlow<List<HomeItem>?> = _shelves.asStateFlow()

    private val _newReleases = MutableStateFlow<List<HomeItem>?>(null)
    val newReleases: StateFlow<List<HomeItem>?> = _newReleases.asStateFlow()

    private val _chart = MutableStateFlow<Chart?>(null)
    val chart: StateFlow<Chart?> = _chart.asStateFlow()

    private val _chartRegion = MutableStateFlow<String?>(null)
    val chartRegion: StateFlow<String?> = _chartRegion.asStateFlow()

    private val _moods = MutableStateFlow<Mood?>(null)
    val moods: StateFlow<Mood?> = _moods.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        loadExplore()
        loadMoods()
    }

    fun loadExplore() {
        _failed.value = false
        viewModelScope.launch {
            try {
                homeRepository
                    .getExploreData(getString(Res.string.view_count), getString(Res.string.song))
                    .collect { result ->
                        when (result) {
                            is Resource.Success -> _shelves.value = result.data.orEmpty()
                            else -> _failed.value = _shelves.value.isNullOrEmpty()
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.value = _shelves.value.isNullOrEmpty()
            }
        }
    }

    fun loadNewReleases() {
        if (_newReleases.value != null) return
        viewModelScope.launch {
            runCatchingNonCancellable {
                homeRepository
                    .getNewRelease(getString(Res.string.new_release), getString(Res.string.music_video))
                    .collect { result -> if (result is Resource.Success) _newReleases.value = result.data.orEmpty() }
            }
        }
    }

    fun loadChart(region: String? = null) {
        viewModelScope.launch {
            val code = region ?: dataStoreManager.chartKey.first()
            if (region != null) dataStoreManager.setChartKey(region)
            _chartRegion.value = code
            _chart.value = null
            runCatchingNonCancellable {
                homeRepository.getChartData(code).collect { result ->
                    if (result is Resource.Success) _chart.value = result.data
                }
            }
        }
    }

    fun loadMoods() {
        if (_moods.value != null) return
        viewModelScope.launch {
            runCatchingNonCancellable {
                homeRepository.getMoodAndMomentsData().collect { result ->
                    if (result is Resource.Success) _moods.value = result.data
                }
            }
        }
    }

    private suspend fun runCatchingNonCancellable(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Explore load failed: ${e.message}")
        }
    }
}
