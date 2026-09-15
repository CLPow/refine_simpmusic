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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "CarLyrics"

/** How often the current line is recomputed. */
private const val TICK_MS = 100L

/** How often we re-check which track is playing (no flow cancellation). */
private const val TRACK_POLL_MS = 250L

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
 * ## Why this is a poller, not a flow collector
 * Earlier versions collected `nowPlayingState` with `collectLatest` per song. A cancelled child
 * (or a one-shot exception) left `started == true` and killed lyrics for the rest of the drive
 * (~1.5 songs). This implementation:
 * - keeps ONE loop alive for the whole car session
 * - tracks the playing `videoId` by polling
 * - never cancels the pipeline between tracks
 * - restarts the outer loop on any unexpected exception
 * - keeps the real title in `TITLE_AND_LYRIC` so notification / widget still show title + artist
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

    private suspend fun runPipeline() {
        while (currentCoroutineContext().isActive) {
            val attached = CarConnectionState.isCarConnected
            val mode =
                if (attached) {
                    dataStoreManager.androidAutoLyricsMode.first()
                } else {
                    AA_LYRICS_OFF
                }
            if (mode == AA_LYRICS_OFF) {
                followJob?.cancel()
                followJob = null
                safeClearOverlay()
                delay(1_000)
                continue
            }
            if (followJob?.isActive != true) {
                Logger.i(TAG, "Car attached; lyrics pipeline running mode=$mode")
                source.clearCache()
                followJob =
                    scope.launch {
                        try {
                            followTracks(mode)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (e: Exception) {
                            Logger.e(TAG, "followTracks failed: ${e.message}")
                        } finally {
                            safeClearOverlay()
                        }
                    }
            }
            delay(2_000)
        }
    }

    private fun safeClearOverlay() {
        runCatching { player.setMetadataOverlay(null) }
    }

    /**
     * Follows the playing track by polling `videoId`. No `collectLatest`: a song change
     * must not cancel the loop that keeps the overlay alive.
     */
    private suspend fun followTracks(mode: String) {
        var lastVideoId: String? = null
        var lines: List<TimedLine>? = null
        var publishedIndex = Int.MIN_VALUE
        var leadMs = DEFAULT_LEAD_MS
        var syncOffsetMs = 0L
        var lastPrefsReadMs = 0L

        while (currentCoroutineContext().isActive) {
            val videoId =
                try {
                    mediaPlayerHandler.nowPlayingState.value.songEntity?.videoId
                } catch (e: Exception) {
                    Logger.w(TAG, "nowPlayingState read failed: ${e.message}")
                    null
                }
            if (videoId != lastVideoId) {
                lastVideoId = videoId
                publishedIndex = Int.MIN_VALUE
                lines = null
                safeClearOverlay()
                if (!videoId.isNullOrBlank()) {
                    val song = mediaPlayerHandler.nowPlayingState.value.songEntity
                    if (song != null) {
                        lines =
                            try {
                                source.syncedLines(song)
                            } catch (e: Exception) {
                                Logger.w(TAG, "syncedLines failed for $videoId: ${e.message}")
                                null
                            }
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastPrefsReadMs > 2_000L) {
                lastPrefsReadMs = now
                runCatching {
                    leadMs = dataStoreManager.aaLyricsLeadMs.first().toLong()
                    syncOffsetMs = dataStoreManager.lyricsSyncOffsetMs.first().toLong()
                }
            }

            val active = lines
            if (active != null && active.isNotEmpty() && !videoId.isNullOrBlank()) {
                val position =
                    try {
                        mediaPlayerHandler.getProgress() + leadMs + syncOffsetMs
                    } catch (e: Exception) {
                        Logger.w(TAG, "getProgress failed: ${e.message}")
                        0L
                    }
                val index = active.indexOfLast { it.startMs <= position }
                if (index != publishedIndex) {
                    publishedIndex = index
                    try {
                        player.setMetadataOverlay(overlayFor(mode, active, index))
                    } catch (e: Exception) {
                        Logger.w(TAG, "setMetadataOverlay failed: ${e.message}")
                    }
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

            // Keep the real title (notification / widget / car header). Lyric goes in the
            // artist/subtitle row — the only spare slot Android Auto exposes.
            AA_LYRICS_TITLE_AND_LYRIC ->
                builder
                    .setArtist(current)
                    .setSubtitle(current)

            else -> return null
        }.build()
    }
}
