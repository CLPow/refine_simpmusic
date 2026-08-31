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
 * Drives the Spotify library import.
 *
 * Unlike the file importer, this one is safe to stop part-way: each playlist is committed on its
 * own once its tracks resolve, so cancelling loses only the playlist in flight. It has to be
 * stoppable, too — matching a large account takes long enough that being trapped in a modal until
 * it finishes would be its own bug.
 */
class SpotifyImportViewModel(
    private val spotifyImportRepository: SpotifyImportRepository,
) : BaseViewModel() {
    private val _importState: MutableStateFlow<SpotifyImportProgress?> = MutableStateFlow(null)

    /** `null` while idle; otherwise the latest step of the running or finished import. */
    val importState: StateFlow<SpotifyImportProgress?> = _importState.asStateFlow()

    private var importJob: Job? = null

    fun start() {
        // Already running: a second tap should not launch a competing sweep against the same account.
        if (importJob?.isActive == true) return
        importJob =
            viewModelScope.launch {
                spotifyImportRepository.importEverything().collect { progress ->
                    _importState.value = progress
                }
            }
    }

    /** Stops an import in progress. Playlists already written stay written. */
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
