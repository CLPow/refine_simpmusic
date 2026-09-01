package com.maxrave.data.repository

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Shelves built from what the user has actually played, rather than from what YouTube suggests.
 *
 * The home feed is YouTube's, verbatim, and it only knows what happens in YouTube's own apps —
 * listening here reaches it slowly if at all. These shelves close that gap using data the app has
 * always collected locally: `totalPlayTime` is a play *counter* incremented once per track start,
 * and `inLibrary` is overwritten on every play, so it reads as "last played" rather than "date
 * added". Neither is gated on any setting and neither leaves the device.
 */
internal object LocalShelfBuilder {
    /** A shelf needs enough entries to look deliberate rather than like a bug. */
    private const val MIN_SHELF_SIZE = 4

    private const val MAX_SHELF_SIZE = 20

    /** Plays before a song counts as one the user actually returns to, not one they skipped past. */
    private const val ON_REPEAT_MIN_PLAYS = 2

    /** How long a well-played song must go untouched before it counts as buried. */
    private const val BURIED_AFTER_DAYS = 30L

    /**
     * Songs played most, most-played first.
     *
     * Deliberately not time-windowed: the point is the ones that stuck, not this week's churn.
     */
    fun onRepeat(
        songs: List<SongEntity>,
        title: String,
    ): HomeItem? =
        songs
            .filter { it.totalPlayTime >= ON_REPEAT_MIN_PLAYS }
            .sortedByDescending { it.totalPlayTime }
            .take(MAX_SHELF_SIZE)
            .toShelf(title)

    /**
     * Songs played often once and not heard in a long time.
     *
     * The one thing a recommender genuinely cannot do for you: it optimises for what you will play
     * next, which is never the record you loved two years ago and simply stopped reaching for.
     */
    @OptIn(ExperimentalTime::class)
    fun buriedFavourites(
        songs: List<SongEntity>,
        title: String,
        nowEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): HomeItem? {
        val cutoffMs = nowEpochMs - BURIED_AFTER_DAYS * MILLIS_PER_DAY
        return songs
            .filter { it.totalPlayTime >= ON_REPEAT_MIN_PLAYS }
            .filter { song -> song.lastPlayedEpochMs()?.let { it < cutoffMs } ?: false }
            .sortedByDescending { it.totalPlayTime }
            .take(MAX_SHELF_SIZE)
            .toShelf(title)
    }

    /**
     * Null rather than an empty shelf when there is too little to show.
     *
     * A row with two entries on a fresh install reads as something broken; no row reads as nothing
     * yet, which is the truth.
     */
    private fun List<SongEntity>.toShelf(title: String): HomeItem? {
        if (size < MIN_SHELF_SIZE) return null
        return HomeItem(
            contents = map { it.toContent() },
            title = title,
        )
    }

    private fun SongEntity.toContent(): Content =
        Content(
            album = null,
            artists = artistName?.mapIndexed { index, name -> Artist(id = artistId?.getOrNull(index), name = name) },
            description = null,
            isExplicit = isExplicit,
            playlistId = null,
            browseId = null,
            thumbnails = listOfNotNull(thumbnails?.let { Thumbnail(width = 544, url = it, height = 544) }),
            title = title,
            videoId = videoId,
            views = null,
            durationSeconds = durationSeconds,
            videoType = videoType,
        )

    /**
     * `inLibrary` is written with `toInstant(TimeZone.UTC)` elsewhere in the schema, so it holds a
     * local wall clock dressed as UTC. Read it back the same way, or the comparison drifts by the
     * offset.
     */
    @OptIn(ExperimentalTime::class)
    private fun SongEntity.lastPlayedEpochMs(): Long? =
        runCatching { inLibrary.toInstant(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
