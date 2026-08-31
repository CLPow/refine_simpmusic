package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.domain.repository.SpotifyImportResult
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.model.response.spotify.SpotifyTrack
import com.maxrave.spotify.model.response.spotify.SpotifyTrackEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "SpotifyImport"

/** Spotify's paging maximums, so the library is enumerated in as few requests as possible. */
private const val PLAYLIST_PAGE = 50
private const val PLAYLIST_TRACK_PAGE = 100
private const val SAVED_TRACK_PAGE = 50

/**
 * Gap between YouTube searches.
 *
 * Importing a whole account means thousands of queries, and firing them as fast as the network
 * allows is what gets an address rate-limited part-way through, leaving half-matched playlists
 * behind. The jitter keeps the traffic from looking metronomic.
 */
private const val SEARCH_INTERVAL_MS = 1_000L
private const val SEARCH_JITTER_MS = 400L

/**
 * Gap between Spotify paging requests.
 *
 * Enumerating a large account is dozens of back-to-back calls before any track work begins. Spotify
 * is far more permissive than this, but a burst is the shape that earns a 429, and the delay is
 * invisible next to the matching that follows.
 */
private const val SPOTIFY_PAGE_INTERVAL_MS = 150L

/** Below this, the best candidate is treated as "not on YouTube" rather than forced into a match. */
private const val MATCH_THRESHOLD = 0.55

/** Tracks written per transaction, mirroring the file importer's batching. */
private const val SONG_BATCH_SIZE = 500

