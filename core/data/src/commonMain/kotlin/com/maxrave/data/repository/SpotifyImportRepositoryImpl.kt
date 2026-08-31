package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.domain.repository.SpotifyImportResult
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.model.response.spotify.SpotifyEmbedResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "SpotifyImport"

/**
 * Gap between YouTube searches.
 *
 * One query per track, and firing them as fast as the network allows is what gets an address
 * rate-limited part-way through, leaving a half-matched playlist behind. The jitter keeps the
 * traffic from looking metronomic. The searches themselves carry no session — see
 * `YouTube.searchSongsSignedOut`.
 */
private const val SEARCH_INTERVAL_MS = 1_000L
private const val SEARCH_JITTER_MS = 400L

/** Below this, the best candidate is treated as "not on YouTube" rather than forced into a match. */
private const val MATCH_THRESHOLD = 0.55

/** Tracks written per transaction, mirroring the file importer's batching. */
private const val SONG_BATCH_SIZE = 500

/**
 * What the embed page returns at most. It states no total, so exactly this many coming back is the
 * only available signal that the playlist may be longer than what was read.
 */
private const val EMBED_TRACK_CAP = 100

/**
 * Consecutive failed YouTube searches before the import gives up.
 *
 * A failed search degrades to "unmatched", which is right for a track that genuinely is not there
 * and badly wrong if the searches are being refused wholesale — that would quietly produce an
 * empty playlist and call it a success.
 */
private const val SEARCH_FAILURE_LIMIT = 10

/**
 * Every shape Spotify hands out for a playlist link: the web URL, the localised web URL
 * (`/intl-de/playlist/...`), and the `spotify:playlist:` URI the mobile apps copy.
 */
private val PLAYLIST_LINK =
    Regex(
        """(?:spotify:playlist:|(?:https?://)?open\.spotify\.com/(?:intl-[A-Za-z-]+/)?playlist/)([A-Za-z0-9]+)""",
    )

