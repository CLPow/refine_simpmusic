package com.maxrave.media3.carapp

import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.AA_LYRICS_BOTH_LINES
import com.maxrave.domain.manager.DataStoreManager.Values.AA_LYRICS_OFF
import com.maxrave.domain.manager.DataStoreManager.Values.AA_LYRICS_TITLE_AND_LYRIC
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.exoplayer.DelegatingForwardingPlayer
import com.maxrave.media3.service.CarConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

private const val TAG = "CarLyrics"

/** How often the current line is recomputed. */
private const val TICK_MS = 100L

/** Default head-unit paint/transport latency compensation when the setting is unset. */
private const val DEFAULT_LEAD_MS = 1000L

/** Backoff after an unexpected pipeline failure so a flaky player cannot busy-loop. */
private const val RESTART_DELAY_MS = 1_000L

/**
 * Puts the current lyric line on the car's screen.
 *
 * Android Auto draws its own now-playing screen from the media session — the app owns no pixels
 * there, only the header of its browse surface — so the two rows of text the host renders are the
 * only place a lyric can go. This rewrites those rows as the song plays.
 *
 * The whole pipeline is crash-isolated: a single failed progress read or overlay write used to
 * cancel `collectLatest`, leave `started == true`, and kill lyrics for the rest of the drive
 * ("stopped after ~1.5 songs"). Failures now log, back off, and restart. [start] still runs once
 * per process so lines are not published twice.
 */
@UnstableApi
internal class CarLyricsOverlay(
    private val player: DelegatingForwardingPlayer,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val dataStoreManager: DataStoreManager,
    private val source: CarLyricsSource,
    private val scope: CoroutineScope,
) {
    private var started = false

    @Volatile
    private var followJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                try {
                    runPipeline()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Logger.e(TAG, "Lyrics pipeline failed; restarting in ${RESTART_DELAY_MS}ms: ${e.message}")
                    try {
                        player.setMetadataOverlay(null)
                    } catch (_: Exception) {
                    }
                    delay(RESTART_DELAY_MS)
                }
            }
        }
    }

    /** Outer loop: car attached + mode. Never returns normally; [start] restarts on failure. */
    private suspend fun runPipeline() {
        var lastMode: String? = null
        combine(
            CarConnectionState.isCarConnectedFlow,
            dataStoreManager.androidAutoLyricsMode,
        ) { carAttached, mode ->
            if (carAttached) mode else AA_LYRICS_OFF
        }.distinctUntilChanged()
            .collect { mode ->
                // Only restart when the mode actually changes. DataStore can re-emit
                // on unrelated preference writes; cancelling mid-song then wiping the
                // cache is why lyrics died after ~1.5 tracks on a long drive.
                if (mode == lastMode) return@collect
                lastMode = mode
                followJob?.cancel()
                followJob = null
                if (mode == AA_LYRICS_OFF) {
                    safeClearOverlay()
                    return@collect
                }
                Logger.i(TAG, "Car attached; showing lyrics in mode $mode")
                source.clearCache()
                // Title carries the sung line: hosts typically render title larger than artist.
                followJob =
                    scope.launch {
                        try {
                            followTracks(AA_LYRICS_BOTH_LINES)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (e: Exception) {
                            Logger.e(TAG, "followTracks failed: ${e.message}")
                        } finally {
                            safeClearOverlay()
                        }
                    }
            }
    }

    private fun safeClearOverlay() {
        runCatching { player.setMetadataOverlay(null) }
    }

    /**
     * Follows the playing track. [collect] (not collectLatest) so a thrown error inside one song
     * cannot cancel the collector itself; the per-song work is cancelled via [followJob].
     */
    private suspend fun followTracks(mode: String) {
        supervisorScope {
            var songJob: Job? = null
            mediaPlayerHandler.nowPlayingState
                .map { it.songEntity }
                .distinctUntilChanged { old, new -> old?.videoId == new?.videoId }
                .collect { song ->
                    songJob?.cancel()
                    safeClearOverlay()
                    val videoId = song?.videoId
                    if (song == null || videoId.isNullOrBlank()) return@collect
                    songJob =
                        launch {
                            try {
                                val lines = source.syncedLines(song)
                                if (lines.isNullOrEmpty()) return@launch
                                followLines(mode, lines)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (e: Exception) {
                                Logger.e(TAG, "Lyrics for $videoId failed: ${e.message}")
                            }
                        }
                }
        }
    }

    /** Advances through [lines] against the playing position until cancelled. */
    private suspend fun followLines(
        mode: String,
        lines: List<TimedLine>,
    ) {
        var publishedIndex = Int.MIN_VALUE
        // Re-read occasionally so a Settings tweak applies mid-drive without restarting the service.
        var leadMs = DEFAULT_LEAD_MS
        var syncOffsetMs = 0L
        var lastPrefsReadMs = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now - lastPrefsReadMs > 2_000L) {
                lastPrefsReadMs = now
                runCatching {
                    leadMs = dataStoreManager.aaLyricsLeadMs.first().toLong()
                    syncOffsetMs = dataStoreManager.lyricsSyncOffsetMs.first().toLong()
                }
            }
            val position =
                try {
                    mediaPlayerHandler.getProgress() + leadMs + syncOffsetMs
                } catch (e: Exception) {
                    Logger.w(TAG, "getProgress failed mid-lyrics: ${e.message}")
                    0L
                }
            val index = lines.indexOfLast { it.startMs <= position }
            if (index != publishedIndex) {
                publishedIndex = index
                try {
                    player.setMetadataOverlay(overlayFor(mode, lines, index))
                } catch (e: Exception) {
                    Logger.w(TAG, "setMetadataOverlay failed: ${e.message}")
                }
            }
            delay(TICK_MS)
        }
    }

    private fun overlayFor(
        mode: String,
        lines: List<TimedLine>,
        index: Int,
    ): MediaMetadata? {
        val current = lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() } ?: return null
        val base = player.underlyingMediaMetadata
        val builder = base.buildUpon()
        return when (mode) {
            AA_LYRICS_BOTH_LINES -> {
                val next =
                    lines
                        .asSequence()
                        .drop(index + 1)
                        .map { it.text }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                builder
                    .setTitle(current)
                    .setDisplayTitle(current)
                    .setArtist(next)
                    .setSubtitle(next)
            }

            AA_LYRICS_TITLE_AND_LYRIC ->
                builder
                    .setArtist(current)
                    .setSubtitle(current)

            else -> return null
        }.build()
    }
}