internal class SpotifyImportRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val dataStoreManager: DataStoreManager,
    private val spotify: Spotify,
    private val youTube: YouTube,
) : SpotifyImportRepository {
    override fun importEverything(): Flow<SpotifyImportProgress> =
        flow {
            emit(SpotifyImportProgress.Preparing)

            val token =
                accessToken() ?: run {
                    emit(SpotifyImportProgress.Error("Sign in to Spotify first"))
                    return@flow
                }

            // Enumerate everything before matching anything: the track total is what makes progress
            // meaningful, and an import this long is unusable without one.
            val sources =
                runCatching { enumerate(token) }
                    .getOrElse { throwable ->
                        Logger.e(TAG, "enumerate failed: ${throwable.message}")
                        emit(
                            SpotifyImportProgress.Error(
                                throwable.message ?: "Couldn't read your Spotify library",
                            ),
                        )
                        return@flow
                    }

            if (sources.isEmpty()) {
                emit(SpotifyImportProgress.Error("No playlists found on this Spotify account"))
                return@flow
            }

            // Titles already in the library are skipped rather than duplicated. Re-running the
            // import is the obvious response to it being interrupted, and it should not leave two
            // of every playlist behind.
            val existingTitles =
                localDataSource
                    .getAllLocalPlaylists(Int.MAX_VALUE, 0)
                    .map { it.title.trim().lowercase() }
                    .toSet()

            val total = sources.sumOf { it.tracks.size }
            // One search per distinct track rather than per playlist entry: a song sitting in five
            // playlists is five entries but one query.
            val resolved = mutableMapOf<String, String?>()
            var processed = 0
            var matched = 0
            var unmatched = 0
            var created = 0
            var skipped = 0

            sources.forEachIndexed { index, source ->
                if (source.name.trim().lowercase() in existingTitles) {
                    skipped++
                    processed += source.tracks.size
                    return@forEachIndexed
                }

                val videoIds = mutableListOf<String>()
                val songs = mutableListOf<SongEntity>()

                for (track in source.tracks) {
                    emit(
                        SpotifyImportProgress.Matching(
                            playlistName = source.name,
                            playlistIndex = index + 1,
                            playlistCount = sources.size,
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
                        if (cached == null) unmatched++ else { videoIds.add(cached); matched++ }
                        continue
                    }

                    delay(SEARCH_INTERVAL_MS + Random.nextLong(SEARCH_JITTER_MS))
                    val best =
                        youTube
                            .searchSongsSignedOut(key)
                            .getOrElse { throwable ->
                                Logger.w(TAG, "search failed for \"$key\": ${throwable.message}")
                                emptyList()
                            }.bestMatchFor(track)

                    resolved[key] = best?.id
                    if (best == null) {
                        unmatched++
                    } else {
                        matched++
                        videoIds.add(best.id)
                        songs.add(best.toSongEntity())
                    }
                }

                // Nothing resolved: writing an empty playlist would just be litter.
                if (videoIds.isEmpty()) return@forEachIndexed

                // Songs must exist before the join rows reference them, or the foreign key on
                // pair_song_local_playlist rejects the insert.
                songs.chunked(SONG_BATCH_SIZE).forEach { localDataSource.insertSongs(it) }
                val id =
                    localDataSource.insertLocalPlaylistWithTracks(
                        localPlaylist =
                            LocalPlaylistEntity(
                                title = source.name,
                                thumbnail = source.thumbnail,
                                tracks = videoIds,
                            ),
                        videoIds = videoIds,
                    )
                if (id != -1L) created++
            }

            emit(
                SpotifyImportProgress.Success(
                    SpotifyImportResult(
                        playlistsCreated = created,
                        playlistsSkipped = skipped,
                        tracksMatched = matched,
                        unmatched = unmatched,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    /** A playlist's worth of Spotify tracks, already paged out. */
    private data class Source(
        val name: String,
        val thumbnail: String?,
        val tracks: List<SpotifyTrack>,
    )

    /**
     * Reuses the stored web-player token while it is still valid and only mints a new one when it
     * is not, matching how lyrics and canvas already treat it.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun accessToken(): String? {
        val stored = dataStoreManager.spotifyPersonalToken.first()
        val expires = dataStoreManager.spotifyPersonalTokenExpires.first()
        if (stored.isNotEmpty() && expires > Clock.System.now().toEpochMilliseconds()) return stored

        val spDc = dataStoreManager.spdc.first()
        if (spDc.isEmpty()) return null
        return spotify
            .getPersonalTokenWithTotp(spDc)
            .onSuccess {
                dataStoreManager.setSpotifyPersonalToken(it.accessToken)
                dataStoreManager.setSpotifyPersonalTokenExpires(it.accessTokenExpirationTimestampMs)
            }.getOrNull()
            ?.accessToken
    }

    /**
     * Liked Songs first, then every playlist on the account, each with its tracks paged out.
     *
     * Liked Songs is not a playlist and never appears in `/v1/me/playlists`, so importing
     * "everything" has to ask for it separately or silently miss the one list most people care
     * about most.
     */
    private suspend fun enumerate(token: String): List<Source> {
        val sources = mutableListOf<Source>()

        val liked =
            pageAll(SAVED_TRACK_PAGE) { offset ->
                spotify.getSavedTracks(token, offset).getOrThrow().let { it.items to it.total }
            }
        if (liked.isNotEmpty()) sources.add(Source("Liked Songs", null, liked))

        var offset = 0
        while (true) {
            val page = spotify.getCurrentUserPlaylists(token, offset).getOrThrow()
            val items = page.items.filterNotNull()
            for (playlist in items) {
                val id = playlist.id ?: continue
                val name = playlist.name?.takeIf { it.isNotBlank() } ?: continue
                val tracks =
                    pageAll(PLAYLIST_TRACK_PAGE) { o ->
                        spotify.getPlaylistTracks(token, id, o).getOrThrow().let { it.items to it.total }
                    }
                if (tracks.isNotEmpty()) {
                    sources.add(Source(name, playlist.images?.firstOrNull()?.url, tracks))
                }
            }
            offset += PLAYLIST_PAGE
            if (items.isEmpty() || offset >= page.total) break
            delay(SPOTIFY_PAGE_INTERVAL_MS)
        }
        return sources
    }

    /**
     * Walks a Spotify paging object to the end, dropping envelopes with no playable track:
     * removed catalogue entries, podcast episodes, and files local to the user's own machine.
     *
     * [pageSize] has to match the limit the caller asked for, or the offset walks past whole pages.
     */
    private suspend fun pageAll(
        pageSize: Int,
        fetch: suspend (offset: Int) -> Pair<List<SpotifyTrackEnvelope?>, Int>,
    ): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total) = fetch(offset)
            items.forEach { envelope ->
                envelope?.track?.takeIf { !it.isLocal && !it.name.isNullOrBlank() }?.let(out::add)
            }
            offset += pageSize
            if (items.isEmpty() || offset >= total) break
            delay(SPOTIFY_PAGE_INTERVAL_MS)
        }
        return out
    }
}

/** `title artist` — the query a person would type, which is what YouTube Music ranks best against. */
private fun SpotifyTrack.searchKey(): String {
    val artist = artists?.firstOrNull()?.name.orEmpty()
    return listOf(name.orEmpty(), artist).filter { it.isNotBlank() }.joinToString(" ").trim()
}

/**
 * Scores candidates and returns the best, or null when none is convincing.
 *
 * Title overlap is measured against Spotify's tokens rather than the candidate's, so a YouTube
 * result carrying extra words — "(Official Audio)", a remaster tag — is not punished for them,
 * while one missing half the actual title is.
 */
private fun List<SongItem>.bestMatchFor(track: SpotifyTrack): SongItem? {
    if (isEmpty()) return null
    val wantTitle = tokens(track.name.orEmpty())
    if (wantTitle.isEmpty()) return null
    val wantArtists = track.artists.orEmpty().mapNotNull { it.name }.flatMap { tokens(it) }.toSet()
    val wantSeconds = track.durationMs?.let { (it / 1000).toInt() }

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
