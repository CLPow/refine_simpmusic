package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface LyricsCanvasRepository {
    fun getSavedLyrics(videoId: String): Flow<LyricsEntity?>

    suspend fun purgeLyricsCacheIfStale(dataStoreManager: DataStoreManager)

    suspend fun insertLyrics(lyricsEntity: LyricsEntity)

    fun getYouTubeMusicLyrics(videoId: String): Flow<Resource<Pair<Lyrics, String?>>>

    fun getYouTubeCaption(videoId: String): Flow<Resource<Lyrics>>

    fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    fun getArtistLogo(artistName: String): Flow<Resource<ArtistLogo>>

    fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>>
}