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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "CarLyrics"

/**
 * How often the current line is recomputed. Comfortably finer than any line is short, and cheap:
 * a position read and a scan of at most a few hundred entries.
 */
private const val TICK_MS = 250L

/**
 * Puts the current lyric line on the car's screen.
 *
 * Android Auto draws its own now-playing screen from the media session — the app owns no pixels
 * there, only the header of its browse surface — so the two rows of text the host renders are the
 * only place a lyric can go. This rewrites those rows as the song plays, which is the same lever
 * every other player that does this pulls, for the same reason: there is no other one.
 *
 * Confined to the moments a car is attached, and reverted the instant it detaches. The rewritten
 * metadata is the session's, so it is also what the phone's notification, the lock screen and any
 * Bluetooth display would show; that is acceptable in a car and would be vandalism anywhere else.
 *
 * Position comes from the player rather than being estimated from playback state and elapsed
 * wall-clock time. Companion apps that read another player's session have to guess, and drift
 * against the music; this app owns the player, so it can simply ask.
 *
 * In the Cast-enabled build the session player is a `CastPlayer` wrapping this one and derives its
 * metadata from media items rather than from the player beneath it, so the overlay does not reach
 * the head unit there. The shipped FOSS build has no such wrapper and is unaffected.
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

    /**
     * Begins watching for a car, once.
     *
     * Called from the media service, which Android recreates freely — a second collector would
     * publish every lyric line twice, and the two would race over what the rows say.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                CarConnectionState.isCarConnectedFlow,
                dataStoreManager.androidAutoLyricsMode,
            ) { carAttached, mode ->
                if (carAttached) mode else AA_LYRICS_OFF
            }.distinctUntilChanged()
                .collectLatest { mode ->
                    if (mode == AA_LYRICS_OFF) {
                        player.setMetadataOverlay(null)
                    } else {
                        Logger.i(TAG, "Car attached; showing lyrics in mode $mode")
                        // A car that comes back should see lyrics saved since it last left.
                        source.clearCache()
                        // Title carries the sung line on car: hosts typically render title
                        // larger than the artist/subtitle row. Users asked for bigger lyrics
                        // while driving; the host owns font size, so put the text where the
                        // larger type already is.
                        followTracks(AA_LYRICS_BOTH_LINES)
                    }
                }
        }
    }

    /**
     * Follows the playing track for as long as lyrics are wanted.
     *
     * Never returns normally: `collectLatest` cancels it when the car detaches or the mode changes,
     * and the overlay is cleared on the way out so the song's own name is restored.
     */
    private suspend fun followTracks(mode: String) {
        try {
            mediaPlayerHandler.nowPlayingState
                .map { it.songEntity }
                .distinctUntilChanged { old, new -> old?.videoId == new?.videoId }
                .collectLatest { song ->
                    // Show the real title while the lookup runs, and for any track without lyrics.
                    player.setMetadataOverlay(null)
                    val videoId = song?.videoId
                    if (song == null || videoId.isNullOrBlank()) return@collectLatest
                    val lines = source.syncedLines(song)
                    if (lines.isNullOrEmpty()) return@collectLatest
                    followLines(mode, lines)
                }
        } finally {
            player.setMetadataOverlay(null)
        }
    }

    /** Advances through [lines] against the playing position until cancelled. */
    private suspend fun followLines(
        mode: String,
        lines: List<TimedLine>,
    ) {
        var publishedIndex = Int.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            val position = mediaPlayerHandler.getProgress()
            val index = lines.indexOfLast { it.startMs <= position }
            if (index != publishedIndex) {
                publishedIndex = index
                player.setMetadataOverlay(overlayFor(mode, lines, index))
            }
            delay(TICK_MS)
        }
    }

    /**
     * The metadata to publish for the line at [index], or null to show the track's own.
     *
     * Null before the first line and through instrumental gaps, where sources emit an empty line.
     * Blanking the rows there would leave the driver looking at nothing, and holding the previous
     * line would be a lie about what is playing; showing what the song is fills the silence with
     * something true.
     */
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
                // The line after this one, skipping the blanks a gap is made of: showing an empty
                // second row for the length of an instrumental break wastes half the screen.
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
                // Title untouched: the driver can still tell what is playing at a glance, which is
                // the whole reason this mode is the default.
                builder
                    .setArtist(current)
                    .setSubtitle(current)

            else -> return null
        }.build()
    }
}
