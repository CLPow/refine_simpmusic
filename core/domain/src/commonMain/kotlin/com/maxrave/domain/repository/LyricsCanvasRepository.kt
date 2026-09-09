package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
import com.maxrave.domain.data.model.canvas.CanvasResult
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface LyricsCanvasRepository {
    fun getSavedLyrics(videoId: String): Flow<LyricsEntity?>

    /**
     * Empties the lyrics cache once, when it was filled by a matcher since found to be wrong.
     *
     * Safe to call from anywhere and as often as you like; it does nothing after the first time.
     * Both entry points call it, because either may be the first to run — the phone's UI, or the
     * car, when someone gets in and presses play without ever opening the app.
     */
    suspend fun purgeLyricsCacheIfStale(dataStoreManager: DataStoreManager)

    suspend fun insertLyrics(lyricsEntity: LyricsEntity)

    suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity)

    fun getSavedTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<TranslatedLyricsEntity?>

    suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    )

    fun getYouTubeCaption(
        preferLang: String,
        videoId: String,
    ): Flow<Resource<Pair<Lyrics, Lyrics?>>>

    fun getCanvas(
        dataStoreManager: DataStoreManager,
        videoId: String,
        duration: Int,
    ): Flow<Resource<CanvasResult>>

    suspend fun updateCanvasUrl(
        videoId: String,
        canvasUrl: String,
    )

    suspend fun updateCanvasThumbUrl(
        videoId: String,
        canvasThumbUrl: String,
    )

    /**
     * Spotify's timed lyrics for this exact recording.
     *
     * Title and artist stay separate rather than arriving pre-joined into one search string: the
     * search needs them fused, but deciding whether a result is the right recording needs them
     * apart, and a joined string cannot be reliably taken back apart.
     */
    fun getSpotifyLyrics(
        dataStoreManager: DataStoreManager,
        title: String,
        artist: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

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

    /** Fetch the artist's name-logo image + dominant color from the hidden catalog. */
    fun getArtistLogo(artistName: String): Flow<Resource<ArtistLogo>>

    fun getAITranslationLyrics(
        lyrics: Lyrics,
        targetLanguage: String,
    ): Flow<Resource<Lyrics>>

    fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>>

    fun getSimpMusicTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<Resource<Lyrics>>

    fun voteSimpMusicLyrics(
        lyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>>

    fun voteSimpMusicTranslatedLyrics(
        translatedLyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>>

    fun insertSimpMusicLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        duration: Int,
        lyrics: Lyrics,
    ): Flow<Resource<String>>

    fun insertSimpMusicTranslatedLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        translatedLyrics: Lyrics,
        language: String,
    ): Flow<Resource<String>>
}