internal class SpotifyImportRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val spotify: Spotify,
    private val youTube: YouTube,
) : SpotifyImportRepository {
    override fun importFromLink(link: String): Flow<SpotifyImportProgress> =
        flow {
            emit(SpotifyImportProgress.Preparing)

            val playlistId =
                PLAYLIST_LINK.find(link.trim())?.groupValues?.get(1) ?: run {
                    emit(
                        SpotifyImportProgress.Error(
                            "That does not look like a Spotify playlist link. In Spotify use Share, then Copy link to playlist.",
                        ),
                    )
                    return@flow
                }

            val entity =
                spotify
                    .getPlaylistFromEmbed(playlistId)
                    .getOrElse { throwable ->
                        Logger.e(TAG, "embed read failed: ${throwable.message}")
                        emit(SpotifyImportProgress.Error(throwable.message ?: "Couldn't read that playlist"))
                        return@flow
                    }

            val name = entity.name ?: entity.title ?: "Spotify playlist"
            // A track with no title is nothing to search for; a podcast episode in a mixed playlist
            // is the usual source of one.
            val tracks = entity.trackList.filter { !it.title.isNullOrBlank() }
            if (tracks.isEmpty()) {
                emit(SpotifyImportProgress.Error("That playlist has no tracks to import."))
                return@flow
            }

            val total = tracks.size
            val videoIds = mutableListOf<String>()
            val songs = mutableListOf<SongEntity>()
            // One search per distinct track: a playlist repeating a song should not pay for it twice.
            val resolved = mutableMapOf<String, String?>()
            var processed = 0
            var matched = 0
            var unmatched = 0
            var consecutiveFailures = 0

            for (track in tracks) {
                emit(
                    SpotifyImportProgress.Matching(
                        playlistName = name,
                        processed = processed,
                        total = total,
                        matched = matched,
                    ),
                )
                processed++

                val key = track.searchKey()
                if (key.isBlank()) {
                    unmatched++
                    continue
                }
                if (resolved.containsKey(key)) {
                    val cached = resolved[key]
                    if (cached == null) {
                        unmatched++
                    } else {
                        videoIds.add(cached)
                        matched++
                    }
                    continue
                }

                delay(SEARCH_INTERVAL_MS + Random.nextLong(SEARCH_JITTER_MS))
                val results =
                    youTube.searchSongsSignedOut(key).getOrElse { throwable ->
                        Logger.w(TAG, "search failed for '$key': ${throwable.message}")
                        null
                    }
                if (results == null) {
                    // A refused search is not the same as a track that is not there. Enough in a
                    // row means YouTube is turning us away, and continuing would write a playlist
                    // that looks imported and contains nothing.
                    consecutiveFailures++
                    if (consecutiveFailures >= SEARCH_FAILURE_LIMIT) {
                        emit(
                            SpotifyImportProgress.Error(
                                "YouTube stopped answering searches. Nothing was saved — try again later.",
                            ),
                        )
                        return@flow
                    }
                    unmatched++
                    continue
                }
                consecutiveFailures = 0

                val best = results.bestMatchFor(track)
                resolved[key] = best?.id
                if (best == null) {
                    unmatched++
                } else {
                    matched++
                    videoIds.add(best.id)
                    songs.add(best.toSongEntity())
                }
            }

            if (videoIds.isEmpty()) {
                emit(
                    SpotifyImportProgress.Error(
                        "None of the $total tracks could be found on YouTube Music, so no playlist was created.",
                    ),
                )
                return@flow
            }

            // Songs must exist before the join rows reference them, or the foreign key on
            // pair_song_local_playlist rejects the insert.
            songs.chunked(SONG_BATCH_SIZE).forEach { localDataSource.insertSongs(it) }
            localDataSource.insertLocalPlaylistWithTracks(
                localPlaylist =
                    LocalPlaylistEntity(
                        title = uniqueTitle(name),
                        thumbnail = entity.coverArt?.sources?.lastOrNull()?.url,
                        tracks = videoIds,
                    ),
                videoIds = videoIds,
            )

            emit(
                SpotifyImportProgress.Success(
                    SpotifyImportResult(
                        playlistName = name,
                        tracksMatched = matched,
                        unmatched = unmatched,
                        possiblyTruncated = entity.trackList.size >= EMBED_TRACK_CAP,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    /**
     * Appends a counter when the library already holds a playlist by this name.
     *
     * Importing the same link twice is a thing people do, and two playlists called "Road trip" that
     * cannot be told apart is worse than one called "Road trip (2)".
     */
    private suspend fun uniqueTitle(name: String): String {
        val taken =
            localDataSource
                .getAllLocalPlaylists(Int.MAX_VALUE, 0)
                .map { it.title.trim().lowercase() }
                .toSet()
        if (name.trim().lowercase() !in taken) return name
        var suffix = 2
        while ("$name ($suffix)".trim().lowercase() in taken) suffix++
        return "$name ($suffix)"
    }
}

/** `title artist` — the query a person would type, which is what YouTube Music ranks best against. */
private fun SpotifyEmbedResponse.Track.searchKey(): String =
    listOf(title.orEmpty(), subtitle.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()

/**
 * Scores candidates and returns the best, or null when none is convincing.
 *
 * Title overlap is measured against Spotify's tokens rather than the candidate's, so a YouTube
 * result carrying extra words — "(Official Audio)", a remaster tag — is not punished for them,
 * while one missing half the actual title is.
 */
private fun List<SongItem>.bestMatchFor(track: SpotifyEmbedResponse.Track): SongItem? {
    if (isEmpty()) return null
    val wantTitle = tokens(track.title.orEmpty())
    if (wantTitle.isEmpty()) return null
    val wantArtists = tokens(track.subtitle.orEmpty())
    val wantSeconds = track.duration?.let { (it / 1000).toInt() }

    return this
        .map { candidate ->
            val titleScore = overlap(wantTitle, tokens(candidate.title))
            val candidateArtists = candidate.artists.flatMap { tokens(it.name) }.toSet()
            // No artist on either side is a shrug, not a mismatch: score it neutrally rather than
            // letting a missing field veto an otherwise good title and duration match.
            val artistScore = if (wantArtists.isEmpty()) 0.5 else overlap(wantArtists, candidateArtists)
            val candidateSeconds = candidate.duration
            val durationScore =
                if (wantSeconds == null || candidateSeconds == null) {
                    0.5
                } else {
                    when (abs(candidateSeconds - wantSeconds)) {
                        in 0..3 -> 1.0
                        in 4..10 -> 0.6
                        in 11..30 -> 0.2
                        else -> 0.0
                    }
                }
            candidate to (0.50 * titleScore + 0.35 * artistScore + 0.15 * durationScore)
        }.maxByOrNull { it.second }
        ?.takeIf { it.second >= MATCH_THRESHOLD }
        ?.first
}

private fun overlap(
    want: Set<String>,
    got: Set<String>,
): Double = if (want.isEmpty()) 0.0 else want.count { it in got }.toDouble() / want.size

/**
 * Lowercased word tokens with punctuation dropped.
 *
 * Built by hand rather than with a Unicode-property regex, whose support differs between the
 * Kotlin JVM and Native engines this module compiles for.
 */
private fun tokens(raw: String): Set<String> =
    raw
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
        .toSet()

private fun SongItem.toSongEntity(): SongEntity =
    SongEntity(
        videoId = id,
        albumId = album?.id,
        albumName = album?.name,
        // The artistId column is positional against artistName, so a partial set of ids would
        // misalign the two lists. All or nothing.
        artistId = artists.map { it.id.orEmpty() }.takeIf { ids -> ids.none(String::isEmpty) },
        artistName = artists.map { it.name },
        duration = duration?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "",
        durationSeconds = duration ?: 0,
        isAvailable = true,
        isExplicit = explicit,
        likeStatus = "",
        thumbnails = thumbnail,
        title = title,
        videoType = MusicVideoType.normalize(musicVideoType) ?: "",
        category = null,
        resultType = null,
    )
