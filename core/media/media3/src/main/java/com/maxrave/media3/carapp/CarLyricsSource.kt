package com.maxrave.media3.carapp

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsSourceOrder
import com.maxrave.domain.lyrics.stripRichSyncTimestamps
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toLyrics
import com.maxrave.domain.utils.toLyricsEntity
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

private const val TAG = "CarLyrics"

/** One lyric line reduced to what a clock needs: when it starts, and what it says. */
internal data class TimedLine(
    val startMs: Long,
    val text: String,
)

/**
 * Finds timed lyrics for the track playing in the car.
 *
 * Separate from the player's own lyrics loading, which lives in the UI layer and is built around a
 * screen: it carries translation, romanization, contribution prompts and view state, none of which
 * survive the trip to a head unit, and none of which exist when the phone's UI was never opened —
 * which is the normal case when someone gets in the car and presses play on the dashboard.
 *
 * What is deliberately shared is [LyricsSourceOrder]: asking sources in a different order here than
 * on the phone would mean a track that shows lyrics in one place and not the other, for no reason a
 * user could ever discover.
 *
 * Only line-timed lyrics are of any use. A plain block of text cannot be advanced against the
 * clock, and putting a whole song's words in a one-line field would be worse than showing nothing.
 */
internal class CarLyricsSource(
    private val lyricsCanvasRepository: LyricsCanvasRepository,
    private val dataStoreManager: DataStoreManager,
) {
    /**
     * Last resolution, keyed by video.
     *
     * A track played twice in a session — a repeat, or a queue that loops — should not go back to
     * the network, and a track that genuinely has no timed lyrics should not be looked up again
     * every time it comes round. `null` is therefore a cached answer in its own right.
     */
    private val cache = mutableMapOf<String, List<TimedLine>?>()

    @Volatile
    private var purgedStaleCache = false

    /**
     * Timed lines for [song], or null when no source has any.
     *
     * Consults the local database first: anything ever shown on the phone is already there, which
     * makes the common case free and keeps the car from waiting on the network at a track change.
     */
    suspend fun syncedLines(song: SongEntity): List<TimedLine>? {
        val videoId = song.videoId
        if (videoId.isBlank()) return null
        synchronized(cache) {
            if (cache.containsKey(videoId)) return cache[videoId]
        }

        val resolved =
            withContext(Dispatchers.IO) {
                // The phone's UI does this too, but it may never have run: someone can get into
                // the car and press play without opening the app at all. Once per process is
                // enough -- the repository would no-op after the first call anyway, and this keeps
                // a settings read off every track change.
                if (!purgedStaleCache) {
                    purgedStaleCache = true
                    runCatching { lyricsCanvasRepository.purgeLyricsCacheIfStale(dataStoreManager) }
                }
                runCatching { resolve(song) }
                    .onFailure { Logger.w(TAG, "Lyrics lookup failed for $videoId: ${it.message}") }
                    .getOrNull()
            }

        synchronized(cache) {
            // Bounded so a long drive through a big queue cannot grow this without limit. The
            // number is arbitrary but generous: far more tracks than one journey plays.
            if (cache.size >= MAX_CACHED_TRACKS) cache.clear()
            cache[videoId] = resolved
        }
        return resolved
    }

    /** Forgets everything, so a returning car re-reads lyrics saved since it was last attached. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private suspend fun resolve(song: SongEntity): List<TimedLine>? {
        val videoId = song.videoId
        lyricsCanvasRepository
            .getSavedLyrics(videoId)
            .firstOrNull()
            ?.toLyrics()
            ?.toTimedLines()
            ?.let {
                Logger.d(TAG, "Using saved lyrics for $videoId")
                return it
            }

        val artist = song.artistName?.firstOrNull().orEmpty()
        val duration = song.durationSeconds.takeIf { it > 0 }

        for (source in LyricsSourceOrder.DEFAULT) {
            val lyrics = fetch(source, song, artist, duration) ?: continue
            val lines = lyrics.toTimedLines() ?: continue
            Logger.d(TAG, "Found timed lyrics for $videoId from $source")
            // Saved so the phone's own lyrics view gets it for free next time, exactly as it would
            // have been had the lookup happened there.
            runCatching { lyricsCanvasRepository.insertLyrics(lyrics.toLyricsEntity(videoId)) }
            return lines
        }
        Logger.d(TAG, "No timed lyrics for $videoId from any source")
        return null
    }

    private suspend fun fetch(
        source: String,
        song: SongEntity,
        artist: String,
        duration: Int?,
    ): Lyrics? =
        when (source) {
            DataStoreManager.SIMPMUSIC ->
                lyricsCanvasRepository.getSimpMusicLyrics(song.videoId).firstSuccess()

            LyricsSourceOrder.SPOTIFY ->
                // No token check first: the repository owns that, and asking it is the only way to
                // find out. Without a session it simply fails and the chain moves on.
                lyricsCanvasRepository
                    .getSpotifyLyrics(dataStoreManager, song.title, artist, duration)
                    .firstSuccess()

            DataStoreManager.LRCLIB ->
                lyricsCanvasRepository.getLrclibLyricsData(artist, song.title, duration).firstSuccess()

            DataStoreManager.BETTER_LYRICS ->
                lyricsCanvasRepository.getBetterLyrics(artist, song.title, duration).firstSuccess()

            DataStoreManager.YOUTUBE ->
                lyricsCanvasRepository
                    .getYouTubeCaption(dataStoreManager.youtubeSubtitleLanguage.first(), song.videoId)
                    .firstSuccess()
                    ?.first

            else -> null
        }

    private suspend fun <T> Flow<Resource<T>>.firstSuccess(): T? =
        runCatching {
            firstOrNull { it is Resource.Success && it.data != null }?.data
        }.getOrNull()

    private companion object {
        const val MAX_CACHED_TRACKS = 200
    }
}

/**
 * Converts lyrics to a clock-ready list, or null when they carry no usable timing.
 *
 * Lines whose start time will not parse are dropped rather than defaulted to zero: a line
 * pretending to start at the beginning would fire at the wrong moment, and one wrong line is more
 * confusing on a dashboard than one missing line. Sorted because not every source emits in order.
 */
private fun Lyrics.toTimedLines(): List<TimedLine>? {
    if (error) return null
    if (syncType != "LINE_SYNCED" && syncType != "RICH_SYNCED") return null
    val timed =
        lines
            ?.mapNotNull { line ->
                val startMs = line.startTimeMs.toLongOrNull() ?: return@mapNotNull null
                TimedLine(startMs = startMs, text = stripRichSyncTimestamps(line.words))
            }?.sortedBy { it.startMs }
            .orEmpty()
    return timed.takeIf { list -> list.any { it.text.isNotBlank() } }
}
