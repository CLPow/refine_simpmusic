@file:OptIn(ExperimentalTime::class)

package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toLyrics
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.metadata.SimpMusicLyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.simpmusic.lyrics.SimpMusicLyricsClient
import org.simpmusic.lyrics.am.toImageUrl
import org.simpmusic.lyrics.parser.parseTtmlLyrics
import kotlin.time.ExperimentalTime

internal class LyricsCanvasRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val simpMusicLyrics: SimpMusicLyricsClient,
) : LyricsCanvasRepository {
    override fun getSavedLyrics(videoId: String): Flow<LyricsEntity?> = flow { emit(localDataSource.getSavedLyrics(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun insertLyrics(lyricsEntity: LyricsEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertLyrics(lyricsEntity)
        }

    override fun getYouTubeMusicLyrics(videoId: String): Flow<Resource<Pair<Lyrics, String?>>> =
        flow {
            val result = youTube.youTubeMusicLyrics(videoId)
            val found = result.getOrNull()
            val lyrics =
                found?.lines?.let { lines ->
                    Lyrics(
                        lines =
                            lines
                                // YouTube Music marks instrumental stretches with a lone "\u266a"
                                // line and shows them as a gap, not as a lyric.
                                .filter { it.text.isNotBlank() && it.text.trim() != "\u266a" }
                                .map { Line(startTimeMs = it.startMs.toString(), endTimeMs = it.endMs.toString(), words = it.text) },
                        syncType = "LINE_SYNCED",
                    )
                } ?: found?.plain?.let { text ->
                    Lyrics(
                        lines = text.lines().map { Line(startTimeMs = "0", endTimeMs = "0", words = it) },
                        syncType = "UNSYNCED",
                    )
                }
            if (lyrics == null || lyrics.lines.isNullOrEmpty()) {
                emit(Resource.Error<Pair<Lyrics, String?>>(result.exceptionOrNull()?.message ?: "No YouTube Music lyrics"))
            } else {
                emit(Resource.Success<Pair<Lyrics, String?>>(lyrics to found?.source))
            }
        }.flowOn(Dispatchers.IO)

    override fun getYouTubeCaption(videoId: String): Flow<Resource<Lyrics>> =
        flow {
            runCatching {
                youTube
                    .getYouTubeCaption(videoId)
                    .onSuccess { lyrics ->
                        emit(Resource.Success<Lyrics>(lyrics.toLyrics()))
                    }.onFailure { e ->
                        Logger.d("Lyrics", "Error: ${e.message}")
                        emit(Resource.Error<Lyrics>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun purgeLyricsCacheIfStale(dataStoreManager: DataStoreManager) {
        val stored = dataStoreManager.lyricsCacheGeneration.first()
        if (stored >= DataStoreManager.LYRICS_CACHE_GENERATION) return
        val removed = localDataSource.deleteAllLyrics()
        dataStoreManager.setLyricsCacheGeneration(DataStoreManager.LYRICS_CACHE_GENERATION)
        Logger.i("Lyrics", "Cleared $removed cached lyrics rows written by an older matcher")
    }

    override fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getLrclibLyricsData: $sartist $strack $duration")
            val qartist =
                sartist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                strack
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchLrclibLyrics(
                    q_track = qtrack,
                    q_artist = qartist,
                    duration = duration,
                    // The loosened strings above are for finding candidates; the untouched ones
                    // are for judging them. Matching on the stripped text is what let a cover
                    // through: with the brackets gone, "Song (Live)" and "Song" read the same.
                    trackName = strack,
                    artistName = sartist,
                ).onSuccess { matched ->
                    // No match is a result, and it has to be emitted: the caller advances to the
                    // next source when it sees an error, and a flow that ends without emitting
                    // anything leaves the lyrics chain waiting for a value that never comes.
                    if (matched != null) {
                        emit(Resource.Success<Lyrics>(matched.toLyrics()))
                    } else {
                        emit(Resource.Error<Lyrics>("No LRCLIB result matched this recording"))
                    }
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("Not found"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getBetterLyrics: $artist $track")
            val qartist =
                artist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                track
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchBetterLyrics(qtrack, qartist, duration)
                .onSuccess { ttml ->
                    if (ttml.isNullOrEmpty()) {
                        emit(Resource.Error<Lyrics>("No BetterLyrics found"))
                        return@onSuccess
                    }
                    val lyrics = parseTtmlLyrics(ttml).toLyrics()
                    emit(Resource.Success(lyrics))
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("BetterLyrics search failed"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getArtistLogo(artistName: String): Flow<Resource<ArtistLogo>> =
        flow {
            simpMusicLyrics
                .searchAMArtist(artistName, limit = 1)
                .onSuccess { artists ->
                    val id = artists.firstOrNull()?.id
                    if (id == null) {
                        emit(Resource.Error<ArtistLogo>("Artist not found"))
                        return@onSuccess
                    }
                    simpMusicLyrics
                        .getAMArtist(id)
                        .onSuccess { artist ->
                            val logo = artist?.attributes?.editorialArtwork?.musicContentColorLogoTrimmed
                            val srcW = logo?.width ?: 0
                            val srcH = logo?.height ?: 0
                            // Scale down to ~1000px wide, keeping the logo aspect ratio.
                            val targetW = 1000
                            val targetH = if (srcW > 0) (srcH.toLong() * targetW / srcW).toInt() else srcH
                            val url = logo?.toImageUrl(targetW, targetH)
                            if (logo == null || url == null) {
                                emit(Resource.Error<ArtistLogo>("No artist logo"))
                                return@onSuccess
                            }
                            emit(
                                Resource.Success(
                                    ArtistLogo(
                                        logoUrl = url,
                                        bgColorHex = logo.bgColor,
                                        width = srcW,
                                        height = srcH,
                                    ),
                                ),
                            )
                        }.onFailure {
                            emit(Resource.Error<ArtistLogo>(it.message ?: "Failed to fetch artist"))
                        }
                }.onFailure {
                    emit(Resource.Error<ArtistLogo>(it.message ?: "Artist search failed"))
                }
        }.flowOn(Dispatchers.IO)

    // SimpMusic Lyrics
    private val simpMusicLyricsTag = "SimpMusicLyricsRepository"

    override fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getLyrics(videoId)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag, "Lyrics found: $lyrics")
                    val result = lyrics.firstOrNull()
                    if (result == null) {
                        Logger.w(simpMusicLyricsTag, "No lyrics found for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("No lyrics found"))
                        return@onSuccess
                    }
                    val appLyrics =
                        result.toLyrics()?.copy(
                            simpMusicLyrics =
                                SimpMusicLyrics(
                                    id = result.id,
                                    vote = result.vote,
                                ),
                        )
                    if (appLyrics == null) {
                        Logger.w(simpMusicLyricsTag, "Failed to convert lyrics for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("Failed to convert lyrics"))
                        return@onSuccess
                    }
                    emit(
                        Resource.Success<Lyrics>(
                            appLyrics,
                        ),
                    )
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Get Lyrics Error: ${it.message}")
                    emit(Resource.Error<Lyrics>(it.message ?: "Failed to get lyrics"))
                }
        }.flowOn(Dispatchers.IO)
}
