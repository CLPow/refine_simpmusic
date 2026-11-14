package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.common.SponsorBlockType
import com.maxrave.data.security.SECRET_PURPOSE_SESSION
import com.maxrave.data.security.SecretCodec
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.LOCAL_PLAYLIST_FILTER_OLDER_FIRST
import com.maxrave.domain.manager.DataStoreManager.Values.REPEAT_ALL
import com.maxrave.domain.manager.DataStoreManager.Values.REPEAT_MODE_OFF
import com.maxrave.domain.manager.DataStoreManager.Values.REPEAT_ONE
import com.maxrave.domain.manager.DataStoreManager.Values.SIMPMUSIC
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.maxrave.common.QUALITY as COMMON_QUALITY

internal class DataStoreManagerImpl(
    private val settingsDataStore: DataStore<Preferences>,
) : DataStoreManager {
    override val location: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LOCATION] ?: "US"
        }

    override suspend fun setLocation(location: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LOCATION] = location
            }
        }
    }

    override val moodArtworkCache: Flow<String?> =
        settingsDataStore.data.map { preferences ->
            preferences[MOOD_ARTWORK_CACHE]
        }

    override val quality: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[QUALITY] ?: COMMON_QUALITY.items[COMMON_QUALITY.BEST_FREE_QUALITY_INDEX].toString()
        }

    override suspend fun setQuality(quality: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[QUALITY] = quality
            }
        }
    }

    override val downloadQuality: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[DOWNLOAD_QUALITY] ?: COMMON_QUALITY.items[COMMON_QUALITY.BEST_FREE_QUALITY_INDEX].toString()
        }

    override suspend fun setDownloadQuality(quality: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[DOWNLOAD_QUALITY] = quality
            }
        }
    }

    override val videoDownloadQuality: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[VIDEO_DOWNLOAD_QUALITY] ?: "720p"
        }

    override val language: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[stringPreferencesKey(SELECTED_LANGUAGE)] ?: SUPPORTED_LANGUAGE.codes.first()
        }

    override fun getString(key: String): Flow<String?> =
        settingsDataStore.data.map { preferences ->
            preferences[stringPreferencesKey(key)]
        }

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        settingsDataStore.edit { settings ->
            settings[stringPreferencesKey(key)] = value
        }
    }

    override val loggedIn: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LOGGED_IN] ?: FALSE
        }

    private fun encodeSecret(
        plain: String,
        purpose: String,
        previousStored: String?,
    ): String {
        val encoded = SecretCodec.encode(plain, purpose)
        if (plain.isNotEmpty() && encoded.isEmpty()) {
            return previousStored.orEmpty()
        }
        return encoded
    }

    override val cookie: Flow<String> =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[COOKIE] ?: "", SECRET_PURPOSE_SESSION)
        }

    override val pageId: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[PAGE_ID] ?: ""
        }

    override val authUser: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[AUTH_USER] ?: 0
        }

    override suspend fun setCookie(
        cookie: String,
        pageId: String?,
        authUser: Int,
    ) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[COOKIE] =
                    encodeSecret(
                        cookie,
                        SECRET_PURPOSE_SESSION,
                        settings[COOKIE],
                    )
                settings[PAGE_ID] = pageId ?: ""
                settings[AUTH_USER] = authUser
            }
        }
    }

    override suspend fun setLoggedIn(logged: Boolean) {
        withContext(Dispatchers.IO) {
            if (logged) {
                settingsDataStore.edit { settings ->
                    settings[LOGGED_IN] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[LOGGED_IN] = FALSE
                }
            }
        }
    }

    override val skipSilent: Flow<String> = flowOf(FALSE)

    override val saveStateOfPlayback: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[SAVE_STATE_OF_PLAYBACK] ?: FALSE
        }

    override val shuffleKey: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[SHUFFLE_KEY] ?: FALSE
        }
    override val repeatKey: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[REPEAT_KEY] ?: REPEAT_MODE_OFF
        }

    override suspend fun recoverShuffleAndRepeatKey(
        shuffle: Boolean,
        repeat: Int,
    ) {
        withContext(Dispatchers.IO) {
            if (shuffle) {
                settingsDataStore.edit { settings ->
                    settings[SHUFFLE_KEY] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SHUFFLE_KEY] = FALSE
                }
            }
            settingsDataStore.edit { settings ->
                settings[REPEAT_KEY] =
                    when (repeat) {
                        1 -> REPEAT_ONE
                        2 -> REPEAT_ALL
                        0 -> REPEAT_MODE_OFF
                        else -> REPEAT_MODE_OFF
                    }
            }
        }
    }

    override val recentMediaId =
        settingsDataStore.data.map { preferences ->
            preferences[RECENT_SONG_MEDIA_ID_KEY] ?: ""
        }
    override val recentPosition =
        settingsDataStore.data.map { preferences ->
            preferences[RECENT_SONG_POSITION_KEY] ?: "0"
        }

    override suspend fun saveRecentSong(
        mediaId: String,
        position: Long,
    ) {
        Logger.w("saveRecentSong", "$mediaId $position")
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[RECENT_SONG_MEDIA_ID_KEY] = mediaId
                settings[RECENT_SONG_POSITION_KEY] = position.toString()
            }
        }
    }

    override val playlistFromSaved =
        settingsDataStore.data.map { preferences ->
            preferences[FROM_SAVED_PLAYLIST] ?: ""
        }

    override suspend fun setPlaylistFromSaved(playlist: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[FROM_SAVED_PLAYLIST] = playlist
            }
        }
    }

    override val sendBackToGoogle =
        settingsDataStore.data.map { preferences ->
            preferences[SEND_BACK_TO_GOOGLE_V2] ?: TRUE
        }

    override suspend fun setSendBackToGoogle(send: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SEND_BACK_TO_GOOGLE_V2] = if (send) TRUE else FALSE
            }
        }
    }

    override val sponsorBlockEnabled: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[SPONSOR_BLOCK_ENABLED] ?: FALSE
        }

    override suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            if (enabled) {
                settingsDataStore.edit { settings ->
                    settings[SPONSOR_BLOCK_ENABLED] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SPONSOR_BLOCK_ENABLED] = FALSE
                }
            }
        }
    }

    override suspend fun getSponsorBlockCategories(): ArrayList<String> {
        val list: ArrayList<String> = arrayListOf()
        var anyStored = false
        for (category in SponsorBlockType.toList()) {
            val stored = getString(category.value).first()
            if (stored != null) anyStored = true
            if (stored == TRUE) list.add(category.value)
        }
        return if (anyStored) list else ArrayList(DEFAULT_SPONSOR_BLOCK_CATEGORIES)
    }

    override suspend fun setSponsorBlockCategories(categories: ArrayList<String>) {
        withContext(Dispatchers.IO) {
            Logger.w("setSponsorBlockCategories", categories.toString())
            for (category in categories) {
                settingsDataStore.edit { settings ->
                    settings[stringPreferencesKey(category)] = TRUE
                }
            }
            SponsorBlockType.toList().filter { !categories.contains(it.value) }.forEach { category ->
                settingsDataStore.edit { settings ->
                    // Store `.value` ("sponsor"), not toString() ("SPONSOR").
                    settings[stringPreferencesKey(category.value)] = FALSE
                }
            }
        }
    }

    override val lyricsProvider =
        settingsDataStore.data.map { preferences ->
            preferences[LYRICS_PROVIDER] ?: SIMPMUSIC
        }

    override suspend fun setLyricsProvider(provider: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_PROVIDER] = provider
            }
        }
    }

    override val lyricsCacheGeneration =
        settingsDataStore.data.map { preferences ->
            preferences[LYRICS_CACHE_GENERATION_KEY] ?: 0
        }

    override suspend fun setLyricsCacheGeneration(generation: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_CACHE_GENERATION_KEY] = generation
            }
        }
    }

    override val skipShortTrackSeconds: Flow<Int> = flowOf(0)

    override val maxSongCacheSize =
        settingsDataStore.data.map { preferences ->
            preferences[MAX_SONG_CACHE_SIZE] ?: -1
        }

    override val playerVolume: Flow<Float> =
        settingsDataStore.data.map { preferences ->
            preferences[PLAYER_VOLUME] ?: 1.0f
        }

    override suspend fun setPlayerVolume(volume: Float) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PLAYER_VOLUME] = volume.coerceIn(0f, 1f)
            }
        }
    }

    override val videoQuality =
        settingsDataStore.data.map { preferences ->
            preferences[VIDEO_QUALITY] ?: "720p"
        }

    override val syncFollowToYouTube: Flow<String> = flowOf(TRUE)

    override val chartKey =
        settingsDataStore.data.map { preferences ->
            preferences[CHART_KEY] ?: "ZZ"
        }

    override val endlessQueue =
        settingsDataStore.data.map { preferences ->
            preferences[ENDLESS_QUEUE] ?: TRUE
        }

    override suspend fun setEndlessQueue(endlessQueue: Boolean) {
        withContext(Dispatchers.IO) {
            if (endlessQueue) {
                settingsDataStore.edit { settings ->
                    settings[ENDLESS_QUEUE] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[ENDLESS_QUEUE] = FALSE
                }
            }
        }
    }

    override val qualityMobile: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[QUALITY_MOBILE] ?: quality.first()
        }

    override suspend fun setQualityMobile(quality: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { it[QUALITY_MOBILE] = quality }
        }
    }

    override val streamOverWifiOnly: Flow<Boolean> =
        settingsDataStore.data.map { preferences -> preferences[STREAM_OVER_WIFI_ONLY] == TRUE }

    override suspend fun setStreamOverWifiOnly(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { it[STREAM_OVER_WIFI_ONLY] = if (enabled) TRUE else FALSE }
        }
    }

    override val doubleTapSeekSeconds: Flow<Int> =
        settingsDataStore.data.map { preferences -> preferences[DOUBLE_TAP_SEEK_SECONDS] ?: 10 }

    override suspend fun setDoubleTapSeekSeconds(seconds: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { it[DOUBLE_TAP_SEEK_SECONDS] = seconds }
        }
    }

    override val allowExternalStartPlayback: Flow<Boolean> =
        settingsDataStore.data.map { preferences -> preferences[ALLOW_EXTERNAL_START_PLAYBACK] != FALSE }

    override suspend fun setAllowExternalStartPlayback(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { it[ALLOW_EXTERNAL_START_PLAYBACK] = if (enabled) TRUE else FALSE }
        }
    }

    override val pinnedLibraryKeys: Flow<Set<String>> =
        settingsDataStore.data.map { preferences -> preferences[PINNED_LIBRARY_KEYS].orEmpty() }

    override suspend fun setLibraryPinned(
        key: String,
        pinned: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                val current = settings[PINNED_LIBRARY_KEYS].orEmpty()
                settings[PINNED_LIBRARY_KEYS] = if (pinned) current + key else current - key
            }
        }
    }

    override val keepYouTubePlaylistOffline: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[KEEP_YOUTUBE_PLAYLIST_OFFLINE] ?: FALSE
        }

    override val combineLocalAndYouTubeLiked: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[COMBINE_LOCAL_AND_YOUTUBE_LIKED] ?: FALSE
        }

    override val shouldShowLogInRequiredAlert =
        settingsDataStore.data.map { preferences ->
            preferences[SHOULD_SHOW_LOG_IN_REQUIRED_ALERT] ?: TRUE
        }

    override suspend fun setShouldShowLogInRequiredAlert(shouldShow: Boolean) {
        withContext(Dispatchers.IO) {
            if (shouldShow) {
                settingsDataStore.edit { settings ->
                    settings[SHOULD_SHOW_LOG_IN_REQUIRED_ALERT] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SHOULD_SHOW_LOG_IN_REQUIRED_ALERT] = FALSE
                }
            }
        }
    }

    override val playbackSpeed =
        settingsDataStore.data.map { preferences ->
            preferences[PLAYBACK_SPEED] ?: 1.0f
        }

    override fun setPlaybackSpeed(speed: Float) {
        runBlocking {
            settingsDataStore.edit { settings ->
                settings[PLAYBACK_SPEED] = speed
            }
        }
    }

    override val pitch =
        settingsDataStore.data.map { preferences ->
            preferences[PITCH] ?: 0
        }

    override fun setPitch(pitch: Int) {
        runBlocking {
            settingsDataStore.edit { settings ->
                settings[PITCH] = pitch
            }
        }
    }

    override val dataSyncId =
        settingsDataStore.data.map { preferences ->
            preferences[DATA_SYNC_ID] ?: ""
        }

    override val visitorData =
        settingsDataStore.data.map { preferences ->
            preferences[VISITOR_DATA] ?: ""
        }

    override val localPlaylistFilter: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LOCAL_PLAYLIST_FILTER] ?: LOCAL_PLAYLIST_FILTER_OLDER_FIRST
        }

    override suspend fun setLocalPlaylistFilter(filter: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LOCAL_PLAYLIST_FILTER] = filter
            }
        }
    }

    override val killServiceOnExit: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[KILL_SERVICE_ON_EXIT] ?: FALSE
        }

    override val autoDownloadLikedSongs: Flow<String> = flowOf(FALSE)

    override val preferredAudioLanguage =
        settingsDataStore.data.map { preferences ->
            preferences[PREFERRED_AUDIO_LANGUAGE] ?: ""
        }

    override val explicitContentEnabled: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[EXPLICIT_CONTENT_ENABLED] ?: TRUE
        }

    override suspend fun setExplicitContentEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            if (enabled) {
                settingsDataStore.edit { settings ->
                    settings[EXPLICIT_CONTENT_ENABLED] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[EXPLICIT_CONTENT_ENABLED] = FALSE
                }
            }
        }
    }

    companion object Settings {
        val COOKIE = stringPreferencesKey("cookie")

        val PAGE_ID = stringPreferencesKey("page_id")
        val AUTH_USER = intPreferencesKey("auth_user")
        val LOGGED_IN = stringPreferencesKey("logged_in")
        val LOCATION = stringPreferencesKey("location")
        val MOOD_ARTWORK_CACHE = stringPreferencesKey("mood_artwork_cache")
        val QUALITY = stringPreferencesKey("quality")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val VIDEO_DOWNLOAD_QUALITY = stringPreferencesKey("video_download_quality")
        val SAVE_STATE_OF_PLAYBACK = stringPreferencesKey("save_state_of_playback")
        val RECENT_SONG_MEDIA_ID_KEY = stringPreferencesKey("recent_song_media_id")
        val RECENT_SONG_POSITION_KEY = stringPreferencesKey("recent_song_position")
        val SHUFFLE_KEY = stringPreferencesKey("shuffle_key")
        val REPEAT_KEY = stringPreferencesKey("repeat_key")
        val SEND_BACK_TO_GOOGLE_V2 = stringPreferencesKey("send_back_to_google_v2")
        val FROM_SAVED_PLAYLIST = stringPreferencesKey("from_saved_playlist")

        val KILL_SERVICE_ON_EXIT = stringPreferencesKey("kill_service_on_exit")
        val LYRICS_PROVIDER = stringPreferencesKey("lyrics_provider")
        val LYRICS_CACHE_GENERATION_KEY = intPreferencesKey("lyrics_cache_generation")

        val SPONSOR_BLOCK_ENABLED = stringPreferencesKey("sponsor_block_enabled_v2")

        val DEFAULT_SPONSOR_BLOCK_CATEGORIES: List<String> =
            listOf(
                SponsorBlockType.MUSIC_OFF_TOPIC.value,
                SponsorBlockType.SPONSOR.value,
                SponsorBlockType.SELF_PROMOTION.value,
                SponsorBlockType.INTRO.value,
                SponsorBlockType.OUTRO.value,
            )
        val MAX_SONG_CACHE_SIZE = intPreferencesKey("maxSongCacheSize")
        val WATCH_VIDEO_INSTEAD_OF_PLAYING_AUDIO =
            stringPreferencesKey("watch_video_instead_of_playing_audio")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val PLAYER_VOLUME = floatPreferencesKey("player_volume")
        val CHART_KEY = stringPreferencesKey("chart_key")
        val ENDLESS_QUEUE = stringPreferencesKey("endless_queue")
        val QUALITY_MOBILE = stringPreferencesKey("quality_mobile")
        val STREAM_OVER_WIFI_ONLY = stringPreferencesKey("stream_over_wifi_only")
        val DOUBLE_TAP_SEEK_SECONDS = intPreferencesKey("double_tap_seek_seconds")
        val ALLOW_EXTERNAL_START_PLAYBACK = stringPreferencesKey("allow_external_start_playback")
        val PINNED_LIBRARY_KEYS = stringSetPreferencesKey("pinned_library_keys")
        val KEEP_YOUTUBE_PLAYLIST_OFFLINE = stringPreferencesKey("keep_youtube_playlist_offline")
        val COMBINE_LOCAL_AND_YOUTUBE_LIKED = stringPreferencesKey("combine_local_and_youtube_liked")
        val SHOULD_SHOW_LOG_IN_REQUIRED_ALERT = stringPreferencesKey("should_show_log_in_required_alert")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PITCH = intPreferencesKey("pitch")
        val DATA_SYNC_ID = stringPreferencesKey("data_sync_id")
        val VISITOR_DATA = stringPreferencesKey("visitor_data")

        val LOCAL_PLAYLIST_FILTER = stringPreferencesKey("local_playlist_filter")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")

        val EXPLICIT_CONTENT_ENABLED = stringPreferencesKey("explicit_content_enabled")

    }
}

expect fun createDataStoreInstance(): DataStore<Preferences>