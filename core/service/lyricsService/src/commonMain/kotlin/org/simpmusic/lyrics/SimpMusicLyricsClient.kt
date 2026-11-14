package org.simpmusic.lyrics

import com.maxrave.domain.lyrics.TrackMatch
import com.maxrave.logger.Logger
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import org.simpmusic.lyrics.am.AMArtistResource
import org.simpmusic.lyrics.am.AMSearchResponse
import org.simpmusic.lyrics.models.response.BaseResponse
import org.simpmusic.lyrics.models.response.BetterLyricsResponse
import org.simpmusic.lyrics.models.response.LrclibObject
import org.simpmusic.lyrics.models.response.LyricsResponse
import org.simpmusic.lyrics.parser.parseSyncedLyrics
import org.simpmusic.lyrics.parser.parseUnsyncedLyrics

private const val TAG = "SimpMusicLyricsClient"

class SimpMusicLyricsClient {
    private val lyricsService = SimpMusicLyrics()
    private var tooManyRequest: Boolean = false

    suspend fun getLyrics(videoId: String): Result<List<LyricsResponse>> =
        runCatching {
            lyricsService.findLyricsByVideoId(videoId).bodyOrThrow<List<LyricsResponse>>()
        }

    /**
     * @param q_track loosened text for the search itself; LRCLIB matches it fuzzily on purpose.
     * @param q_artist the same, for the artist.
     * @param trackName the track's real title, used to judge the results rather than to find them.
     * @param artistName the track's real artist credit, likewise.
     *
     * The search deliberately casts a wide net -- brackets, features and punctuation stripped --
     * so it reaches entries whose metadata is written differently. That is also why the results
     * cannot be trusted in the order they arrive: a search for a famous song returns its live
     * recording, several covers and a karaoke track, all of them plausible on title and artist.
     * [TrackMatch] decides which of them is this exact recording, from the untouched metadata.
     */
    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
        duration: Int?,
        trackName: String,
        artistName: String,
    ) = runCatching {
        val rs =
            lyricsService
                .searchLrclibLyrics(
                    q_track = q_track,
                    q_artist = q_artist,
                ).body<List<LrclibObject>>()
        val lrclibObject: LrclibObject? =
            TrackMatch.best(
                title = trackName,
                artist = artistName,
                durationSeconds = duration,
                candidates =
                    rs.map { entry ->
                        TrackMatch.Candidate(
                            title = entry.trackName ?: entry.name,
                            artist = entry.artistName,
                            durationSeconds = entry.duration.toInt(),
                            value = entry,
                            isSynced = !entry.syncedLyrics.isNullOrEmpty(),
                            isInstrumental = entry.instrumental,
                        )
                    },
            )
        if (lrclibObject != null) {
            val syncedLyrics = lrclibObject.syncedLyrics
            val plainLyrics = lrclibObject.plainLyrics
            if (!syncedLyrics.isNullOrEmpty()) {
                parseSyncedLyrics(syncedLyrics)
            } else if (!plainLyrics.isNullOrEmpty()) {
                parseUnsyncedLyrics(plainLyrics)
            } else {
                null
            }
        } else {
            null
        }
    }

    suspend fun searchBetterLyrics(
        q_track: String,
        q_artist: String,
        durationSeconds: Int?,
    ) = runCatching {
        val rs =
            lyricsService
                .searchBetterLyrics(
                    q_track = q_track,
                    q_artist = q_artist,
                    durationSeconds = durationSeconds,
                ).body<BetterLyricsResponse>()
        rs.ttml
    }

    suspend fun searchAMArtist(
        name: String,
        limit: Int = 5,
    ): Result<List<AMArtistResource>> =
        runCatching {
            val response = lyricsService.searchAMArtist(name, limit)
            if (response.status.value !in 200..299) {
                throw Exception("AM search failed: ${response.status.value}")
            }
            val parsed = response.body<AMSearchResponse>()
            val resources = parsed.resources?.artists.orEmpty()
            // Keep the search ranking from results.data; fall back to the resources map order.
            parsed.results
                ?.artists
                ?.data
                ?.mapNotNull { resources[it.id] }
                ?: resources.values.toList()
        }

    /**
     * Fetch a single artist by id, including [AMEditorialArtwork] (name-logo PNG) and keyColor.
     * Returns null when the id is not present in the response.
     */
    suspend fun getAMArtist(id: String): Result<AMArtistResource?> =
        runCatching {
            val response = lyricsService.getAMArtist(id)
            if (response.status.value !in 200..299) {
                throw Exception("AM artist fetch failed: ${response.status.value}")
            }
            response
                .body<AMSearchResponse>()
                .resources
                ?.artists
                ?.get(id)
        }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (this.status.value == 429) {
            tooManyRequest = true
            Logger.e(TAG, "Too many requests: ${this.status.value}")
        } else {
            tooManyRequest = false
        }
        try {
            val data = body<BaseResponse<T>>()
            if (data.error != null) {
                val error = data.error
                Logger.e(TAG, "Error response: ${error.reason} (code: ${error.code})")
                throw Exception("Error response: ${error.reason} (code: ${error.code})")
            }
            return data.data ?: throw Exception("Response data is null")
        } catch (e: Exception) {
            throw e
        }
    }
}