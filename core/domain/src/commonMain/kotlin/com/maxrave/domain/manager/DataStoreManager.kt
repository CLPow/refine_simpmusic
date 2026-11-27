package com.maxrave.domain.manager

import kotlinx.coroutines.flow.Flow

interface DataStoreManager {
    val location: Flow<String>

    suspend fun setLocation(location: String)

    val quality: Flow<String>

    suspend fun setQuality(quality: String)

    val downloadQuality: Flow<String>

    suspend fun setDownloadQuality(quality: String)

    val videoDownloadQuality: Flow<String>

    val language: Flow<String>

    val moodArtworkCache: Flow<String?>

    fun getString(key: String): Flow<String?>

    suspend fun putString(
        key: String,
        value: String,
    )

    val loggedIn: Flow<String>
    val cookie: Flow<String>
    val pageId: Flow<String>
    val authUser: Flow<Int>

    suspend fun setCookie(
        cookie: String,
        pageId: String?,
        authUser: Int = 0,
    )

    suspend fun setLoggedIn(logged: Boolean)

    val skipSilent: Flow<String>

    val saveStateOfPlayback: Flow<String>

    val shuffleKey: Flow<String>
    val repeatKey: Flow<String>

    suspend fun recoverShuffleAndRepeatKey(
        shuffle: Boolean,
        repeat: Int,
    )

    val recentMediaId: Flow<String>
    val recentPosition: Flow<String>

    suspend fun saveRecentSong(
        mediaId: String,
        position: Long,
    )

    val playlistFromSaved: Flow<String>

    suspend fun setPlaylistFromSaved(playlist: String)

    val sendBackToGoogle: Flow<String>

    suspend fun setSendBackToGoogle(send: Boolean)

    val sponsorBlockEnabled: Flow<String>

    suspend fun setSponsorBlockEnabled(enabled: Boolean)

    suspend fun getSponsorBlockCategories(): ArrayList<String>

    suspend fun setSponsorBlockCategories(categories: ArrayList<String>)

    val lyricsProvider: Flow<String>

    suspend fun setLyricsProvider(provider: String)

    val lyricsCacheGeneration: Flow<Int>

    suspend fun setLyricsCacheGeneration(generation: Int)

    val skipShortTrackSeconds: Flow<Int>

    val maxSongCacheSize: Flow<Int>

    val playerVolume: Flow<Float>

    suspend fun setPlayerVolume(volume: Float)

    val videoQuality: Flow<String>

    suspend fun setVideoQuality(quality: String)

    val videoQualityMobile: Flow<String>

    suspend fun setVideoQualityMobile(quality: String)

    val syncFollowToYouTube: Flow<String>

    val chartKey: Flow<String>

    val endlessQueue: Flow<String>

    suspend fun setEndlessQueue(endlessQueue: Boolean)

    val qualityMobile: Flow<String>

    suspend fun setQualityMobile(quality: String)

    val streamOverWifiOnly: Flow<Boolean>

    suspend fun setStreamOverWifiOnly(enabled: Boolean)

    val doubleTapSeekSeconds: Flow<Int>

    suspend fun setDoubleTapSeekSeconds(seconds: Int)

    val allowExternalStartPlayback: Flow<Boolean>

    suspend fun setAllowExternalStartPlayback(enabled: Boolean)

    val pinnedLibraryKeys: Flow<Set<String>>

    suspend fun setLibraryPinned(
        key: String,
        pinned: Boolean,
    )

    val keepYouTubePlaylistOffline: Flow<String>

    val combineLocalAndYouTubeLiked: Flow<String>

    val shouldShowLogInRequiredAlert: Flow<String>

    suspend fun setShouldShowLogInRequiredAlert(shouldShow: Boolean)

    val playbackSpeed: Flow<Float>

    fun setPlaybackSpeed(speed: Float)

    val pitch: Flow<Int>

    fun setPitch(pitch: Int)

    val dataSyncId: Flow<String>

    val visitorData: Flow<String>

    val localPlaylistFilter: Flow<String>

    suspend fun setLocalPlaylistFilter(filter: String)

    val killServiceOnExit: Flow<String>

    val autoDownloadLikedSongs: Flow<String>

    val preferredAudioLanguage: Flow<String>

    val explicitContentEnabled: Flow<String>

    suspend fun setExplicitContentEnabled(enabled: Boolean)

    companion object Values {
        const val SIMPMUSIC = "simpmusic"
        const val YOUTUBE = "youtube"
        const val LRCLIB = "lrclib"
        const val BETTER_LYRICS = "better_lyrics"

        const val LYRICS_CACHE_GENERATION = 3

        const val REPEAT_MODE_OFF = "REPEAT_MODE_OFF"
        const val REPEAT_ONE = "REPEAT_ONE"
        const val REPEAT_ALL = "REPEAT_ALL"

        const val TRUE = "TRUE"
        const val FALSE = "FALSE"

        const val LOCAL_PLAYLIST_FILTER_OLDER_FIRST = "older_first"
        const val LOCAL_PLAYLIST_FILTER_NEWER_FIRST = "newer_first"
        const val LOCAL_PLAYLIST_FILTER_TITLE = "title"
        const val LOCAL_PLAYLIST_FILTER_CUSTOM_ORDER = "custom_order"
    }
}