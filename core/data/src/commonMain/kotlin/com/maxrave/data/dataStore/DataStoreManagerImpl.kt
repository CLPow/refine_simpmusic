package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.common.SponsorBlockType
import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.data.security.SECRET_PURPOSE_SESSION
import com.maxrave.data.security.SecretCodec
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.AA_LYRICS_TITLE_AND_LYRIC
import com.maxrave.domain.manager.DataStoreManager.Values.AI_PROVIDER_GEMINI
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.GITHUB
import com.maxrave.domain.manager.DataStoreManager.Values.LOCAL_PLAYLIST_FILTER_OLDER_FIRST
import com.maxrave.domain.manager.DataStoreManager.Values.PROXY_TYPE_HTTP
import com.maxrave.domain.manager.DataStoreManager.Values.PROXY_TYPE_SOCKS
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
    override val appVersion: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[APP_VERSION] ?: ""
        }

    override suspend fun setAppVersion(version: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[APP_VERSION] = version
            }
        }
    }

    override val openAppTime: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[OPEN_APP_TIME] ?: 0
        }

    override suspend fun openApp() {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[OPEN_APP_TIME] = openAppTime.first() + 1
            }
        }
    }

    override suspend fun resetOpenAppTime() {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[OPEN_APP_TIME] = 0
            }
        }
    }

    override suspend fun doneOpenAppTime() {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[OPEN_APP_TIME] = 31
            }
        }
    }

    override val location: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LOCATION] ?: "VN"
        }

    override suspend fun setLocation(location: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LOCATION] = location
            }
        }
    }

    override val moodAndGenresCache: Flow<String?> =
        settingsDataStore.data.map { preferences ->
            preferences[MOOD_AND_GENRES_CACHE]
        }

    override suspend fun setMoodAndGenresCache(json: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[MOOD_AND_GENRES_CACHE] = json
            }
        }
    }

    override val moodArtworkCache: Flow<String?> =
        settingsDataStore.data.map { preferences ->
            preferences[MOOD_ARTWORK_CACHE]
        }

    override suspend fun setMoodArtworkCache(json: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[MOOD_ARTWORK_CACHE] = json
            }
        }
    }

    /**
     * Defaults to the best tier YouTube gives every account, not the worst.
     *
     * `items[0]` is "Low - 66kps" (itag 250), which is what this defaulted to before: every install
     * streamed at roughly 66 kbps despite itag 251 being served to Premium and non-Premium accounts
     * alike. Nothing was gating the better stream -- it simply was not being asked for. That is a
     * far larger audible difference than anything above it, since the tiers past this one need a
     * Premium entitlement that no client-side change can supply.
     */
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

    /** Same reasoning as [quality]: a download kept at 66 kbps is kept badly, and permanently. */
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

    override suspend fun setVideoDownloadQuality(quality: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[VIDEO_DOWNLOAD_QUALITY] = quality
            }
        }
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

    /**
     * Encode with SecretCodec, keeping the previously stored ciphertext when encryption fails.
     * An empty encode of a non-empty plain value means the platform refused to protect it —
     * overwriting the slot with "" would silently sign the user out.
     */
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

    // Protected at rest where the platform needs it — see SecretCodec. decode() passes plain text
    // through unchanged, so an install that predates this keeps its session and is re-protected on
    // the next write.
    override val cookie: Flow<String> =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[COOKIE] ?: "", SECRET_PURPOSE_SESSION)
        }

    override val pageId: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[PAGE_ID] ?: ""
        }

    override suspend fun setCookie(
        cookie: String,
        pageId: String?,
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

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val normalizeVolume: Flow<String> = flowOf(FALSE)

    override suspend fun setNormalizeVolume(normalize: Boolean) {
        withContext(Dispatchers.IO) {
            if (normalize) {
                settingsDataStore.edit { settings ->
                    settings[NORMALIZE_VOLUME] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[NORMALIZE_VOLUME] = FALSE
                }
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val skipSilent: Flow<String> = flowOf(FALSE)

    override suspend fun setSkipSilent(skip: Boolean) {
        withContext(Dispatchers.IO) {
            if (skip) {
                settingsDataStore.edit { settings ->
                    settings[SKIP_SILENT] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SKIP_SILENT] = FALSE
                }
            }
        }
    }

    override val saveStateOfPlayback: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[SAVE_STATE_OF_PLAYBACK] ?: FALSE
        }

    override suspend fun setSaveStateOfPlayback(save: Boolean) {
        withContext(Dispatchers.IO) {
            if (save) {
                settingsDataStore.edit { settings ->
                    settings[SAVE_STATE_OF_PLAYBACK] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SAVE_STATE_OF_PLAYBACK] = FALSE
                }
            }
        }
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

    override val saveRecentSongAndQueue: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[SAVE_RECENT_SONG] ?: FALSE
        }

    override suspend fun setSaveRecentSongAndQueue(save: Boolean) {
        withContext(Dispatchers.IO) {
            if (save) {
                settingsDataStore.edit { settings ->
                    settings[SAVE_RECENT_SONG] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SAVE_RECENT_SONG] = FALSE
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

    /**
     * On by default: reporting listens is what lets YouTube's recommendations learn from this app
     * at all. Stored under a new key so every existing install starts with it on once (the old
     * key defaulted to off, and almost nobody found the switch); a choice made after that sticks.
     */
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

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val sponsorBlockEnabled: Flow<String> = flowOf(FALSE)

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
        for (category in SponsorBlockType.toList()) {
            if (getString(category.value).first() == TRUE) list.add(category.value)
        }
        return list
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
                    settings[stringPreferencesKey(category.toString())] = FALSE
                }
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val enableTranslateLyric: Flow<String> = flowOf(FALSE)

    override suspend fun setEnableTranslateLyric(enable: Boolean) {
        withContext(Dispatchers.IO) {
            if (enable) {
                settingsDataStore.edit { settings ->
                    settings[USE_TRANSLATION_LANGUAGE] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[USE_TRANSLATION_LANGUAGE] = FALSE
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

    /**
     * Defaults to keeping the song name and putting the current line beneath it.
     *
     * On by default because the whole point is that it works in the car without anyone having gone
     * looking for a setting first, and this is the mode that adds lyrics without taking anything
     * away: the driver can still see what is playing at a glance.
     */
    override val androidAutoLyricsMode =
        settingsDataStore.data.map { preferences ->
            preferences[ANDROID_AUTO_LYRICS_MODE] ?: AA_LYRICS_TITLE_AND_LYRIC
        }

    override suspend fun setAndroidAutoLyricsMode(mode: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[ANDROID_AUTO_LYRICS_MODE] = mode
            }
        }
    }

    override val aaLyricsLeadMs: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            // Default 1000ms: head units paint lyrics late; user asked for car to lead by 1s.
            preferences[AA_LYRICS_LEAD_MS] ?: 1000
        }

    override suspend fun setAaLyricsLeadMs(ms: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AA_LYRICS_LEAD_MS] = ms.coerceIn(0, 3000)
            }
        }
    }

    override val lyricsSyncOffsetMs: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            // Default +1000ms: lyrics should appear ~1s early so phone and AA hosts
            // paint on time with the singer (see CarLyricsOverlay LEAD / this offset).
            preferences[LYRICS_SYNC_OFFSET_MS] ?: 1000
        }

    override suspend fun setLyricsSyncOffsetMs(ms: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_SYNC_OFFSET_MS] = ms.coerceIn(-2000, 2000)
            }
        }
    }

    override val lyricsFontScalePercent: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[LYRICS_FONT_SCALE_PERCENT] ?: 100
        }

    override suspend fun setLyricsFontScalePercent(percent: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_FONT_SCALE_PERCENT] = percent.coerceIn(50, 200)
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val skipShortTrackSeconds: Flow<Int> = flowOf(0)

    override suspend fun setSkipShortTrackSeconds(seconds: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SKIP_SHORT_TRACK_SECONDS] = seconds.coerceIn(0, 120)
            }
        }
    }

    override val crossfadePrepBufferMs: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[CROSSFADE_PREP_BUFFER_MS] ?: 3000
        }

    override suspend fun setCrossfadePrepBufferMs(ms: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CROSSFADE_PREP_BUFFER_MS] = ms.coerceIn(0, 15000)
            }
        }
    }

    override val listenTogetherConfirmJoin: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LISTEN_TOGETHER_CONFIRM_JOIN] ?: FALSE
        }

    override suspend fun setListenTogetherConfirmJoin(confirm: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LISTEN_TOGETHER_CONFIRM_JOIN] = if (confirm) TRUE else FALSE
            }
        }
    }

    override val lyricsSourceOrder: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LYRICS_SOURCE_ORDER] ?: ""
        }

    override suspend fun setLyricsSourceOrder(csv: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_SOURCE_ORDER] = csv
            }
        }
    }

    override val httpsOnly: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[HTTPS_ONLY] ?: FALSE
        }

    override suspend fun setHttpsOnly(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[HTTPS_ONLY] = if (enabled) TRUE else FALSE
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val checkForUpdates: Flow<String> = flowOf(FALSE)

    override suspend fun setCheckForUpdates(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CHECK_FOR_UPDATES] = if (enabled) TRUE else FALSE
            }
        }
    }

    override val translationLanguage =
        settingsDataStore.data.map { preferences ->
            val languageValue = language.first()
            preferences[TRANSLATION_LANGUAGE] ?: if (languageValue.length >= 2) {
                languageValue
                    .substring(0..1)
            } else {
                "en"
            }
        }

    override suspend fun setTranslationLanguage(language: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[TRANSLATION_LANGUAGE] = language
            }
        }
    }

    override val maxSongCacheSize =
        settingsDataStore.data.map { preferences ->
            preferences[MAX_SONG_CACHE_SIZE] ?: -1
        }

    override suspend fun setMaxSongCacheSize(size: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[MAX_SONG_CACHE_SIZE] = size
            }
        }
    }

    override val watchVideoInsteadOfPlayingAudio =
        settingsDataStore.data.map { preferences ->
            preferences[WATCH_VIDEO_INSTEAD_OF_PLAYING_AUDIO] ?: FALSE
        }

    override suspend fun setWatchVideoInsteadOfPlayingAudio(watch: Boolean) {
        withContext(Dispatchers.IO) {
            if (watch) {
                settingsDataStore.edit { settings ->
                    settings[WATCH_VIDEO_INSTEAD_OF_PLAYING_AUDIO] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[WATCH_VIDEO_INSTEAD_OF_PLAYING_AUDIO] = FALSE
                }
            }
        }
    }

    override val radioAudioOnly =
        settingsDataStore.data.map { preferences ->
            preferences[RADIO_AUDIO_ONLY] ?: FALSE
        }

    override suspend fun setRadioAudioOnly(audioOnly: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[RADIO_AUDIO_ONLY] = if (audioOnly) TRUE else FALSE
            }
        }
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

    override suspend fun setVideoQuality(quality: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[VIDEO_QUALITY] = quality
            }
        }
    }

    override val spdc =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[SPDC] ?: "", "spotify.sp_dc")
        }

    override suspend fun setSpdc(spdc: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SPDC] = encodeSecret(spdc, "spotify.sp_dc", settings[SPDC])
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val equalizerEnabled: Flow<String> = flowOf(FALSE)

    override suspend fun setEqualizerEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[EQUALIZER_ENABLED] = if (enabled) TRUE else FALSE
            }
        }
    }

    override val equalizerBands: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[EQUALIZER_BANDS] ?: ""
        }

    override suspend fun setEqualizerBands(bandsDb: List<Float>) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                // Blank when flat, so "no equalizer" and "an equalizer set to zero" are the same
                // stored state and neither installs a filter chain.
                settings[EQUALIZER_BANDS] =
                    if (bandsDb.all { it == 0f }) "" else bandsDb.joinToString(",")
            }
        }
    }

    override val equalizerPreamp: Flow<Float> =
        settingsDataStore.data.map { preferences ->
            preferences[EQUALIZER_PREAMP]?.toFloatOrNull() ?: 0f
        }

    override suspend fun setEqualizerPreamp(preampDb: Float) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[EQUALIZER_PREAMP] = preampDb.toString()
            }
        }
    }

    override val equalizerAutoEqProfile: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[EQUALIZER_AUTOEQ_PROFILE] ?: ""
        }

    override suspend fun setEqualizerAutoEqProfile(
        label: String,
        bandsDb: List<Float>,
    ) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[EQUALIZER_AUTOEQ_PROFILE] =
                    if (label.isBlank()) "" else label + "\n" + bandsDb.joinToString(",")
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val syncFollowToYouTube: Flow<String> = flowOf(TRUE)

    override suspend fun setSyncFollowToYouTube(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SYNC_FOLLOW_TO_YOUTUBE] = if (enabled) TRUE else FALSE
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val spotifyLyrics: Flow<String> = flowOf(FALSE)

    override suspend fun setSpotifyLyrics(spotifyLyrics: Boolean) {
        withContext(Dispatchers.IO) {
            if (spotifyLyrics) {
                settingsDataStore.edit { settings ->
                    settings[SPOTIFY_LYRICS] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SPOTIFY_LYRICS] = FALSE
                }
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val spotifyCanvas: Flow<String> = flowOf(FALSE)

    override suspend fun setSpotifyCanvas(spotifyCanvas: Boolean) {
        withContext(Dispatchers.IO) {
            if (spotifyCanvas) {
                settingsDataStore.edit { settings ->
                    settings[SPOTIFY_CANVAS] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[SPOTIFY_CANVAS] = FALSE
                }
            }
        }
    }

    override val spotifyClientToken: Flow<String> =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[SPOTIFY_CLIENT_TOKEN] ?: "", "spotify.client_token")
        }

    override suspend fun setSpotifyClientToken(token: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SPOTIFY_CLIENT_TOKEN] =
                    encodeSecret(token, "spotify.client_token", settings[SPOTIFY_CLIENT_TOKEN])
            }
        }
    }

    override val spotifyClientTokenExpires: Flow<Long> =
        settingsDataStore.data.map { preferences ->
            preferences[SPOTIFY_CLIENT_TOKEN_EXPIRES] ?: 0
        }

    override suspend fun setSpotifyClientTokenExpires(expires: Long) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SPOTIFY_CLIENT_TOKEN_EXPIRES] = expires
            }
        }
    }

    // Fallback "" (blank) on purpose: credentials are not hard-coded in source — they come
    // only from the remote config. CommonRepositoryImpl pushes a value into YouTube only when
    // non-blank, so an empty cache simply leaves TIDAL disabled until the first fetch.
    override val tidalClientId: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[TIDAL_CLIENT_ID] ?: ""
        }

    override suspend fun setTidalClientId(value: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[TIDAL_CLIENT_ID] = value
            }
        }
    }

    override val tidalClientSecret: Flow<String> =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[TIDAL_CLIENT_SECRET] ?: "", "tidal.client_secret")
        }

    override suspend fun setTidalClientSecret(value: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[TIDAL_CLIENT_SECRET] =
                    encodeSecret(value, "tidal.client_secret", settings[TIDAL_CLIENT_SECRET])
            }
        }
    }

    override val spotifyPersonalToken: Flow<String> =
        settingsDataStore.data.map { preferences ->
            SecretCodec.decode(preferences[SPOTIFY_PERSONAL_TOKEN] ?: "", "spotify.personal_token")
        }

    override suspend fun setSpotifyPersonalToken(token: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SPOTIFY_PERSONAL_TOKEN] =
                    encodeSecret(token, "spotify.personal_token", settings[SPOTIFY_PERSONAL_TOKEN])
            }
        }
    }

    override val spotifyPersonalTokenExpires: Flow<Long> =
        settingsDataStore.data.map { preferences ->
            preferences[SPOTIFY_PERSONAL_TOKEN_EXPIRES] ?: 0
        }

    override suspend fun setSpotifyPersonalTokenExpires(expires: Long) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[SPOTIFY_PERSONAL_TOKEN_EXPIRES] = expires
            }
        }
    }

    override val homeLimit: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[HOME_LIMIT] ?: 5
        }

    override suspend fun setHomeLimit(limit: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[HOME_LIMIT] = limit
            }
        }
    }

    override val chartKey =
        settingsDataStore.data.map { preferences ->
            preferences[CHART_KEY] ?: "ZZ"
        }

    override suspend fun setChartKey(key: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CHART_KEY] = key
            }
        }
    }

    // The YouTube Music look is the only look: its nav bar is solid, so this is fixed off.
    override val translucentBottomBar: Flow<String> = flowOf(FALSE)

    override suspend fun setTranslucentBottomBar(translucent: Boolean) {
        withContext(Dispatchers.IO) {
            if (translucent) {
                settingsDataStore.edit { settings ->
                    settings[TRANSLUCENT_BOTTOM_BAR] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[TRANSLUCENT_BOTTOM_BAR] = FALSE
                }
            }
        }
    }

    // YouTube Music is the only theme (2.1.17). Whatever an older version stored is ignored.
    override val themeMode: Flow<String> = flowOf(DataStoreManager.THEME_MODE_YTM)

    override suspend fun setThemeMode(mode: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[THEME_MODE_V2] = mode
            }
        }
    }

    override val themeColorSource =
        settingsDataStore.data.map { preferences ->
            preferences[THEME_COLOR_SOURCE] ?: DataStoreManager.THEME_COLOR_DEFAULT
        }

    override suspend fun setThemeColorSource(source: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[THEME_COLOR_SOURCE] = source
            }
        }
    }

    override val customThemeColor =
        settingsDataStore.data.map { preferences ->
            preferences[CUSTOM_THEME_COLOR] ?: DataStoreManager.DEFAULT_THEME_COLOR_HEX
        }

    override suspend fun setCustomThemeColor(argbHex: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CUSTOM_THEME_COLOR] = argbHex
            }
        }
    }

    override val nowPlayingStyle =
        settingsDataStore.data.map { preferences ->
            preferences[NOW_PLAYING_STYLE] ?: DataStoreManager.NOW_PLAYING_STYLE_APPLE_MUSIC
        }

    override suspend fun setNowPlayingStyle(style: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[NOW_PLAYING_STYLE] = style
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val lyricsStyle: Flow<String> = flowOf(DataStoreManager.LYRICS_STYLE_CLASSIC)

    override suspend fun setLyricsStyle(style: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LYRICS_STYLE] = style
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val romanizationLanguages: Flow<String> = flowOf("")

    override suspend fun setRomanizationLanguages(languages: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[ROMANIZATION_LANGUAGES] = languages
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val usingProxy: Flow<String> = flowOf(FALSE)

    override suspend fun setUsingProxy(usingProxy: Boolean) {
        withContext(Dispatchers.IO) {
            if (usingProxy) {
                settingsDataStore.edit { settings ->
                    settings[USING_PROXY] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[USING_PROXY] = FALSE
                }
            }
        }
    }

    override val proxyType =
        settingsDataStore.data
            .map { preferences ->
                preferences[PROXY_TYPE]
            }.map {
                when (it) {
                    PROXY_TYPE_HTTP -> DataStoreManager.ProxyType.PROXY_TYPE_HTTP
                    PROXY_TYPE_SOCKS -> DataStoreManager.ProxyType.PROXY_TYPE_SOCKS
                    else -> DataStoreManager.ProxyType.PROXY_TYPE_HTTP
                }
            }

    override suspend fun setProxyType(proxyType: DataStoreManager.ProxyType) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PROXY_TYPE] =
                    when (proxyType) {
                        DataStoreManager.ProxyType.PROXY_TYPE_HTTP -> PROXY_TYPE_HTTP
                        DataStoreManager.ProxyType.PROXY_TYPE_SOCKS -> PROXY_TYPE_SOCKS
                    }
            }
        }
    }

    override val proxyHost =
        settingsDataStore.data.map { preferences ->
            preferences[PROXY_HOST] ?: ""
        }

    override suspend fun setProxyHost(proxyHost: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PROXY_HOST] = proxyHost
            }
        }
    }

    override val proxyPort =
        settingsDataStore.data.map { preferences ->
            preferences[PROXY_PORT] ?: 8000
        }

    override suspend fun setProxyPort(proxyPort: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PROXY_PORT] = proxyPort
            }
        }
    }

    override val proxyUsername =
        settingsDataStore.data.map { preferences ->
            preferences[PROXY_USERNAME] ?: ""
        }

    override suspend fun setProxyUsername(proxyUsername: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PROXY_USERNAME] = proxyUsername
            }
        }
    }

    override val proxyPassword =
        settingsDataStore.data.map { preferences ->
            preferences[PROXY_PASSWORD] ?: ""
        }

    override suspend fun setProxyPassword(proxyPassword: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[PROXY_PASSWORD] = proxyPassword
            }
        }
    }

    override fun getJVMProxy(): ProxyConfiguration? =
        runBlocking {
            try {
                if (usingProxy.first() == TRUE) {
                    val proxyType = proxyType.first()
                    val proxyHost = proxyHost.first()
                    val proxyPort = proxyPort.first()
                    val proxyUsername = proxyUsername.first()
                    val proxyPassword = proxyPassword.first()
                    return@runBlocking ProxyConfiguration(
                        proxyHost,
                        proxyPort,
                        proxyType,
                        proxyUsername.ifEmpty { null },
                        proxyPassword.ifEmpty { null },
                    )
                } else {
                    return@runBlocking null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@runBlocking null
            }
        }

    override val endlessQueue =
        settingsDataStore.data.map { preferences ->
            // On unless turned off, as YouTube Music's Autoplay is: an album or single runs on into
            // similar songs instead of stopping at its last track.
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

    override val keepYouTubePlaylistOffline: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[KEEP_YOUTUBE_PLAYLIST_OFFLINE] ?: FALSE
        }

    override suspend fun setKeepYouTubePlaylistOffline(keep: Boolean) {
        withContext(Dispatchers.IO) {
            if (keep) {
                settingsDataStore.edit { settings ->
                    settings[KEEP_YOUTUBE_PLAYLIST_OFFLINE] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[KEEP_YOUTUBE_PLAYLIST_OFFLINE] = FALSE
                }
            }
        }
    }

    override val combineLocalAndYouTubeLiked: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[COMBINE_LOCAL_AND_YOUTUBE_LIKED] ?: FALSE
        }

    override suspend fun setCombineLocalAndYouTubeLiked(combine: Boolean) {
        withContext(Dispatchers.IO) {
            if (combine) {
                settingsDataStore.edit { settings ->
                    settings[COMBINE_LOCAL_AND_YOUTUBE_LIKED] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[COMBINE_LOCAL_AND_YOUTUBE_LIKED] = FALSE
                }
            }
        }
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

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val autoCheckForUpdates: Flow<String> = flowOf(FALSE)

    override suspend fun setAutoCheckForUpdates(autoCheck: Boolean) {
        withContext(Dispatchers.IO) {
            if (autoCheck) {
                settingsDataStore.edit { settings ->
                    settings[AUTO_CHECK_FOR_UPDATES] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[AUTO_CHECK_FOR_UPDATES] = FALSE
                }
            }
        }
    }

    override val updateChannel =
        settingsDataStore.data.map { preferences ->
            preferences[UPDATE_CHANNEL] ?: GITHUB
        }

    override suspend fun setUpdateChannel(channel: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[UPDATE_CHANNEL] = channel
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

    override suspend fun setDataSyncId(dataSyncId: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[DATA_SYNC_ID] = dataSyncId
            }
        }
    }

    override val visitorData =
        settingsDataStore.data.map { preferences ->
            preferences[VISITOR_DATA] ?: ""
        }

    override suspend fun setVisitorData(visitorData: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[VISITOR_DATA] = visitorData
            }
        }
    }

    override suspend fun setAIProvider(provider: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[stringPreferencesKey("ai_provider")] = provider
            }
        }
    }

    override val aiProvider: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[AI_PROVIDER] ?: AI_PROVIDER_GEMINI
        }

    override suspend fun setAIApiKey(apiKey: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AI_API_KEY] = apiKey
            }
        }
    }

    override val aiApiKey: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[AI_API_KEY] ?: ""
        }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val useAITranslation: Flow<String> = flowOf(FALSE)

    override suspend fun setUseAITranslation(use: Boolean) {
        withContext(Dispatchers.IO) {
            if (use) {
                settingsDataStore.edit { settings ->
                    settings[USE_AI_TRANSLATION] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[USE_AI_TRANSLATION] = FALSE
                }
            }
        }
    }

    override val customModelId =
        settingsDataStore.data.map { preferences ->
            preferences[CUSTOM_MODEL_ID] ?: ""
        }

    override suspend fun setCustomModelId(modelId: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CUSTOM_MODEL_ID] = modelId
            }
        }
    }

    override val customOpenAIBaseUrl: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CUSTOM_OPENAI_BASE_URL] ?: ""
        }

    override suspend fun setCustomOpenAIBaseUrl(baseUrl: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CUSTOM_OPENAI_BASE_URL] = baseUrl
            }
        }
    }

    override val customOpenAIHeaders: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CUSTOM_OPENAI_HEADERS] ?: ""
        }

    override suspend fun setCustomOpenAIHeaders(headers: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CUSTOM_OPENAI_HEADERS] = headers
            }
        }
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

    override suspend fun setKillServiceOnExit(kill: Boolean) {
        withContext(Dispatchers.IO) {
            if (kill) {
                settingsDataStore.edit { settings ->
                    settings[KILL_SERVICE_ON_EXIT] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[KILL_SERVICE_ON_EXIT] = FALSE
                }
            }
        }
    }

    override val keepServiceAlive: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[KEEP_SERVICE_ALIVE] ?: FALSE
        }

    override suspend fun setKeepServiceAlive(keep: Boolean) {
        withContext(Dispatchers.IO) {
            if (keep) {
                settingsDataStore.edit { settings ->
                    settings[KEEP_SERVICE_ALIVE] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[KEEP_SERVICE_ALIVE] = FALSE
                }
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val crossfadeEnabled: Flow<String> = flowOf(FALSE)

    override suspend fun setCrossfadeEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            if (enabled) {
                settingsDataStore.edit { settings ->
                    settings[CROSSFADE_ENABLED] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[CROSSFADE_ENABLED] = FALSE
                }
            }
        }
    }

    override val crossfadeDuration: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[CROSSFADE_DURATION] ?: 5000
        }

    override suspend fun setCrossfadeDuration(duration: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CROSSFADE_DURATION] = duration
            }
        }
    }

    /**
     * Off by default.
     *
     * DJ mode high-pass sweeps the incoming track from 2 kHz down to 20 Hz across the whole
     * overlap, so the next song's opening arrives with its bass stripped, quietly, underneath the
     * outgoing one. As an effect somebody chooses that is a fine thing to offer; as the default it
     * simply made every transition sound wrong.
     */
    override val crossfadeDjMode: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CROSSFADE_DJ_MODE] ?: FALSE
        }

    override suspend fun setCrossfadeDjMode(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CROSSFADE_DJ_MODE] = if (enabled) TRUE else FALSE
            }
        }
    }

    // Defaults to FALSE: anyone already running crossfade would otherwise find it silently absent
    // on albums after updating.
    override val crossfadeSkipAlbum: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CROSSFADE_SKIP_ALBUM] ?: FALSE
        }

    override suspend fun setCrossfadeSkipAlbum(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[CROSSFADE_SKIP_ALBUM] = if (enabled) TRUE else FALSE
            }
        }
    }

    // Defaults to FALSE: it spends storage and mobile data on the user's behalf.
    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val autoDownloadLikedSongs: Flow<String> = flowOf(FALSE)

    override suspend fun setAutoDownloadLikedSongs(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AUTO_DOWNLOAD_LIKED_SONGS] = if (enabled) TRUE else FALSE
            }
        }
    }

    override val youtubeSubtitleLanguage =
        settingsDataStore.data.map { preferences ->
            val languageValue = language.first()
            preferences[YOUTUBE_SUBTITLE_LANGUAGE] ?: if (languageValue.length >= 2) {
                languageValue
                    .substring(0..1)
            } else {
                "en"
            }
        }

    override suspend fun setYoutubeSubtitleLanguage(language: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[YOUTUBE_SUBTITLE_LANGUAGE] = language
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val helpBuildLyricsDatabase: Flow<String> = flowOf(FALSE)

    override suspend fun setHelpBuildLyricsDatabase(help: Boolean) {
        withContext(Dispatchers.IO) {
            if (help) {
                settingsDataStore.edit { settings ->
                    settings[HELP_BUILD_LYRICS_DATABASE] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[HELP_BUILD_LYRICS_DATABASE] = FALSE
                }
            }
        }
    }

    override val contributorName: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CONTRIBUTOR_NAME] ?: ""
        }

    override val contributorEmail: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[CONTRIBUTOR_EMAIL] ?: ""
        }

    override suspend fun setContributorLyricsDatabase(
        contributor: Pair<String, String>?, // contributor name and email, null if anonymous
    ) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                if (contributor == null) {
                    settings[CONTRIBUTOR_NAME] = ""
                    settings[CONTRIBUTOR_EMAIL] = ""
                } else {
                    settings[CONTRIBUTOR_NAME] = contributor.first
                    settings[CONTRIBUTOR_EMAIL] = contributor.second
                }
            }
        }
    }

    override val backupDownloaded: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[BACKUP_DOWNLOADED] ?: FALSE
        }

    override suspend fun setBackupDownloaded(backupDownloaded: Boolean) {
        withContext(Dispatchers.IO) {
            if (backupDownloaded) {
                settingsDataStore.edit { settings ->
                    settings[BACKUP_DOWNLOADED] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[BACKUP_DOWNLOADED] = FALSE
                }
            }
        }
    }

    // Liquid glass is not part of the YouTube Music look, so it stays off.
    override val enableLiquidGlass: Flow<String> = flowOf(FALSE)

    override suspend fun setEnableLiquidGlass(enable: Boolean) {
        withContext(Dispatchers.IO) {
            if (enable) {
                settingsDataStore.edit { settings ->
                    settings[LIQUID_GLASS] = TRUE
                }
            } else {
                settingsDataStore.edit { settings ->
                    settings[LIQUID_GLASS] = FALSE
                }
            }
        }
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

    override val discordToken: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[DISCORD_TOKEN] ?: ""
        }

    override suspend fun setDiscordToken(token: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[DISCORD_TOKEN] = token
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val richPresenceEnabled: Flow<String> = flowOf(FALSE)

    override suspend fun setRichPresenceEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[RICH_PRESENCE] = if (enabled) TRUE else FALSE
            }
        }
    }

    override val lastfmSessionKey: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LASTFM_SESSION_KEY] ?: ""
        }

    override val lastfmUsername: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[LASTFM_USERNAME] ?: ""
        }

    /**
     * Key and username are written together, and an empty key clears both.
     *
     * They only mean anything as a pair: a username with no key cannot scrobble, and a key with no
     * username leaves settings unable to say whose account is connected. One edit also means
     * logging out cannot leave half the pair behind if the process dies mid-way.
     */
    override suspend fun setLastfmSession(
        sessionKey: String,
        username: String,
    ) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LASTFM_SESSION_KEY] = sessionKey
                settings[LASTFM_USERNAME] = if (sessionKey.isEmpty()) "" else username
            }
        }
    }

    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val lastfmScrobbleEnabled: Flow<String> = flowOf(FALSE)

    override suspend fun setLastfmScrobbleEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LASTFM_SCROBBLE_ENABLED] = if (enabled) TRUE else FALSE
            }
        }
    }

    /**
     * On by default, and it never leaves the device.
     *
     * This is what fills `playback_event` and `event_artist`. Defaulting it off meant a fresh
     * install recorded nothing, so Wrapped and the analytics screen had nothing to show and there
     * was no local signal to rank recommendations with. Unlike [sendBackToGoogle], nothing here is
     * transmitted anywhere.
     */
    // Not part of the YouTube Music replica (2.2.0): pinned, whatever an older version stored.
    override val localTrackingEnabled: Flow<String> = flowOf(FALSE)

    override suspend fun setLocalTrackingEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[LOCAL_TRACKING_ENABLED] = if (enabled) TRUE else FALSE
            }
        }
    }

    // Auto Backup
    override val autoBackupEnabled: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[AUTO_BACKUP_ENABLED] ?: FALSE
        }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AUTO_BACKUP_ENABLED] = if (enabled) TRUE else FALSE
            }
        }
    }

    override val autoBackupFrequency: Flow<String> =
        settingsDataStore.data.map { preferences ->
            preferences[AUTO_BACKUP_FREQUENCY] ?: DataStoreManager.AUTO_BACKUP_FREQUENCY_DAILY
        }

    override suspend fun setAutoBackupFrequency(frequency: String) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AUTO_BACKUP_FREQUENCY] = frequency
            }
        }
    }

    override val autoBackupMaxFiles: Flow<Int> =
        settingsDataStore.data.map { preferences ->
            preferences[AUTO_BACKUP_MAX_FILES] ?: 5
        }

    override suspend fun setAutoBackupMaxFiles(max: Int) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AUTO_BACKUP_MAX_FILES] = max
            }
        }
    }

    override val autoBackupLastTime: Flow<Long> =
        settingsDataStore.data.map { preferences ->
            preferences[AUTO_BACKUP_LAST_TIME] ?: 0L
        }

    override suspend fun setAutoBackupLastTime(time: Long) {
        withContext(Dispatchers.IO) {
            settingsDataStore.edit { settings ->
                settings[AUTO_BACKUP_LAST_TIME] = time
            }
        }
    }

    companion object Settings {
        val APP_VERSION = stringPreferencesKey("app_version")
        val COOKIE = stringPreferencesKey("cookie")

        val PAGE_ID = stringPreferencesKey("page_id")
        val LOGGED_IN = stringPreferencesKey("logged_in")
        val LOCATION = stringPreferencesKey("location")
        val MOOD_AND_GENRES_CACHE = stringPreferencesKey("mood_and_genres_cache")
        val MOOD_ARTWORK_CACHE = stringPreferencesKey("mood_artwork_cache")
        val QUALITY = stringPreferencesKey("quality")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val VIDEO_DOWNLOAD_QUALITY = stringPreferencesKey("video_download_quality")
        val NORMALIZE_VOLUME = stringPreferencesKey("normalize_volume")
        val SKIP_SILENT = stringPreferencesKey("skip_silent")
        val SAVE_STATE_OF_PLAYBACK = stringPreferencesKey("save_state_of_playback")
        val SAVE_RECENT_SONG = stringPreferencesKey("save_recent_song")
        val RECENT_SONG_MEDIA_ID_KEY = stringPreferencesKey("recent_song_media_id")
        val RECENT_SONG_POSITION_KEY = stringPreferencesKey("recent_song_position")
        val SHUFFLE_KEY = stringPreferencesKey("shuffle_key")
        val REPEAT_KEY = stringPreferencesKey("repeat_key")
        val SEND_BACK_TO_GOOGLE_V2 = stringPreferencesKey("send_back_to_google_v2")
        val FROM_SAVED_PLAYLIST = stringPreferencesKey("from_saved_playlist")

        val KILL_SERVICE_ON_EXIT = stringPreferencesKey("kill_service_on_exit")
        val KEEP_SERVICE_ALIVE = stringPreferencesKey("keep_service_alive")
        val CROSSFADE_ENABLED = stringPreferencesKey("crossfade_enabled")
        val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
        val CROSSFADE_DJ_MODE = stringPreferencesKey("crossfade_dj_mode")
        val CROSSFADE_SKIP_ALBUM = stringPreferencesKey("crossfade_skip_album")
        val AUTO_DOWNLOAD_LIKED_SONGS = stringPreferencesKey("auto_download_liked_songs")
        val LYRICS_PROVIDER = stringPreferencesKey("lyrics_provider")
        val ANDROID_AUTO_LYRICS_MODE = stringPreferencesKey("android_auto_lyrics_mode")
        val AA_LYRICS_LEAD_MS = intPreferencesKey("aa_lyrics_lead_ms")
        val LYRICS_SYNC_OFFSET_MS = intPreferencesKey("lyrics_sync_offset_ms")
        val LYRICS_FONT_SCALE_PERCENT = intPreferencesKey("lyrics_font_scale_percent")
        val SKIP_SHORT_TRACK_SECONDS = intPreferencesKey("skip_short_track_seconds")
        val CROSSFADE_PREP_BUFFER_MS = intPreferencesKey("crossfade_prep_buffer_ms")
        val LISTEN_TOGETHER_CONFIRM_JOIN = stringPreferencesKey("listentogether_confirm_join")
        val LYRICS_SOURCE_ORDER = stringPreferencesKey("lyrics_source_order")
        val HTTPS_ONLY = stringPreferencesKey("https_only")
        val CHECK_FOR_UPDATES = stringPreferencesKey("check_for_updates")
        val LYRICS_CACHE_GENERATION_KEY = intPreferencesKey("lyrics_cache_generation")
        val TRANSLATION_LANGUAGE = stringPreferencesKey("translation_language")
        val USE_TRANSLATION_LANGUAGE = stringPreferencesKey("use_translation_language")

        val SPONSOR_BLOCK_ENABLED = stringPreferencesKey("sponsor_block_enabled")
        val MAX_SONG_CACHE_SIZE = intPreferencesKey("maxSongCacheSize")
        val WATCH_VIDEO_INSTEAD_OF_PLAYING_AUDIO =
            stringPreferencesKey("watch_video_instead_of_playing_audio")
        val RADIO_AUDIO_ONLY = stringPreferencesKey("radio_audio_only")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val PLAYER_VOLUME = floatPreferencesKey("player_volume")
        val SPDC = stringPreferencesKey("sp_dc")
        val SPOTIFY_LYRICS = stringPreferencesKey("spotify_lyrics")
        val SYNC_FOLLOW_TO_YOUTUBE = stringPreferencesKey("sync_follow_to_youtube")
        val EQUALIZER_AUTOEQ_PROFILE = stringPreferencesKey("equalizer_autoeq_profile")
        val EQUALIZER_BANDS = stringPreferencesKey("equalizer_bands")
        val EQUALIZER_ENABLED = stringPreferencesKey("equalizer_enabled")
        val EQUALIZER_PREAMP = stringPreferencesKey("equalizer_preamp")
        val SPOTIFY_CANVAS = stringPreferencesKey("spotify_canvas")
        val SPOTIFY_CLIENT_TOKEN = stringPreferencesKey("spotify_client_token")
        val SPOTIFY_CLIENT_TOKEN_EXPIRES = longPreferencesKey("spotify_client_token_expires")
        val SPOTIFY_PERSONAL_TOKEN = stringPreferencesKey("spotify_personal_token")
        val SPOTIFY_PERSONAL_TOKEN_EXPIRES = longPreferencesKey("spotify_personal_token_expires")
        val TIDAL_CLIENT_ID = stringPreferencesKey("tidal_client_id")
        val TIDAL_CLIENT_SECRET = stringPreferencesKey("tidal_client_secret")
        val HOME_LIMIT = intPreferencesKey("home_limit")
        val CHART_KEY = stringPreferencesKey("chart_key")
        val TRANSLUCENT_BOTTOM_BAR = stringPreferencesKey("translucent_bottom_bar")
        val THEME_MODE_V2 = stringPreferencesKey("theme_mode_v2")
        val THEME_COLOR_SOURCE = stringPreferencesKey("theme_color_source")
        val CUSTOM_THEME_COLOR = stringPreferencesKey("custom_theme_color")
        val NOW_PLAYING_STYLE = stringPreferencesKey("now_playing_style")
        val LYRICS_STYLE = stringPreferencesKey("lyrics_style")
        val ROMANIZATION_LANGUAGES = stringPreferencesKey("romanization_languages")
        val USING_PROXY = stringPreferencesKey("using_proxy")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val ENDLESS_QUEUE = stringPreferencesKey("endless_queue")
        val KEEP_YOUTUBE_PLAYLIST_OFFLINE = stringPreferencesKey("keep_youtube_playlist_offline")
        val COMBINE_LOCAL_AND_YOUTUBE_LIKED = stringPreferencesKey("combine_local_and_youtube_liked")
        val SHOULD_SHOW_LOG_IN_REQUIRED_ALERT = stringPreferencesKey("should_show_log_in_required_alert")
        val AUTO_CHECK_FOR_UPDATES = stringPreferencesKey("auto_check_for_updates")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PITCH = intPreferencesKey("pitch")
        val OPEN_APP_TIME = intPreferencesKey("open_app_time")
        val DATA_SYNC_ID = stringPreferencesKey("data_sync_id")
        val VISITOR_DATA = stringPreferencesKey("visitor_data")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_API_KEY = stringPreferencesKey("ai_gemini_api_key")

        val CUSTOM_MODEL_ID = stringPreferencesKey("custom_model_id")
        val CUSTOM_OPENAI_BASE_URL = stringPreferencesKey("custom_openai_base_url")
        val CUSTOM_OPENAI_HEADERS = stringPreferencesKey("custom_openai_headers")

        val USE_AI_TRANSLATION = stringPreferencesKey("use_ai_translation")

        val LOCAL_PLAYLIST_FILTER = stringPreferencesKey("local_playlist_filter")
        val YOUTUBE_SUBTITLE_LANGUAGE = stringPreferencesKey("youtube_subtitle_language")
        val HELP_BUILD_LYRICS_DATABASE = stringPreferencesKey("help_build_lyrics_database")
        val CONTRIBUTOR_NAME = stringPreferencesKey("contributor_name")
        val CONTRIBUTOR_EMAIL = stringPreferencesKey("contributor_email")

        val BACKUP_DOWNLOADED = stringPreferencesKey("backup_downloaded")

        val LIQUID_GLASS = stringPreferencesKey("liquid_glass")

        val EXPLICIT_CONTENT_ENABLED = stringPreferencesKey("explicit_content_enabled")

        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val RICH_PRESENCE = stringPreferencesKey("rich_presence")

        val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")
        val LASTFM_SCROBBLE_ENABLED = stringPreferencesKey("lastfm_scrobble_enabled")

        val LOCAL_TRACKING_ENABLED = stringPreferencesKey("local_tracking_enabled")


        // Auto Backup
        val AUTO_BACKUP_ENABLED = stringPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_FREQUENCY = stringPreferencesKey("auto_backup_frequency")
        val AUTO_BACKUP_MAX_FILES = intPreferencesKey("auto_backup_max_files")
        val AUTO_BACKUP_LAST_TIME = longPreferencesKey("auto_backup_last_time")
    }
}

expect fun createDataStoreInstance(): DataStore<Preferences>