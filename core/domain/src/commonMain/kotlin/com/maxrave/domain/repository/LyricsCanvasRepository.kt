package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
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

    /**
     * YouTube Music's own lyrics for [videoId] with the "Source: …" line YouTube shows under them.
     * LINE_SYNCED when YouTube has timing for this recording, UNSYNCED when it only has the text.
     */
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

    /** Fetch the artist's name-logo image + dominant color from the hidden catalog. */
    fun getArtistLogo(artistName: String): Flow<Resource<ArtistLogo>>

    fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>>
}