package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives a Spotify playlist import from a shared link.
 *
 * Safe to stop part-way: nothing is written until every track has been looked up, so cancelling
 * simply abandons the work. It has to be stoppable — a hundred tracks is a couple of minutes, and
 * being trapped in a modal until it finishes would be its own bug.
 */
class SpotifyImportViewModel(
    private val spotifyImportRepository: SpotifyImportRepository,
) : BaseViewModel() {
    private val _importState: MutableStateFlow<SpotifyImportProgress?> = MutableStateFlow(null)

    /** `null` while idle; otherwise the latest step of the running or finished import. */
    val importState: StateFlow<SpotifyImportProgress?> = _importState.asStateFlow()

    private var importJob: Job? = null

    fun start(link: String) {
        // Already running: a second tap should not launch a competing import.
        if (importJob?.isActive == true) return
        importJob =
            viewModelScope.launch {
                spotifyImportRepository.importFromLink(link).collect { progress ->
                    _importState.value = progress
                }
            }
    }

    /** Syncs the whole Spotify library. Re-runnable: see [SpotifyImportRepository.syncEverything]. */
    fun syncEverything() {
        if (importJob?.isActive == true) return
        importJob =
            viewModelScope.launch {
                spotifyImportRepository.syncEverything().collect { progress ->
                    _importState.value = progress
                }
            }
    }

    /** Stops an import in progress. Nothing is written until it completes, so nothing is left behind. */
    fun cancel() {
        importJob?.cancel()
        importJob = null
        _importState.value = null
    }

    /** Back to idle, which is what dismisses the result dialog. */
    fun dismiss() {
        importJob = null
        _importState.value = null
    }
}
