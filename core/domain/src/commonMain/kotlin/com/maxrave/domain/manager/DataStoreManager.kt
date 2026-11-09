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

    /**
     * Cover art resolved per browse category, keyed by its params. The category list itself
     * carries no artwork, so each cover costs one full category browse — worth remembering on
     * disk rather than paying again every time the search screen opens.
     */
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

    /**
     * Which generation of the lyrics matcher filled the local lyrics cache.
     *
     * A cached wrong match cannot be told from a right one — correct title, correct artist,
     * somebody else's words — so fixing the matcher does nothing for a song whose lyrics are
     * already stored. Bumping [Values.LYRICS_CACHE_GENERATION] empties the cache once, so the fix
     * reaches songs that were already wrong instead of only new ones.
     */
    val lyricsCacheGeneration: Flow<Int>

    suspend fun setLyricsCacheGeneration(generation: Int)

    /** Skip radio/queue tracks shorter than this many seconds (0 = off). */
    val skipShortTrackSeconds: Flow<Int>

    val maxSongCacheSize: Flow<Int>

    val playerVolume: Flow<Float>

    suspend fun setPlayerVolume(volume: Float)

    val videoQuality: Flow<String>

    /**
     * Whether following an artist in the app also subscribes to their YouTube channel.
     *
     * Off by default and only settable while signed in: Follow has always been local-only, and
     * writing to someone's account is not something to start doing without being asked.
     */
    val syncFollowToYouTube: Flow<String>

    val chartKey: Flow<String>

    val endlessQueue: Flow<String>

    suspend fun setEndlessQueue(endlessQueue: Boolean)

    /** Streaming quality on a metered (mobile) network; [quality] is the Wi-Fi one. */
    val qualityMobile: Flow<String>

    suspend fun setQualityMobile(quality: String)

    /** YouTube Music's "Stream via Wi-Fi only": no streaming on a metered network. */
    val streamOverWifiOnly: Flow<Boolean>

    suspend fun setStreamOverWifiOnly(enabled: Boolean)

    /** YouTube Music's "Double-tap to seek" step, in seconds. */
    val doubleTapSeekSeconds: Flow<Int>

    suspend fun setDoubleTapSeekSeconds(seconds: Int)

    /** YouTube Music's "Allow external devices to start playback" (car Bluetooth, headsets). */
    val allowExternalStartPlayback: Flow<Boolean>

    suspend fun setAllowExternalStartPlayback(enabled: Boolean)

    /** Library entries pinned to the top, by their Library key. */
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

    /**
     * When on, liking a song also queues it for offline download, at the existing download quality.
     *
     * Off by default — it spends storage and data without the user asking each time. Only applies
     * from the moment it is switched on: songs liked earlier are left alone, and unliking never
     * removes a download that already exists.
     */
    val autoDownloadLikedSongs: Flow<String>

    /**
     * Language code (e.g. "vi") of the audio track to prefer on videos that ship several — dubbed
     * podcasts, mostly. Empty means the original track. Deliberately NOT defaulted to the app
     * language the way [youtubeSubtitleLanguage] is: that would swap the speaker's own voice for an
     * AI dub for every user whose app language has one.
     */
    val preferredAudioLanguage: Flow<String>

    val explicitContentEnabled: Flow<String>

    suspend fun setExplicitContentEnabled(enabled: Boolean)

    companion object Values {
        const val SIMPMUSIC = "simpmusic"
        const val YOUTUBE = "youtube"
        const val LRCLIB = "lrclib"
        const val BETTER_LYRICS = "better_lyrics"

        /**
         * Raise this whenever a change makes previously cached lyrics untrustworthy. Costs each
         * user one re-fetch per song they play, and nothing else.
         *
         * 1 — the matcher that took the first search result, so live takes, covers and karaoke
         *     tracks were stored under the right song's name.
         * 2 — 2.3.8: YouTube Music's own timed lyrics replace the older matched timings.
         */
        const val LYRICS_CACHE_GENERATION = 2


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