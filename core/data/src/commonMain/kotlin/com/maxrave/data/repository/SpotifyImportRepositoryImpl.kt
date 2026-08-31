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
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.spotify.model.response.spotify.LIKED_SONGS_URI
import com.maxrave.spotify.model.response.spotify.SpotifyLibraryItem
import com.maxrave.spotify.model.response.spotify.SpotifyPlaylistSlice
import com.maxrave.spotify.model.response.spotify.SpotifyTrackInfo
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

/** Page size for the internal API, matching what the web player asks for. */
private const val PATHFINDER_PAGE = 100

/** Library entries per request. */
private const val LIBRARY_PAGE = 50

/** A courtesy gap between pages; a burst is the shape that earns a 429. */
private const val PATHFINDER_PAGE_INTERVAL_MS = 200L

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
    private val dataStoreManager: DataStoreManager,
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

            // Signed into Spotify: read through the endpoint the web player itself uses. It pages,
            // and it can see playlists the user has not made public, so neither of the embed
            // limits applies. Signed out: fall back to the public embed, which still handles any
            // shared link and asks nothing of the user.
            val slice =
                runCatching { readPlaylist(playlistId) }
                    .getOrElse { throwable ->
                        Logger.e(TAG, "playlist read failed: ${throwable.message}")
                        emit(SpotifyImportProgress.Error(throwable.message ?: "Couldn't read that playlist"))
                        return@flow
                    }

            val name = slice.name ?: "Spotify playlist"
            val tracks = slice.tracks
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
                        thumbnail = slice.coverArt,
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
                        // Only the embed path can truncate. It states no total, so a
                        // full page is indistinguishable from a longer playlist.
                        possiblyTruncated = slice.total == null && tracks.size >= EMBED_TRACK_CAP,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    override fun syncEverything(): Flow<SpotifyImportProgress> =
        flow {
            emit(SpotifyImportProgress.Preparing)

            val tokens =
                spotifyTokens() ?: run {
                    emit(SpotifyImportProgress.Error("Sign in to Spotify in Settings first."))
                    return@flow
                }

            val library =
                runCatching { readLibrary(tokens) }
                    .getOrElse { throwable ->
                        Logger.e(TAG, "library read failed: ${throwable.message}")
                        emit(SpotifyImportProgress.Error(throwable.message ?: "Couldn't read your Spotify library"))
                        return@flow
                    }
            if (library.isEmpty()) {
                emit(SpotifyImportProgress.Error("No playlists found on this Spotify account."))
                return@flow
            }

            var created = 0
            var updated = 0
            var matched = 0
            var unmatched = 0
            var alreadyPresent = 0
            // Shared across playlists: the same song sitting in five of them costs one search.
            val resolved = mutableMapOf<String, String?>()

            library.forEachIndexed { index, item ->
                val slice =
                    runCatching { readPlaylistByUri(item.uri, tokens) }
                        .getOrElse { throwable ->
                            // One unreadable playlist should not abandon the rest of the library.
                            Logger.w(TAG, "skipping ${item.name}: ${throwable.message}")
                            null
                        } ?: return@forEachIndexed

                val existing = localDataSource.getLocalPlaylistBySpotifyUri(item.uri)
                val existingIds = existing?.tracks.orEmpty()
                // What the local playlist already holds, indexed the same way tracks are matched.
                // This is what lets an unchanged playlist cost zero YouTube searches: a Spotify
                // track that is already here is recognised without ever being looked up.
                val presentKeys =
                    localDataSource
                        .getSongByListVideoIdFull(existingIds)
                        .map { song ->
                            tokens(song.title) to song.artistName.orEmpty().flatMap { tokens(it) }.toSet()
                        }

                val newVideoIds = mutableListOf<String>()
                val newSongs = mutableListOf<SongEntity>()
                var processed = 0
                var consecutiveFailures = 0

                for (track in slice.tracks) {
                    emit(
                        SpotifyImportProgress.Matching(
                            playlistName = item.name,
                            processed = processed,
                            total = slice.tracks.size,
                            matched = matched,
                            playlistIndex = index + 1,
                            playlistCount = library.size,
                        ),
                    )
                    processed++

                    if (track.isAlreadyIn(presentKeys)) {
                        alreadyPresent++
                        continue
                    }

                    val key = track.searchKey()
                    if (key.isBlank()) {
                        unmatched++
                        continue
                    }
                    val cached = if (resolved.containsKey(key)) resolved[key] else null
                    if (resolved.containsKey(key)) {
                        if (cached == null) unmatched++ else if (cached !in existingIds && cached !in newVideoIds) {
                            newVideoIds.add(cached)
                            matched++
                        } else {
                            alreadyPresent++
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
                        consecutiveFailures++
                        if (consecutiveFailures >= SEARCH_FAILURE_LIMIT) {
                            emit(
                                SpotifyImportProgress.Error(
                                    "YouTube stopped answering searches. Playlists finished so far were kept — try again later.",
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
                    when {
                        best == null -> unmatched++
                        best.id in existingIds || best.id in newVideoIds -> alreadyPresent++
                        else -> {
                            matched++
                            newVideoIds.add(best.id)
                            newSongs.add(best.toSongEntity())
                        }
                    }
                }

                if (newVideoIds.isEmpty()) return@forEachIndexed
                newSongs.chunked(SONG_BATCH_SIZE).forEach { localDataSource.insertSongs(it) }

                if (existing == null) {
                    localDataSource.insertLocalPlaylistWithTracks(
                        localPlaylist =
                            LocalPlaylistEntity(
                                title = uniqueTitle(item.name),
                                thumbnail = item.coverArt,
                                spotifyPlaylistUri = item.uri,
                                tracks = newVideoIds,
                            ),
                        videoIds = newVideoIds,
                    )
                    created++
                } else {
                    // Appended, never rewritten: anything the user added or removed locally stays
                    // exactly as they left it. A sync that silently reordered or dropped their
                    // edits would be worse than no sync.
                    val startPosition = existingIds.size
                    newVideoIds.forEachIndexed { offset, videoId ->
                        localDataSource.insertPairSongLocalPlaylist(
                            PairSongLocalPlaylist(
                                playlistId = existing.id,
                                songId = videoId,
                                position = startPosition + offset,
                            ),
                        )
                    }
                    localDataSource.updateLocalPlaylistTracks(existingIds + newVideoIds, existing.id)
                    updated++
                }
            }

            emit(
                SpotifyImportProgress.Success(
                    SpotifyImportResult(
                        playlistName = "",
                        tracksMatched = matched,
                        unmatched = unmatched,
                        possiblyTruncated = false,
                        playlistsCreated = created,
                        playlistsUpdated = updated,
                        alreadyPresent = alreadyPresent,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    /** Liked Songs first, then the account's playlists, paged. */
    private suspend fun readLibrary(tokens: Pair<String, String>): List<SpotifyLibraryItem> {
        val items = mutableListOf<SpotifyLibraryItem>()
        items +=
            SpotifyLibraryItem(
                uri = LIKED_SONGS_URI,
                name = "Liked Songs",
                coverArt = null,
                isLikedSongs = true,
            )
        var offset = 0
        while (true) {
            val page =
                spotify
                    .getLibraryPlaylists(offset, LIBRARY_PAGE, tokens.first, tokens.second)
                    .getOrThrow()
            items += page
            offset += LIBRARY_PAGE
            if (page.size < LIBRARY_PAGE) break
            delay(PATHFINDER_PAGE_INTERVAL_MS)
        }
        return items.distinctBy { it.uri }
    }

    /**
     * Reads the whole playlist, preferring the route that can see all of it.
     *
     * Paging stops on a short page as well as on the stated total, rather than trusting either
     * alone: a short page is the only reliable end marker, and the total is not always present.
     */
    private suspend fun readPlaylist(playlistId: String): SpotifyPlaylistSlice {
        val tokens = spotifyTokens() ?: return spotify.getPlaylistFromEmbed(playlistId).getOrThrow()
        return runCatching { readPlaylistByUri("spotify:playlist:" + playlistId, tokens) }
            .getOrElse { spotify.getPlaylistFromEmbed(playlistId).getOrThrow() }
    }

    private suspend fun readPlaylistByUri(
        uri: String,
        tokens: Pair<String, String>,
    ): SpotifyPlaylistSlice {
        val collected = mutableListOf<SpotifyTrackInfo>()
        var name: String? = null
        var total: Int? = null
        var offset = 0
        while (true) {
            val page =
                spotify
                    .getPlaylistTracksViaPathfinder(uri, offset, PATHFINDER_PAGE, tokens.first, tokens.second)
                    .getOrElse { throwable ->
                        // These query hashes are pinned to a web-player release and will eventually
                        // stop matching. Falling back beats failing outright; the cost is the embed
                        // page limits, and only for a playlist that has a public link.
                        Logger.w(TAG, "pathfinder read failed: " + throwable.message)
                        if (collected.isEmpty()) throw throwable
                        return SpotifyPlaylistSlice(name, null, total, collected)
                    }
            if (name == null) name = page.name
            if (total == null) total = page.total
            collected += page.tracks
            offset += PATHFINDER_PAGE
            if (page.tracks.size < PATHFINDER_PAGE) break
            val known = total
            if (known != null && collected.size >= known) break
            delay(PATHFINDER_PAGE_INTERVAL_MS)
        }
        return SpotifyPlaylistSlice(name, null, total ?: collected.size, collected)
    }

    /** Access and client token pair for the internal Spotify API, or null when signed out. */
    @OptIn(ExperimentalTime::class)
    private suspend fun spotifyTokens(): Pair<String, String>? {
        val spDc = dataStoreManager.spdc.first()
        if (spDc.isEmpty()) return null
        val now = Clock.System.now().toEpochMilliseconds()

        val storedAccess = dataStoreManager.spotifyPersonalToken.first()
        val access =
            if (storedAccess.isNotEmpty() && dataStoreManager.spotifyPersonalTokenExpires.first() > now) {
                storedAccess
            } else {
                spotify
                    .getPersonalTokenWithTotp(spDc)
                    .onSuccess {
                        dataStoreManager.setSpotifyPersonalToken(it.accessToken)
                        dataStoreManager.setSpotifyPersonalTokenExpires(it.accessTokenExpirationTimestampMs)
                    }.getOrNull()
                    ?.accessToken
            } ?: return null

        val storedClient = dataStoreManager.spotifyClientToken.first()
        val client =
            if (storedClient.isNotEmpty() && dataStoreManager.spotifyClientTokenExpires.first() > now) {
                storedClient
            } else {
                spotify
                    .getClientToken()
                    .onSuccess {
                        dataStoreManager.setSpotifyClientToken(it.grantedToken.token)
                        dataStoreManager.setSpotifyClientTokenExpires(
                            (it.grantedToken.expiresAfterSeconds * 1000L) + now,
                        )
                    }.getOrNull()
                    ?.grantedToken
                    ?.token
            } ?: return null

        return access to client
    }

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

/**
 * Whether a locally-held song already covers this Spotify track.
 *
 * Compared on the same tokens the matcher scores with, so "already synced" means the same thing
 * here as "matched" does there. This is what makes re-syncing an unchanged playlist free: the track
 * is recognised from what is already in the database, without spending a YouTube search to learn
 * its video id.
 */
private fun SpotifyTrackInfo.isAlreadyIn(present: List<Pair<Set<String>, Set<String>>>): Boolean {
    val wantTitle = tokens(title)
    if (wantTitle.isEmpty()) return false
    val wantArtists = tokens(artist)
    return present.any { (haveTitle, haveArtists) ->
        overlap(wantTitle, haveTitle) >= 0.85 &&
            (wantArtists.isEmpty() || overlap(wantArtists, haveArtists) >= 0.5)
    }
}

/** `title artist` — the query a person would type, which is what YouTube Music ranks best against. */
private fun SpotifyTrackInfo.searchKey(): String =
    listOf(title, artist)
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
private fun List<SongItem>.bestMatchFor(track: SpotifyTrackInfo): SongItem? {
    if (isEmpty()) return null
    val wantTitle = tokens(track.title)
    if (wantTitle.isEmpty()) return null
    val wantArtists = tokens(track.artist)
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
