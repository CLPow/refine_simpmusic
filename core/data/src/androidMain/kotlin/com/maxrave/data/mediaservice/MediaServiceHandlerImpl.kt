package com.maxrave.data.mediaservice

import com.maxrave.domain.audio.TrackInfo
import com.maxrave.domain.mediaservice.handler.playerErrorAction
import com.maxrave.domain.mediaservice.handler.WIFI_ONLY_ERROR_CODE_NAME
import com.maxrave.domain.mediaservice.handler.PlayerErrorAction
import kotlinx.coroutines.async
import com.maxrave.data.platform.hasValidatedInternet
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import com.maxrave.common.ASC
import com.maxrave.common.CUSTOM_ORDER
import com.maxrave.common.Config.ALBUM_CLICK
import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.RADIO_CLICK
import com.maxrave.common.Config.RECOVER_TRACK_QUEUE
import com.maxrave.common.Config.SHARE
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.common.Config.VIDEO_CLICK
import com.maxrave.common.DESC
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.LOCAL_PLAYLIST_ID_SAVED_QUEUE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.TITLE
import com.maxrave.data.db.Converters
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.extension.toSongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.firstOrNull

private val TAG = "Media3ServiceHandlerImpl"

internal class MediaServiceHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Context = getKoin().get()
    override val player: MediaPlayerInterface = getKoin().get()


    /**
     * Built here rather than injected: it needs nothing this handler does not already hold, and
     * threading it through [createMediaServiceHandler] would mean changing that expect signature
     * and all three actuals for one dependency.
     */
    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(player.currentMediaItem)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData =
        MutableStateFlow<QueueData>(
            QueueData(
                queueState = QueueData.StateSource.STATE_CREATED,
                data = QueueData.Data(),
            ),
        )
    override val queueData = _queueData.asStateFlow()

    private val _controlState =
        MutableStateFlow<ControlState>(
            ControlState(
                isPlaying = player.isPlaying,
                isShuffle = player.shuffleModeEnabled,
                repeatState =
                    when (player.repeatMode) {
                        PlayerConstants.REPEAT_MODE_ONE -> {
                            RepeatState.One
                        }

                        PlayerConstants.REPEAT_MODE_ALL -> {
                            RepeatState.All
                        }

                        PlayerConstants.REPEAT_MODE_OFF -> {
                            RepeatState.None
                        }

                        else -> {
                            RepeatState.None
                        }
                    },
                isLiked = false,
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
                isCrossfading = false,
                volume = 1f,
            ),
        )

    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingTrackState>(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState(false, 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // SponsorBlock skip segments
    private val _skipSegments: MutableStateFlow<List<SponsorSkipSegments>?> = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex: MutableStateFlow<Int> = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState.NOT_CASTING)
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    // List of Specific variables


    private var skipSilent = false


    private val playbackReporter = PlaybackReporter(streamRepository, dataStoreManager, coroutineScope)

    /** Read on every track change, so kept warm here instead of a blocking DataStore read each time. */
    private val endlessQueueOn: StateFlow<Boolean> =
        dataStoreManager.endlessQueue
            .map { it == TRUE }
            .stateIn(coroutineScope, SharingStarted.Eagerly, false)


    /**
     * Consecutive tracks skipped as unavailable. YouTube Music moves on from a track that will
     * not play instead of stopping the queue; the cap stops a dead connection from skipping
     * through everything. Reset once anything plays.
     */
    private var unavailableSkips = 0

    private fun startReporting(videoId: String) {
        playbackReporter.start(
            videoId = videoId,
            playlistId = queueData.value.data.playlistId,
            positionMs = { player.currentPosition },
            durationMs = { player.duration },
            isPlaying = { player.isPlaying },
        )
    }

    private var volumeNormalizationJob: Job? = null

    private var sleepTimerJob: Job? = null

    /** How long the sleep timer spends ramping the volume down before it stops playback. */
    private val sleepFadeDurationMs = 5_000L

    /** Steps in that ramp — 50, matching the crossfade ramp, so 100ms per step at 5 seconds. */
    private val sleepFadeSteps = 50

    /**
     * Silence held after the ramp before playback is actually stopped.
     *
     * The gain is applied in the audio pipeline, ahead of the sink, so it reaches the speaker one
     * buffer late — AudioTrack alone holds 250–750 ms. Pausing the moment the ramp hits zero would
     * therefore cut while the audible level is still around -12 dBFS, which is exactly the abrupt
     * stop this feature exists to remove. Sized above the largest buffer media3 will allocate.
     */
    private val sleepFadeTailMs = 800L

    private var getSkipSegmentsJob: Job? = null

    private var getFormatJob: Job? = null

    private var progressJob: Job? = null

    private var bufferedJob: Job? = null

    private var updateNotificationJob: Job? = null

    private var toggleLikeJob: Job? = null

    private var loadJob: Job? = null
    private val restoreQueueMutex = Mutex()

    // Set by restoreQueueAndPlay before it contends for restoreQueueMutex, so a startup
    // restore already holding the lock can honour the play request itself instead of
    // finishing paused. One-way for the life of the process: mayBeRestoreQueue only ever
    // runs once, from init.
    @Volatile
    private var playRequestedDuringRestore = false

    // Set by restoreSavedQueue once the saved track is in the player - i.e. once a play request
    // can be honoured without the rest of the queue. Written after the addMediaItem that reads
    // [playRequestedDuringRestore] and re-read immediately afterwards, so the restore and a
    // resumption request racing it can never both miss the other's write: whichever runs second
    // sees the first, and exactly one of them starts playback.
    @Volatile
    private var restoreHasPrimedPlayer = false

    private var songEntityJob: Job? = null


    private var getDataOfNowPlayingTrackStateJob: Job? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private fun fromListIntToString(list: List<Int>?): String? = list?.let { json.encodeToString(list) }

    private fun fromStringToListInt(value: String?): List<Int>? =
        try {
            value?.let { json.decodeFromString<List<Int>>(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    //
    init {
        player.addListener(this)
        progressJob = Job()
        bufferedJob = Job()
        sleepTimerJob = Job()
        volumeNormalizationJob = Job()
        updateNotificationJob = Job()
        toggleLikeJob = Job()
        loadJob = Job()
        songEntityJob = Job()
        getSkipSegmentsJob = Job()
        getFormatJob = Job()
        skipSilent = runBlocking { dataStoreManager.skipSilent.first() == TRUE }
        // Collected, not read once: the Sound page changes these while music plays.
        backgroundScope.launch {
            dataStoreManager.enhanceSettings.distinctUntilChanged().collect { player.setEnhance(it) }
        }
        _nowPlaying.value = player.currentMediaItem
        if (runBlocking { dataStoreManager.saveStateOfPlayback.first() } == TRUE) {
            Logger.d(TAG, "SaveStateOfPlayback TRUE")
            val shuffleKey = runBlocking { dataStoreManager.shuffleKey.first() }
            val repeatKey = runBlocking { dataStoreManager.repeatKey.first() }
            Logger.d(TAG, "Shuffle: $shuffleKey")
            Logger.d(TAG, "Repeat: $repeatKey")
            val restoredShuffle = shuffleKey == TRUE
            val restoredRepeatMode =
                when (repeatKey) {
                    DataStoreManager.REPEAT_ONE -> PlayerConstants.REPEAT_MODE_ONE
                    DataStoreManager.REPEAT_ALL -> PlayerConstants.REPEAT_MODE_ALL
                    else -> PlayerConstants.REPEAT_MODE_OFF
                }
            player.shuffleModeEnabled = restoredShuffle
            player.repeatMode = restoredRepeatMode
            // Ensure controlState is in sync after restore, regardless of listener callbacks
            _controlState.value =
                _controlState.value.copy(
                    isShuffle = restoredShuffle,
                    repeatState =
                        when (restoredRepeatMode) {
                            PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
                            PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
                            else -> RepeatState.None
                        },
                )
        }
        mayBeRestoreQueue()
        coroutineScope.launch {
            val controlStateJob =
                launch {
                    controlState.collectLatest {
                        updateNotification()
                    }
                }
            val skipSegmentsJob =
                launch {
                    simpleMediaState
                        .filter { it is SimpleMediaState.Progress }
                        .map {
                            val current = (it as SimpleMediaState.Progress).progress
                            val duration = player.duration
                            if (duration > 0L) {
                                (current.toFloat() / player.duration) * 100
                            } else {
                                -1f
                            }
                        }.filter { it >= 0f }
                        .distinctUntilChanged()
                        .collect { current ->
                            if (dataStoreManager.sponsorBlockEnabled.first() == TRUE) {
                                if (player.duration > 0L) {
                                    val skipSegments = skipSegments.value
                                    val listCategory = dataStoreManager.getSponsorBlockCategories()
                                    if (skipSegments != null) {
                                        for (skip in skipSegments) {
                                            if (listCategory.contains(skip.category)) {
                                                val firstPart = ((skip.segment[0] / skip.videoDuration) * 100).toFloat()
                                                val secondPart =
                                                    ((skip.segment[1] / skip.videoDuration) * 100).toFloat()
                                                if (current in firstPart..secondPart) {
                                                    Logger.w(TAG, "Seek to $secondPart")
                                                    Logger.d(TAG, "Seek to Cr: $current, First: $firstPart, Second: $secondPart")
                                                    skipSegment((secondPart * player.duration).toLong() / 100)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            val playbackSpeedPitchJob =
                launch {
                    combine(dataStoreManager.playbackSpeed, dataStoreManager.pitch) { speed, pitch ->
                        Pair(speed, pitch)
                    }.collectLatest { pair ->
                        Logger.w(TAG, "Playback speed: ${pair.first}, Pitch: ${pair.second}")
                        player.playbackParameters =
                            GenericPlaybackParameters(
                                pair.first,
                                2f.pow(pair.second.toFloat() / 12),
                            )
                        Logger.w(TAG, "Playback current speed: ${player.playbackParameters.speed}, Pitch: ${player.playbackParameters.pitch}")
                    }
                }
            controlStateJob.join()
            skipSegmentsJob.join()
            playbackSpeedPitchJob.join()
        }
    }

    private fun getDataOfNowPlayingState(mediaItem: GenericMediaItem) {
        val videoId =
            if (mediaItem.isVideo()) {
                mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            } else {
                mediaItem.mediaId
            }
        val track =
            queueData.value.data.listTracks
                .find { it.videoId == videoId }
        _nowPlayingState.update {
            it.copy(
                mediaItem = mediaItem,
                track = track,
            )
        }
        _format.value = null
        _skipSegments.value = null
        // Reports this listen to the account (watch history -> recommendations). Its own job, so
        // a cancelled metadata lookup below never takes the report down with it.
        startReporting(videoId)
        getDataOfNowPlayingTrackStateJob?.cancel()
        getDataOfNowPlayingTrackStateJob =
            coroutineScope.launch {
                Logger.w(TAG, "getDataOfNowPlayingState: $videoId")
                Logger.w(TAG, "getDataOfNowPlayingState: ${track?.thumbnails}")
                songRepository.getSongById(videoId).cancellable().singleOrNull().let { songEntity ->
                    if (songEntity != null) {
                        _controlState.update { it.copy(isLiked = songEntity.liked) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: songEntity.thumbnails
                                ?: "https://i.ytimg.com/vi/${songEntity.videoId}/maxresdefault.jpg"
                        Logger.w(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        Logger.w(TAG, "getDataOfNowPlayingState: $thumbUrl")
                        if (songEntity.thumbnails != thumbUrl) {
                            songRepository.updateThumbnailsSongEntity(thumbUrl, songEntity.videoId).singleOrNull()?.let {
                                Logger.w(TAG, "getDataOfNowPlayingState: Updated thumbs $it")
                            }
                        }
                        // Rows written before the parsers carried YouTube's real MUSIC_VIDEO_TYPE_*
                        // hold an invented label ("Song", "video", a view count). They are corrected
                        // here as the user plays them rather than by a migration. normalize() drops
                        // anything that is not a real type, so an unknown never overwrites a known one.
                        MusicVideoType.normalize(track?.videoType)?.let { freshVideoType ->
                            if (songEntity.videoType != freshVideoType) {
                                songRepository.updateVideoTypeSongEntity(freshVideoType, songEntity.videoId).singleOrNull()?.let {
                                    Logger.w(TAG, "getDataOfNowPlayingState: Updated videoType $it")
                                }
                            }
                        }
                        songRepository.updateSongInLibrary(now(), songEntity.videoId).singleOrNull().let {
                            Logger.w(TAG, "getDataOfNowPlayingState: $it")
                        }
                        songRepository.updateListenCount(songEntity.videoId)
                        Logger.w(TAG, "getDataOfNowPlayingState: $songEntity")
                        Logger.w(TAG, "getDataOfNowPlayingState: $track")
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    songEntity.copy(
                                        thumbnails = thumbUrl,
                                    ),
                            )
                        }
                    } else {
                        _controlState.update { it.copy(isLiked = false) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: "https://i.ytimg.com/vi/${track?.videoId}/maxresdefault.jpg"
                        Logger.w(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        val songEntity =
                            (track?.toSongEntity() ?: mediaItem.toSongEntity()).copy(
                                thumbnails = thumbUrl,
                            )
                        songRepository
                            .insertSong(
                                songEntity,
                            ).singleOrNull()
                            ?.let {
                                Logger.w(TAG, "getDataOfNowPlayingState: $it")
                            }
                        Logger.w(TAG, "getDataOfNowPlayingState: $songEntity")
                        _nowPlayingState.update {
                            it.copy(
                                songEntity = songEntity,
                            )
                        }
                    }
                    Logger.w(TAG, "getDataOfNowPlayingState: ${nowPlayingState.value}")
                }
                songEntityJob?.cancel()
                songEntityJob =
                    coroutineScope.launch {
                        songRepository.getSongAsFlow(videoId).cancellable().filterNotNull().collectLatest { songEntity ->
                            if (dataStoreManager.explicitContentEnabled.first() == FALSE && songEntity.isExplicit) {
                                showToast(ToastType.ExplicitContent)
                                if (player.hasNextMediaItem()) {
                                    player.seekToNext()
                                } else if (player.hasPreviousMediaItem()) {
                                    player.seekToPrevious()
                                } else {
                                    player.stop()
                                }
                                return@collectLatest
                            }
                            _nowPlayingState.update {
                                it.copy(
                                    songEntity = songEntity,
                                )
                            }
                            _controlState.update {
                                it.copy(
                                    isLiked = songEntity.liked,
                                )
                            }
                        }
                    }
                if (dataStoreManager.sponsorBlockEnabled.first() == TRUE) {
                    getSkipSegments(videoId)
                }
                getFormat(videoId)
            }
    }

    private fun getSkipSegments(videoId: String) {
        _skipSegments.value = null
        coroutineScope.launch {
            streamRepository.getSkipSegments(videoId).collect { response ->
                when (response) {
                    is Resource.Success -> {
                        _skipSegments.value = response.data
                    }

                    is Resource.Error -> {
                        Logger.e(TAG, "getSkipSegments: ${response.message}")
                        _skipSegments.value = null
                    }
                }
            }
        }
    }

    private fun getFormat(mediaId: String?) {
        getFormatJob?.cancel()
        getFormatJob =
            coroutineScope.launch {
                if (mediaId != null) {
                    streamRepository.getFormatFlow(mediaId).cancellable().collectLatest { f ->
                        Logger.w(TAG, "Get format for $mediaId: $f")
                        if (f != null) {
                            _format.emit(f)
                        } else {
                            _format.emit(null)
                        }
                    }
                }
            }
    }

    private fun updateNextPreviousTrackAvailability() {
        _controlState.value =
            _controlState.value.copy(
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
            )
    }

    private fun addMediaItemNotSet(
        mediaItem: GenericMediaItem,
        index: Int? = null,
    ) {
        index?.let {
            player.addMediaItem(it, mediaItem)
        } ?: player.addMediaItem(mediaItem)
        if (player.mediaItemCount == 1) {
            player.prepare()
            player.playWhenReady = true
        }
        updateNextPreviousTrackAvailability()
    }

    private fun moveMediaItem(
        fromIndex: Int,
        newIndex: Int,
    ) {
        player.moveMediaItem(fromIndex, newIndex)
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    private fun skipSegment(position: Long) {
        if (position in 0..player.duration) {
            player.seekTo(position)
        } else if (position > player.duration) {
            player.seekToNext()
        }
    }

    @SuppressLint("PrivateResource")
    private fun updateNotification() {
        updateNotificationJob?.cancel()
        updateNotificationJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                val liked =
                    songRepository
                        .getSongById(id)
                        .singleOrNull()
                        ?.liked ?: false
                Logger.w("Check liked", liked.toString())
                _controlState.value = _controlState.value.copy(isLiked = liked)
                onUpdateNotification.invoke(
                    listOf(
                        GenericCommandButton.Like(liked),
                        GenericCommandButton.Shuffle(isShuffled = _controlState.value.isShuffle),
                        GenericCommandButton.Repeat(repeatState = _controlState.value.repeatState),
                        GenericCommandButton.Radio,
                    ),
                )
            }
    }

    // Region: Override functions
    override fun startProgressUpdate() {
        // Cancel any previous loop first: onIsPlayingChanged(true) can fire repeatedly
        // (crossfade swap, rebuffer→ready, resume-on-focus-gain) and a leaked loop would
        // otherwise multiply both the UI updates and the periodic position writes (#2152).
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                // Persist the playback position to DataStore on this interval so a sudden
                // service/process kill while playing in the background (aggressive OEMs)
                // still restores the correct position instead of restarting the track from
                // the beginning (#2152). The position is otherwise only saved on pause /
                // track change / release, which misses uninterrupted background playback.
                val positionPersistIntervalMs = 5_000L
                var sinceLastPositionSaveMs = 0L
                while (true) {
                    delay(100)
                    _simpleMediaState.value = SimpleMediaState.Progress(player.currentPosition)
                    sinceLastPositionSaveMs += 100
                    if (sinceLastPositionSaveMs >= positionPersistIntervalMs) {
                        sinceLastPositionSaveMs = 0
                        mayBeSaveRecentPosition()
                        // Riding the existing 5s tick instead of adding one: the scrobble point is
                        // half the track or four minutes, so five seconds of granularity is plenty
                        // and the 100ms loop stays as cheap as it was.
                    }
                }
            }
    }

    override fun startBufferedUpdate() {
        // Same reason as startProgressUpdate above: this is reached once per track load and once
        // per stall, and stopBufferedUpdate only cancels the newest job — so every earlier loop
        // survives and keeps pushing Loading every 500 ms for the rest of the session.
        bufferedJob?.cancel()
        bufferedJob =
            coroutineScope.launch {
                while (true) {
                    delay(500)
                    _simpleMediaState.value =
                        SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
                    val current = nowPlayingState.value.songEntity
                    if (current?.durationSeconds == 0 && player.duration > 0L) {
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    current.copy(
                                        durationSeconds = (player.duration / 1000).toInt(),
                                    ),
                            )
                        }
                    }
                }
            }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
        Logger.w(TAG, "stopProgressUpdate: ${progressJob?.isActive}")
    }

    override fun stopBufferedUpdate() {
        bufferedJob?.cancel()
        // Deliberately emits nothing: this runs when buffering *ends*, so publishing Loading here
        // said the opposite of what happened.
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            is PlayerEvent.UpdateVolume -> {}

            PlayerEvent.Backward -> {
                player.seekBack()
            }

            PlayerEvent.Forward -> {
                player.seekForward()
            }

            PlayerEvent.PlayPause -> {
                if (player.isPlaying) {
                    player.pause()
                    stopProgressUpdate()
                } else {
                    player.play()
                    startProgressUpdate()
                }
            }

            PlayerEvent.Next -> {
                resetCrossfade()
                player.seekToNext()
            }

            PlayerEvent.Previous -> {
                resetCrossfade()
                player.seekToPrevious()
            }

            PlayerEvent.SkipToPrevious -> {
                resetCrossfade()
                player.seekToPreviousMediaItem()
            }

            PlayerEvent.Stop -> {
                stopProgressUpdate()
                player.stop()
                _nowPlayingState.value = NowPlayingTrackState.initial()
            }

            is PlayerEvent.UpdateProgress -> {
                // Unknown length (still loading, a live stream): nowhere to seek to yet.
                if (player.duration > 0) {
                    player.seekTo((player.duration * playerEvent.newProgress.coerceIn(0f, 100f) / 100).toLong())
                }
            }

            PlayerEvent.Shuffle -> {
                if (player.shuffleModeEnabled) {
                    player.shuffleModeEnabled = false
                    _controlState.value = _controlState.value.copy(isShuffle = false)
                } else {
                    player.shuffleModeEnabled = true
                    _controlState.value = _controlState.value.copy(isShuffle = true)
                }
            }

            PlayerEvent.Repeat -> {
                when (player.repeatMode) {
                    PlayerConstants.REPEAT_MODE_OFF -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                    }

                    PlayerConstants.REPEAT_MODE_ONE -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_OFF
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.None)
                    }

                    PlayerConstants.REPEAT_MODE_ALL -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                    }

                    else -> {
                        when (controlState.first().repeatState) {
                            RepeatState.None -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.One -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.All -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                            }
                        }
                    }
                }
            }

            PlayerEvent.ToggleLike -> {
                toggleLike()
            }
        }
    }

    override fun toggleRadio() {
        coroutineScope.launch {
            val currentSong = nowPlayingState.value.songEntity ?: return@launch
            Logger.d(TAG, "toggleRadio: ${currentSong.title}")
            songRepository
                .getRadioFromEndpoint(
                    YouTubeWatchEndpoint(
                        videoId = currentSong.videoId,
                        playlistId = "RDAMVM${currentSong.videoId}",
                    ),
                ).collectLatest { res ->
                    val data = res.data
                    when (res) {
                        is Resource.Success if (data != null && data.first.isNotEmpty()) -> {
                            setQueueData(
                                QueueData.Data(
                                    listTracks = data.first,
                                    firstPlayedTrack = data.first.first(),
                                    playlistId = "RDAMVM${currentSong.videoId}",
                                    playlistName = "\"${currentSong.title}\" Radio",
                                    playlistType = PlaylistType.RADIO,
                                    continuation = data.second,
                                ),
                            )
                            clearMediaItems()
                            currentSong.durationSeconds.let {
                                songRepository.updateDurationSeconds(it, currentSong.videoId)
                            }
                            addMediaItem(currentSong.toGenericMediaItem(), playWhenReady = true)
                            loadPlaylistOrAlbum(0)
                        }

                        else -> {
                            Logger.e(TAG, "toggleRadio: ${res.message}")
                        }
                    }
                }
        }
    }

    override fun toggleLike() {
        Logger.w(TAG, "toggleLike: ${nowPlayingState.value.mediaItem.mediaId}")
        toggleLikeJob?.cancel()
        toggleLikeJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                val liking = !(controlState.first().isLiked)
                songRepository.updateLikeStatus(id, if (liking) 1 else 0)
                // YouTube Music's thumbs up is the account's like: it lands in Liked music and
                // teaches recommendations. Signed out, the like stays on this device.
                if (dataStoreManager.cookie.first().isNotEmpty()) {
                    val code =
                        if (liking) {
                            songRepository.addToYouTubeLiked(id).firstOrNull()
                        } else {
                            songRepository.removeFromYouTubeLiked(id).firstOrNull()
                        }
                    if (code !in 200..299) Logger.w(TAG, "YouTube ${if (liking) "like" else "unlike"} of $id answered $code")
                }
                delay(200)
            }
    }

    override fun dislike() {
        val id = player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO) ?: return
        val wasLiked = controlState.value.isLiked
        coroutineScope.launch {
            // As in YouTube Music, a thumbs down also moves on from the song.
            if (player.hasNextMediaItem()) onPlayerEvent(PlayerEvent.Next)
            if (wasLiked) songRepository.updateLikeStatus(id, 0)
            if (dataStoreManager.cookie.first().isNotEmpty()) {
                val code = songRepository.dislikeOnYouTube(id).firstOrNull()
                if (code !in 200..299) Logger.w(TAG, "YouTube dislike of $id answered $code")
            }
        }
    }

    override fun like(liked: Boolean) {
        _controlState.value = _controlState.value.copy(isLiked = liked)
    }

    override fun resetSongAndQueue() {
        player.clearMediaItems()
        _queueData.value = QueueData()
    }

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob =
            coroutineScope.launch(Dispatchers.Main) {
                var stoppedPlayback = false
                try {
                    if (minutes == Int.MAX_VALUE) {
                        // "End of current song" mode: use sentinel -1 to indicate this special state
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = -1)
                        }
                        // Poll until player duration is available (may be -1 initially)
                        var duration = player.duration
                        while (duration <= 0L) {
                            delay(500)
                            duration = player.duration
                        }
                        val remaining = (duration - player.currentPosition).coerceAtLeast(0L)
                        // Fade over the tail of the track rather than after it, so the song is
                        // already silent by the time it ends. A track with less time left than the
                        // fade gets a shorter one instead of bleeding into whatever plays next.
                        // Fade and tail together must fit inside what is left, or the timer would
                        // run past the end of the track and pause somewhere inside the next one.
                        val fadeMs = sleepFadeDurationMs.coerceAtMost(remaining)
                        val tailMs = sleepFadeTailMs.coerceAtMost(remaining - fadeMs)
                        delay(remaining - fadeMs - tailMs)
                        fadeOutForSleep(fadeMs)
                        delay(tailMs)
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    } else {
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = minutes)
                        }
                        var count = minutes
                        while (count > 0) {
                            // The fade belongs inside the final minute, so shorten that wait by its length.
                            val isFinalMinute = count == 1
                            delay(
                                if (isFinalMinute) 60 * 1000L - sleepFadeDurationMs - sleepFadeTailMs else 60 * 1000L,
                            )
                            if (isFinalMinute) {
                                fadeOutForSleep(sleepFadeDurationMs)
                                delay(sleepFadeTailMs)
                            }
                            count--
                            // Logged per tick so an early stop can be attributed. If playback ends
                            // while these are still counting down, the timer did not do it -- on
                            // Android the usual culprit is the OS killing the playback service, and
                            // without this there is no way to tell that from a timer that fired early.
                            Logger.i(TAG, "Sleep timer: $count of $minutes minutes remaining")
                            _sleepTimerState.update {
                                it.copy(isDone = false, timeRemaining = count)
                            }
                        }
                        Logger.i(TAG, "Sleep timer elapsed after $minutes minutes; pausing")
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    }
                } finally {
                    // Only the cancelled path clears the attenuation here — sleepStop(), or the
                    // scope going away mid-fade. When the timer runs to completion the adapter
                    // clears it instead, from inside the pause it queued, because pause() is
                    // asynchronous and this coroutine cannot tell when playback actually stopped.
                    // Restoring it from here would lift the volume back over the last of the audio.
                    if (!stoppedPlayback) player.sleepFadeFactor = 1f
                }
            }
    }

    /**
     * Ramps [player]'s sleep-fade attenuation down to silence over [durationMs].
     *
     * Uses the same equal-power (cosine) curve as the crossfade ramp: loudness is perceived
     * logarithmically, so a linear ramp sounds like it drops away early and then lingers near the
     * bottom. Leaves the factor at zero: the caller holds that silence for [sleepFadeTailMs] so the
     * attenuated audio can drain through the sink, and only restores the factor after pausing.
     */
    private suspend fun fadeOutForSleep(durationMs: Long) {
        if (durationMs <= 0L) return
        // Fewer steps than the nominal 50 for a very short fade, so the ramp cannot outlast the
        // budget it was given: 50 steps at the 1ms floor would take 50ms regardless of duration.
        val steps = sleepFadeSteps.toLong().coerceAtMost(durationMs).toInt()
        val delayPerStep = (durationMs / steps).coerceAtLeast(1L)
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            player.sleepFadeFactor = cos(progress * PI / 2).toFloat()
            delay(delayPerStep)
        }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(false, 0)
    }

    override fun removeMediaItem(position: Int) {
        val listTracks = _queueData.value.data.listTracks
        // [position] is a place in the queue as shown (play order). Out of range means the queue
        // changed under the caller; removing anyway crashed or took the wrong song.
        if (position !in listTracks.indices) {
            Logger.w(TAG, "removeMediaItem: $position is outside the queue (${listTracks.size})")
            return
        }
        // The player keeps its items in their original order; while shuffling, the shown order
        // is the shuffle order, so the place has to be mapped back.
        val playerIndex = if (player.shuffleModeEnabled) player.getUnshuffledIndex(position) else position
        if (playerIndex in 0 until player.mediaItemCount) player.removeMediaItem(playerIndex)
        val temp = listTracks.toMutableList()
        temp.removeAt(position)
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = temp,
                    ).afterRemove(playerIndex),
            )
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        for (mediaItem in mediaItemList) {
            addMediaItemNotSet(mediaItem)
        }
    }

    override fun playMediaItemInMediaSource(index: Int) {
        val i = if (player.shuffleModeEnabled) player.getUnshuffledIndex(index) else index
        player.seekTo(i, 0)
        player.prepare()
        player.playWhenReady = true
    }

    override fun currentSongIndex(): Int = player.currentMediaItemIndex

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
//        if (from < to) {
//            for (i in from until to) {
//                moveItemDown(i)
//            }
//        } else {
//            for (i in from downTo to + 1) {
//                moveItemUp(i)
//            }
//        }
        moveMediaItem(from, to)
    }

    override fun resetCrossfade() {
        _controlState.update {
            it.copy(
                isCrossfading = false,
            )
        }
    }

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val playlistId = _queueData.value.data.playlistId ?: return
        val firstPlayedTrack = _queueData.value.data.firstPlayedTrack ?: return
        coroutineScope.launch {
            if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                songRepository.insertSong(firstPlayedTrack.toSongEntity()).collect {
                    Logger.w(TAG, "Inserted song: ${firstPlayedTrack.title}")
                }
                clearMediaItems()
                firstPlayedTrack.durationSeconds?.let {
                    songRepository.updateDurationSeconds(it, firstPlayedTrack.videoId)
                }
                addMediaItem(firstPlayedTrack.toGenericMediaItem(), playWhenReady = true)
                val longId = playlistId.replace(LOCAL_PLAYLIST_ID, "").toLong()
                val localPlaylist = localPlaylistRepository.getLocalPlaylist(longId).lastOrNull()?.data
                if (localPlaylist != null) {
                    Logger.w(TAG, "shufflePlaylist: Local playlist track size ${localPlaylist.tracks?.size}")
                    val trackCount = localPlaylist.tracks?.size ?: return@launch
                    val listPosition =
                        (0 until trackCount).toMutableList().apply {
                            remove(randomTrackIndex)
                        }
                    if (listPosition.isEmpty()) return@launch
                    listPosition.shuffle()
                    _queueData.update {
                        it.copy(
                            // After shuffle prefix is offset and list position
                            data =
                                it.data.copy(
                                    continuation = "SHUFFLE0_${fromListIntToString(listPosition)}",
                                ),
                        )
                    }
                    loadMore()
                }
            }
        }
    }

    override fun loadMore() {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        // Separate local and remote data
        // Local Add Prefix to PlaylistID to differentiate between local and remote
        // Local: LC-PlaylistID
        // A queue without a playlist id ("Play all", a single from a shelf) still reaches Autoplay below.
        val playlistId = _queueData.value.data.playlistId ?: ""
        Logger.w("Check loadMore", playlistId.toString())
        val continuation = _queueData.value.data.continuation
        Logger.w("Check loadMore", continuation.toString())
        if (continuation != null) {
            if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    val longId =
                        try {
                            playlistId.replace(LOCAL_PLAYLIST_ID, "").toLong()
                        } catch (e: NumberFormatException) {
                            return@launch
                        }
                    Logger.w("Check loadMore", longId.toString())
                    if (continuation.startsWith("SHUFFLE")) {
                        val regex = Regex("(?<=SHUFFLE)\\d+(?=_)")
                        var offset = regex.find(continuation)?.value?.toInt() ?: return@launch
                        val posString = continuation.removePrefix("SHUFFLE${offset}_")
                        val listPosition = fromStringToListInt(posString) ?: return@launch
                        val theLastLoad = 50 * (offset + 1) >= listPosition.size
                        localPlaylistRepository
                            .getPlaylistPairSongByListPosition(
                                longId,
                                listPosition.subList(50 * offset, if (theLastLoad) listPosition.size else 50 * (offset + 1)),
                            ).singleOrNull()
                            ?.let { pair ->
                                Logger.w("Check loadMore response", pair.size.toString())
                                songRepository.getSongsByListVideoId(pair.map { it.songId }).lastOrNull()?.let { songs ->
                                    if (songs.isNotEmpty()) {
                                        delay(300)
                                        loadMoreCatalog(songs.toArrayListTrack())
                                        offset++
                                        _queueData.update {
                                            it.copy(
                                                data =
                                                    it.data.copy(
                                                        continuation =
                                                            if (!theLastLoad) {
                                                                "SHUFFLE${offset}_$posString"
                                                            } else {
                                                                null
                                                            },
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                    } else if (
                        continuation.startsWith(ASC) ||
                        continuation.startsWith(DESC) ||
                        continuation.startsWith(CUSTOM_ORDER) ||
                        continuation.startsWith(TITLE)
                    ) {
                        val filter =
                            if (continuation.startsWith(ASC)) {
                                FilterState.OlderFirst
                            } else if (continuation.startsWith(DESC)) {
                                FilterState.NewerFirst
                            } else if (continuation.startsWith(CUSTOM_ORDER)) {
                                FilterState.CustomOrder
                            } else {
                                FilterState.Title
                            }
                        val converters = Converters()

                        when (filter) {
                            FilterState.NewerFirst, FilterState.OlderFirst -> {
                                val localDateTime =
                                    try {
                                        val timestampString =
                                            if (filter == FilterState.OlderFirst) {
                                                continuation.removePrefix(ASC)
                                            } else {
                                                continuation.removePrefix(DESC)
                                            }
                                        val timestamp = timestampString.toLong()
                                        converters.fromTimestamp(timestamp)
                                            ?: return@launch
                                    } catch (e: Exception) {
                                        Logger.e(TAG, "loadMore: Failed to parse timestamp", e)
                                        return@launch
                                    }
                                localPlaylistRepository
                                    .getPlaylistPairSongByTime(
                                        longId,
                                        filter,
                                        localDateTime,
                                    ).lastOrNull()
                                    ?.let { pair ->
                                        Logger.w("Check loadMore response", pair.size.toString())
                                        songRepository.getSongsByListVideoId(pair.map { it.songId }).single().let { songs ->
                                            if (songs.isNotEmpty()) {
                                                delay(300)
                                                loadMoreCatalog(songs.toArrayListTrack())
                                                _queueData.update {
                                                    it.copy(
                                                        data =
                                                            it.data.copy(
                                                                continuation =
                                                                    if (filter ==
                                                                        FilterState.OlderFirst
                                                                    ) {
                                                                        ASC +
                                                                            pair.lastOrNull()?.inPlaylist?.let { inPlaylist ->
                                                                                converters.dateToTimestamp(inPlaylist)
                                                                            }
                                                                    } else {
                                                                        DESC +
                                                                            pair.lastOrNull()?.inPlaylist?.let { inPlaylist ->
                                                                                converters.dateToTimestamp(inPlaylist)
                                                                            }
                                                                    },
                                                            ),
                                                    )
                                                }
                                            } else {
                                                _queueData.update {
                                                    it.copy(
                                                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                                                    )
                                                }
                                                reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                            }
                                        }
                                    }
                            }

                            FilterState.Title, FilterState.CustomOrder -> {
                                val offset =
                                    if (filter == FilterState.CustomOrder) {
                                        continuation.removePrefix(CUSTOM_ORDER).toInt()
                                    } else {
                                        continuation.removePrefix(TITLE).toInt()
                                    }
                                localPlaylistRepository
                                    .getPlaylistPairSongByOffset(
                                        longId,
                                        offset,
                                        filter,
                                    ).lastOrNull()
                                    ?.let { pair ->
                                        Logger.w("Check loadMore response", pair.size.toString())
                                        songRepository.getSongsByListVideoId(pair.map { it.songId }).single().let { songs ->
                                            if (songs.isNotEmpty()) {
                                                delay(300)
                                                loadMoreCatalog(songs.toArrayListTrack())
                                                _queueData.update {
                                                    it.copy(
                                                        data =
                                                            it.data.copy(
                                                                continuation =
                                                                    if (filter ==
                                                                        FilterState.CustomOrder
                                                                    ) {
                                                                        CUSTOM_ORDER + (offset + 1)
                                                                    } else {
                                                                        TITLE + (offset + 1).toString()
                                                                    },
                                                            ),
                                                    )
                                                }
                                            } else {
                                                _queueData.update {
                                                    it.copy(
                                                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                                                    )
                                                }
                                                reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            } else {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    Logger.w(TAG, "Check loadMore continuation $continuation")
                    songRepository
                        .getContinueTrack(playlistId, continuation)
                        .lastOrNull()
                        .let { response ->
                            val list = response?.first
                            if (list != null) {
                                Logger.w(TAG, "Check loadMore response $response")
                                loadMoreCatalog(list)
                                _queueData.update {
                                    it.copy(
                                        data =
                                            it.data.copy(
                                                continuation = response.second,
                                            ),
                                    )
                                }
                            } else {
                                _queueData.update {
                                    it.copy(
                                        data =
                                            it.data.copy(
                                                continuation = null,
                                            ),
                                    )
                                }
                                if (endlessQueueOn.value) {
                                    Logger.w(TAG, "loadMore: Endless Queue")
                                    val lastTrack =
                                        queueData.value.data.listTracks
                                            .lastOrNull() ?: return@launch
                                    val radioId = "RDAMVM${lastTrack.videoId}"
                                    if (radioId == queueData.value.data.playlistId) {
                                        Logger.w(TAG, "loadMore: Already in radio mode")
                                        return@launch
                                    }
                                    _queueData.update {
                                        it.copy(
                                            data =
                                                it.data.copy(
                                                    playlistId = radioId,
                                                ).withAutoplayStart(),
                                            queueState = QueueData.StateSource.STATE_INITIALIZED,
                                        )
                                    }
                                    reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                    Logger.d("Check loadMore", "queueData: ${queueData.value}")
                                    getRelated(lastTrack.videoId)
                                }
                            }
                        }
                }
            }
        } else if (endlessQueueOn.value) {
            Logger.w(TAG, "loadMore: Endless Queue")
            val lastTrack =
                queueData.value.data.listTracks
                    .lastOrNull() ?: return
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                    data =
                        it.data.copy(
                            playlistId = "RDAMVM${lastTrack.videoId}",
                        ).withAutoplayStart(),
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
            Logger.d("Check loadMore", "queueData: ${queueData.value}")
            getRelated(lastTrack.videoId)
        }
    }

    override fun getRelated(videoId: String) {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        coroutineScope.launch {
            songRepository.getRelatedData(videoId).collect { response ->
                when (response) {
                    is Resource.Success -> {
                        loadMoreCatalog(response.data?.first?.toCollection(arrayListOf()) ?: arrayListOf())
                        _queueData.update {
                            it.copy(
                                data =
                                    it.data.copy(
                                        continuation = response.data?.second,
                                    ),
                            )
                        }
                    }

                    is Resource.Error -> {
                        Logger.d("Check Related", "getRelated: ${response.message}")
                        _queueData.update {
                            it.copy(
                                queueState = QueueData.StateSource.STATE_INITIALIZED,
                                data =
                                    it.data.copy(
                                        continuation = null,
                                    ),
                            )
                        }
                        reorderShuffledQueue(player.getCurrentMediaTimeLine())
                    }
                }
            }
        }
    }

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.update {
            it.copy(
                data = queueData,
            )
        }
        // Snapshot which tracks came from the album, for the crossfade rule. Taken at load time
        // because endless queue appends to this same queue afterwards, and those additions are not
        // album tracks — that boundary is exactly where crossfade should resume.
        player.albumTrackIds =
            if (queueData.playlistType == PlaylistType.ALBUM) {
                queueData.listTracks.map { it.videoId }.toSet()
            } else {
                emptySet()
            }
        Logger.w(TAG, "setQueueData: $queueData")
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) {
        moveMediaItem(position, position - 1)
        queueData.value.data.listTracks.toMutableList().let { list ->
            val temp = list[position]
            list[position] = list[position - 1]
            list[position - 1] = temp
            _queueData.update {
                it.copy(
                    data = it.data.copy(listTracks = list),
                )
            }
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override suspend fun moveItemDown(position: Int) {
        moveMediaItem(position, position + 1)
        queueData.value.data.listTracks.toMutableList().let { list ->
            val temp = list[position]
            list[position] = list[position + 1]
            list[position + 1] = temp
            _queueData.update {
                it.copy(
                    data = it.data.copy(listTracks = list),
                )
            }
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (mediaItem != null) {
            Logger.d("MusicSource", "addFirstMediaItem: ${mediaItem.mediaId}")
            moveMediaItem(0, index)
        }
    }

    override fun reset() {
        _queueData.value = QueueData()
    }

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {
        updateCatalog(downloaded, index).let {
            if (index != 0 && index != null) {
                moveMediaItem(0, index)
            }
            updateNextPreviousTrackAvailability()
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
        }
    }

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {
        Logger.d("Queue", listTrack.map { it.title }.toString())
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val catalogMetadata: ArrayList<Track> = arrayListOf()
        for (i in 0 until listTrack.size) {
            val track = listTrack[i]
            var thumbUrl =
                track.thumbnails?.lastOrNull()?.url
                    ?: "https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
            thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
            val artistName: String = track.artists.toListName().connectArtists()
            val isSong =
                (
                    track.thumbnails?.lastOrNull()?.height != 0 &&
                        track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                        track.thumbnails?.lastOrNull()?.height != null
                ) &&
                    (
                        !thumbUrl
                            .contains("hq720") &&
                            !thumbUrl
                                .contains("maxresdefault") &&
                            !thumbUrl.contains("sddefault")
                    )
            if (track.artists.isNullOrEmpty()) {
                songRepository
                    .getSongInfo(track.videoId)
                    .lastOrNull()
                    .let { songInfo ->
                        if (songInfo != null) {
                            catalogMetadata.add(
                                track.copy(
                                    artists =
                                        listOf(
                                            Artist(
                                                songInfo.authorId,
                                                songInfo.author ?: "",
                                            ),
                                        ),
                                ),
                            )
                            addMediaItemNotSet(
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = songInfo.author ?: "",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = MERGING_DATA_TYPE.SONG,
                                        ),
                                    customCacheKey = track.videoId,
                                ),
                            )
                        } else {
                            val mediaItem =
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = "Various Artists",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = MERGING_DATA_TYPE.SONG,
                                        ),
                                    customCacheKey = track.videoId,
                                )
                            addMediaItemNotSet(mediaItem)
                            catalogMetadata.add(
                                track.copy(
                                    artists = listOf(Artist("", "Various Artists")),
                                ),
                            )
                        }
                    }
            } else {
                addMediaItemNotSet(
                    GenericMediaItem(
                        mediaId = track.videoId,
                        uri = track.videoId,
                        metadata =
                            GenericMediaMetadata(
                                title = track.title,
                                artist = artistName,
                                albumTitle = track.album?.name,
                                artworkUri = thumbUrl,
                                description = MERGING_DATA_TYPE.SONG,
                            ),
                        customCacheKey = track.videoId,
                    ),
                )
                catalogMetadata.add(track)
            }
            Logger.d(
                "MusicSource",
                "updateCatalog: ${track.title}, ${catalogMetadata.size}",
            )
            Logger.d("MusicSource", "updateCatalog: ${track.title}")
        }
        // Intent, not observed: isPlaying is also false while paused OR while the next track
        // is still preparing, so reading it here let a background queue append strip a live
        // play-intent mid-load and the incoming track came up silent.
        if (!player.playWhenReady && isAddToQueue) {
            player.playWhenReady = false
        }
        _queueData.update {
            it
                .copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                ).addTrackList(catalogMetadata)
        }
        reorderShuffledQueue(player.getCurrentMediaTimeLine())
    }

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean {
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val tempQueue: ArrayList<Track> = arrayListOf()
        tempQueue.addAll(queueData.value.data.listTracks)
        val chunkedList = tempQueue.chunked(100)
        // Reset queue
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = arrayListOf(),
                        autoplayStartIndex = null,
                    ),
            )
        }
        val current = if (index != null) tempQueue.getOrNull(index) else null
        chunkedList.forEach { list ->
            val catalogMetadata: ArrayList<Track> = arrayListOf()
            Logger.w("SimpleMediaServiceHandler", "Catalog size: ${tempQueue.size}")
            Logger.w("SimpleMediaServiceHandler", "Skip index: $index")
            for (i in list.indices) {
                val track = list[i]
                if (track == current) continue
                var thumbUrl =
                    track.thumbnails?.lastOrNull()?.url
                        ?: "https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
                thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                val isSong =
                    (
                        track.thumbnails?.lastOrNull()?.height != 0 &&
                            track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                            track.thumbnails?.lastOrNull()?.height != null
                    ) &&
                        (
                            !thumbUrl
                                .contains("hq720") &&
                                !thumbUrl
                                    .contains("maxresdefault") &&
                                !thumbUrl.contains("sddefault")
                        )
                if (downloaded == 1) {
                    if (track.artists.isNullOrEmpty()) {
                        songRepository.getSongInfo(track.videoId).lastOrNull().let { songInfo ->
                            if (songInfo != null) {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = songInfo.author ?: "",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = MERGING_DATA_TYPE.SONG,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists =
                                            listOf(
                                                Artist(
                                                    songInfo.authorId,
                                                    songInfo.author ?: "",
                                                ),
                                            ),
                                    ),
                                )
                            } else {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = "Various Artists",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = MERGING_DATA_TYPE.SONG,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists = listOf(Artist("", "Various Artists")),
                                    ),
                                )
                            }
                        }
                    } else {
                        val mediaItem =
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = track.artists.toListName().connectArtists(),
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = MERGING_DATA_TYPE.SONG,
                                    ),
                                customCacheKey = track.videoId,
                            )
                        addMediaItemNotSet(mediaItem)
                        catalogMetadata.add(track)
                    }
                    Logger.d("MusicSource", "updateCatalog: ${track.title}, ${catalogMetadata.size}")
                } else {
                    val artistName: String = track.artists.toListName().connectArtists()
                    if (track.artists.isNullOrEmpty()) {
                        songRepository
                            .getSongInfo(track.videoId)
                            .cancellable()
                            .lastOrNull()
                            .let { songInfo ->
                                if (songInfo != null) {
                                    catalogMetadata.add(
                                        track.copy(
                                            artists =
                                                listOf(
                                                    Artist(
                                                        songInfo.authorId,
                                                        songInfo.author ?: "",
                                                    ),
                                                ),
                                        ),
                                    )
                                    addMediaItemNotSet(
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = songInfo.author ?: "",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = MERGING_DATA_TYPE.SONG,
                                                ),
                                            customCacheKey = track.videoId,
                                        ),
                                    )
                                } else {
                                    val mediaItem =
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = "Various Artists",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = MERGING_DATA_TYPE.SONG,
                                                ),
                                            customCacheKey = track.videoId,
                                        )
                                    addMediaItemNotSet(mediaItem)
                                    catalogMetadata.add(
                                        track.copy(
                                            artists = listOf(Artist("", "Various Artists")),
                                        ),
                                    )
                                }
                            }
                    } else {
                        addMediaItemNotSet(
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = artistName,
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = MERGING_DATA_TYPE.SONG,
                                    ),
                                customCacheKey = track.videoId,
                            ),
                        )
                        catalogMetadata.add(track)
                    }
                    Logger.d(
                        "MusicSource",
                        "updateCatalog: ${track.title}, ${catalogMetadata.size}",
                    )
                    Logger.d("MusicSource", "updateCatalog: ${track.title}")
                }
            }
            _queueData.update {
                it.addTrackList(catalogMetadata)
            }
            delay(200)
        }
        if (current != null && index != null) {
            _queueData.update {
                it.addToIndex(current, index)
            }
        }
        Logger.w("SimpleMediaServiceHandler", "current queue: ${player.mediaItemCount}")
        return true
    }

    override fun addQueueToPlayer() {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load()
            }
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load(index = index)
            }
    }

    override fun currentOrderIndex(): Int =
        if (player.shuffleModeEnabled) {
            queueData.value.data.listTracks.indexOfLast {
                it.videoId == player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
            }
        } else {
            currentSongIndex()
        }

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
    }

    override suspend fun playNext(track: Track) {
        insertTracksAt(listOf(track), forQueue = false) { current -> current + 1 }
    }

    override suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        // While shuffling, list positions are the shuffle order and there is no fixed place to keep
        // apart; it appends, as before.
        if (player.shuffleModeEnabled) {
            loadMoreCatalog(ArrayList(tracks), isAddToQueue = true)
            return
        }
        insertTracksAt(tracks, forQueue = true) { current -> queueData.value.data.addToQueueIndex(current) }
    }

    /**
     * The one insert path for "Play next" and "Add to queue". Missing artists are looked up first;
     * the position is worked out only afterwards, from the queue as it is then (the song may have
     * changed during the lookups), and the queue is updated from its fresh state in one step, so
     * songs another load appended meanwhile are kept. A null position appends.
     */
    private suspend fun insertTracksAt(
        tracks: List<Track>,
        forQueue: Boolean,
        positionFor: (current: Int) -> Int?,
    ) {
        _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZING) }
        val resolved =
            tracks.map { track ->
                if (!track.artists.isNullOrEmpty()) return@map track
                val info = songRepository.getSongInfo(track.videoId).cancellable().lastOrNull()
                track.copy(artists = listOf(if (info != null) Artist(info.authorId, info.author ?: "") else Artist("", "Various Artists")))
            }
        val position = positionFor(player.currentMediaItemIndex)
        if (position == null || position !in 0..queueData.value.data.listTracks.size) {
            loadMoreCatalog(ArrayList(resolved), isAddToQueue = true)
            return
        }
        resolved.forEachIndexed { offset, track -> addMediaItemNotSet(track.toQueueMediaItem(), position + offset) }
        _queueData.update {
            val list = it.data.listTracks.toMutableList()
            list.addAll(position.coerceAtMost(list.size), resolved)
            val data = it.data.copy(listTracks = list)
            it.copy(
                data = if (forQueue) data.afterQueueAdd(position, resolved.size) else data.afterInsert(position, resolved.size),
                queueState = QueueData.StateSource.STATE_INITIALIZED,
            )
        }
        reorderShuffledQueue(player.getCurrentMediaTimeLine())
    }

    private fun Track.toQueueMediaItem(): GenericMediaItem {
        val thumbUrl =
            Regex("=w\\d+-h\\d+").replace(
                thumbnails?.lastOrNull()?.url ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                "=w544-h544",
            )
        return GenericMediaItem(
            mediaId = videoId,
            uri = videoId,
            metadata =
                GenericMediaMetadata(
                    title = title,
                    artist = artists.toListName().connectArtists(),
                    albumTitle = album?.name,
                    artworkUri = thumbUrl,
                    description = MERGING_DATA_TYPE.SONG,
                ),
            customCacheKey = videoId,
        )
    }

    override suspend fun replaceUpcomingRadio(endpoint: YouTubeWatchEndpoint): Boolean =
        // In the handler's own scope: closing Up next cancels only the wait, never a half-done edit.
        coroutineScope.async { retuneRadio(endpoint) }.await()

    private suspend fun retuneRadio(endpoint: YouTubeWatchEndpoint): Boolean {
        if (player.shuffleModeEnabled) return false
        // Checked before claiming the queue, or it could never pass.
        if (queueData.value.queueState != QueueData.StateSource.STATE_INITIALIZED) return false
        // Claimed for the whole fetch, so a loadMore set off meanwhile backs off instead of
        // appending the old radio's next page and overwriting the continuation.
        _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZING) }
        try {
            val response = songRepository.getRadioFromEndpoint(endpoint).lastOrNull()
            val (tracks, continuation) = (response as? Resource.Success)?.data ?: return false
            val data = queueData.value.data
            val current = player.currentMediaItemIndex
            // The radio part starts after the current song, or after the songs you queued.
            val from = maxOf(current + 1, data.autoplayStartIndex ?: (current + 1))
            val kept = data.listTracks.take(from).map { it.videoId }.toSet()
            // A radio starts with its seed, usually a song that is already kept.
            val radio = tracks.filter { it.videoId !in kept }
            if (radio.isEmpty()) return false
            for (index in data.listTracks.size - 1 downTo from) removeMediaItem(index)
            _queueData.update {
                it.copy(data = it.data.copy(playlistId = endpoint.playlistId ?: it.data.playlistId, continuation = continuation))
            }
            loadMoreCatalog(ArrayList(radio), isAddToQueue = true)
            return true
        } finally {
            _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
        }
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        val track =
            when (anyTrack) {
                is Track -> anyTrack
                is SongEntity -> anyTrack.toTrack()
                else -> return
            }
        if (track.isExplicit && dataStoreManager.explicitContentEnabled.first() == FALSE) {
            showToast(ToastType.ExplicitContent)
            return
        }
        songRepository.insertSong(track.toSongEntity()).singleOrNull()?.let {
            Logger.d(TAG, "Inserted song: ${track.title}")
        }
        clearMediaItems()
        track.durationSeconds?.let {
            songRepository.updateDurationSeconds(it, track.videoId)
        }
        addMediaItem(track.toGenericMediaItem(), playWhenReady = type != RECOVER_TRACK_QUEUE)
        when (type) {
            SONG_CLICK, VIDEO_CLICK, SHARE -> {
                getRelated(track.videoId)
            }

            PLAYLIST_CLICK, ALBUM_CLICK, RADIO_CLICK -> {
                loadPlaylistOrAlbum(index)
            }
        }
    }

    override fun getPlayerDuration(): Long = player.duration

    override fun getProgress(): Long = player.currentPosition

    override fun mayBeSaveRecentSong(runBlocking: Boolean) {
        val unit =
            suspend {
                // Skip while the playing song is unknown or the queue is mid-rebuild:
                // updateCatalog clears listTracks and re-inserts the current track only at
                // the end, so saving in that window persists a queue missing the current
                // track (plus a blank media id), which desyncs the next restore.
                val videoId = nowPlayingState.value.songEntity?.videoId
                if (videoId != null && queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED) {
                    dataStoreManager.saveRecentSong(
                        videoId,
                        player.contentPosition,
                    )
                    dataStoreManager.setPlaylistFromSaved(queueData.value.data.playlistName ?: "")
                    Logger.d(
                        "Check saved",
                        player.currentMediaItem
                            ?.metadata
                            ?.title
                            .toString(),
                    )
                    val temp: ArrayList<Track> = ArrayList()
                    temp.clear()
                    temp.addAll(_queueData.value.data.listTracks)
                    Logger.w("Check recover queue", temp.toString())
                    songRepository.recoverQueue(
                        temp,
                        playlistType = _queueData.value.data.playlistType?.name,
                    )
                }
            }
        if (runBlocking) {
            runBlocking { unit() }
        } else {
            coroutineScope.launch { unit() }
        }
    }

    /**
     * Lightweight periodic persistence of just the playback position (#2152).
     * Unlike [mayBeSaveRecentSong] this does NOT rewrite the whole saved queue, so it is
     * cheap enough to call every few seconds while a track plays uninterrupted. The saved
     * media id + position are what [mayBeRestoreQueue] reads to resume after a process kill.
     */
    private fun mayBeSaveRecentPosition() {
        coroutineScope.launch {
            val videoId = nowPlayingState.value.songEntity?.videoId ?: return@launch
            dataStoreManager.saveRecentSong(videoId, player.contentPosition)
        }
    }

    /**
     * Publishes YouTube's loudness and format for the track now playing *and* the next one, so the
     * engine's normalization has the incoming track's gain before a crossfade or gapless change
     * reaches it. Whether loudness is used is the engine's business ("Enhance audio" › Normalize
     * volume), so this runs on every track whatever the settings.
     */
    override fun publishTrackInfo() {
        // 2.3.8 reset the volume line here on every track; kept so nothing else changes.
        player.volume = 1f
        val current = player.currentMediaItem?.mediaId ?: return
        val next =
            if (player.shuffleModeEnabled) null else player.getMediaItemAt(player.currentMediaItemIndex + 1)?.mediaId
        volumeNormalizationJob?.cancel()
        volumeNormalizationJob =
            coroutineScope.launch {
                listOfNotNull(current, next).distinct().forEach { mediaId ->
                    launch {
                        streamRepository
                            .getFormatFlow(mediaId.removePrefix("Video"))
                            .cancellable()
                            .distinctUntilChanged()
                            .collectLatest { format ->
                                player.setTrackInfo(
                                    mediaId,
                                    TrackInfo(
                                        loudnessDb = format?.loudnessDb?.toFloat(),
                                        itag = format?.itag,
                                        bitrateKbps = format?.bitrate?.takeIf { it > 0 }?.let { it / 1000 },
                                    ),
                                )
                            }
                    }
                }
            }
    }

    override fun mayBeSavePlaybackState() {
        if (runBlocking { dataStoreManager.saveStateOfPlayback.first() } == TRUE) {
            runBlocking {
                dataStoreManager.recoverShuffleAndRepeatKey(
                    player.shuffleModeEnabled,
                    player.repeatMode,
                )
            }
        }
    }

    override fun mayBeRestoreQueue() {
        coroutineScope.launch { restoreSavedQueue(playWhenReady = false) }
    }

    override suspend fun restoreQueueAndPlay(): Boolean {
        playRequestedDuringRestore = true
        // A restore that is already past its addMediaItem has the saved track in the player, so
        // the request is served right here instead of queueing behind restoreQueueMutex. That
        // restore is sitting on loadJob.join(), and the load it joins paces itself in chunks and
        // can reach for the network, so waiting for the lock would put the whole queue in front
        // of the first note - and in front of the BUFFERING/READY that lets Media3 call
        // startForeground() before the system's deadline. Falling through is still correct when
        // the player has not been primed yet: the restore has not read the flag either, so it
        // starts the track playing itself.
        if (restoreHasPrimedPlayer && player.mediaItemCount > 0) {
            requestPlayWhenReady()
            return true
        }
        return restoreSavedQueue(playWhenReady = true)
    }

    /**
     * Asks the player to start, in a way that survives the track still being mid-load.
     *
     * [MediaPlayerInterface.play] alone does not. The adapter answers it out of its own state
     * machine and drops it outright while the track is still being set up, and a load that was
     * started with "do not play" ends by pausing whatever intent arrived during it. Writing
     * `playWhenReady` records the intent synchronously instead, and both the load and the READY
     * transition it ends on honour it - which is the whole point on the resumption path, where
     * the request routinely lands on a track the restore only just handed to the player.
     */
    private fun requestPlayWhenReady() {
        player.playWhenReady = true
    }

    /**
     * Loads the persisted queue back into the player.
     *
     * [playWhenReady] is what separates the two callers. Startup restore only primes the
     * player, but playback resumption (`MediaSession.Callback.onPlaybackResumption`, reached
     * from a media button - a headset, a Bluetooth remote, a Samsung Routine) has to end up
     * actually playing, and fast: `MediaButtonReceiver` started the service with
     * `startForegroundService()`, and Media3 only calls `startForeground()` once the player
     * reports BUFFERING or READY with `playWhenReady` set. Miss the system's foreground-start
     * deadline and the service is torn down with "did not then call Service.startForeground()",
     * which is reported as a background ANR with an idle main thread.
     *
     * That deadline is also why the resumption path starts the track loading before the rest of
     * the queue: [load] paces itself in 100-track chunks and can reach for the network to fill
     * in missing artists, so holding playback until it finishes is not safe here.
     *
     * Serialized because both callers can be in flight at once - the same service start that
     * dispatches the media button event also creates this handler, whose init calls
     * [mayBeRestoreQueue] - and a second unguarded pass would load the queue twice. That is the
     * normal ordering rather than an edge case, which is why the play request is handed to the
     * restore already in flight instead of queueing behind the lock, which would put the whole
     * queue load in front of the first note. The handoff runs in both directions, because the
     * request can arrive at any point of a restore that suspends repeatedly: before the track
     * reaches the player it is read out of [playRequestedDuringRestore] here, and after that
     * point [restoreHasPrimedPlayer] lets [restoreQueueAndPlay] start the track itself and
     * return without touching the lock at all.
     */
    private suspend fun restoreSavedQueue(playWhenReady: Boolean): Boolean =
        restoreQueueMutex.withLock {
            if (player.mediaItemCount > 0) {
                // Already restored, or the app was running all along.
                if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
                return@withLock true
            }
            val currentPlayingTrack =
                songRepository
                    .getSongById(dataStoreManager.recentMediaId.first())
                    .lastOrNull()
                    ?.toTrack() ?: return@withLock false
            // Snapshot the position before touching the player: loading the queue fires
            // onMediaItemTransition -> mayBeSaveRecentSong, which rewrites the stored
            // position before the seek below would otherwise read it.
            val savedPosition = dataStoreManager.recentPosition.first().toLongOrNull() ?: 0L
            val savedQueueEntity =
                songRepository
                    .getSavedQueue()
                    .singleOrNull()
                    ?.firstOrNull()
            val savedTracks = savedQueueEntity?.listTrack.orEmpty()
            // The saved queue may not contain the saved track (e.g. persisted while the
            // queue was being rebuilt). Put the track at the front then: updateCatalog
            // skips listTracks[index] as "already in the player", so index must point at
            // the playing track or the UI queue and the player playlist end up shifted
            // against each other.
            var index = savedTracks.indexOfFirst { it.videoId == currentPlayingTrack.videoId }
            val listTracks =
                if (index == -1) {
                    index = 0
                    (listOf(currentPlayingTrack) + savedTracks).toCollection(arrayListOf())
                } else {
                    savedTracks.toCollection(arrayListOf())
                }
            setQueueData(
                QueueData.Data(
                    listTracks = listTracks,
                    firstPlayedTrack = currentPlayingTrack,
                    playlistId = LOCAL_PLAYLIST_ID_SAVED_QUEUE,
                    playlistName = dataStoreManager.playlistFromSaved.first(),
                    playlistType =
                        runCatching {
                            savedQueueEntity?.playlistType?.let { PlaylistType.valueOf(it) }
                        }.getOrNull() ?: PlaylistType.PLAYLIST,
                    continuation = null,
                ),
            )
            // Playing is the whole difference: the track starts loading (and so reaches
            // BUFFERING) right here, before the queue behind it. The flag is re-read at each use
            // because a resumption request can arrive while the reads above are still running.
            addMediaItem(
                currentPlayingTrack.toGenericMediaItem(),
                playWhenReady = playWhenReady || playRequestedDuringRestore,
            )
            // Land on the saved position now, not after the load: the track may already be
            // playing (or start the moment it is primed below), and the queue load can take
            // seconds. load() moves this item to [index] with moveMediaItem, which keeps both the
            // item and its position, so nothing has to seek again afterwards.
            player.seekTo(0, savedPosition)
            // From here the track is in the player, so [restoreQueueAndPlay] can start it itself
            // rather than waiting for the load below. Publishing that first and then re-reading
            // the request is what closes the window between the two: a request that arrived
            // while addMediaItem was running - too late for the line above - is picked up here,
            // and one that arrives after this point finds the player primed and starts it there.
            restoreHasPrimedPlayer = true
            if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
            loadPlaylistOrAlbum(index = index)
            loadJob?.join()
            resetCrossfade()
            // Only a paused restore still needs this. While playing, the position set above has
            // moved on during the load, and seeking back to savedPosition would rewind it.
            if (!(playWhenReady || playRequestedDuringRestore)) player.seekTo(index, savedPosition)
            // Announce the restored position once. Nothing plays after a restore
            // (playWhenReady = false above), and startProgressUpdate only runs while
            // isPlaying — so no state is ever published and the UI sits at 0:00 on a
            // queue the user left half-finished, until they press play.
            _simpleMediaState.value = SimpleMediaState.Progress(savedPosition)
            // Backstop: playback has normally started well before this, above. It still matters
            // for the startup restore, which reaches here with the request already set only when
            // it lost the race described there.
            if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
            true
        }

    override fun shouldReleaseOnTaskRemoved() =
        runBlocking {
            dataStoreManager.killServiceOnExit.first() == TRUE
        }

    override fun release() {
        Logger.w("ServiceHandler", "Starting release process")
        try {
            // Save state first
            mayBeSaveRecentSong(true)
            mayBeSavePlaybackState()

            // Stop and release player
            player.removeListener(this)

            // Cancel all jobs
            progressJob?.cancel()
            progressJob = null
            bufferedJob?.cancel()
            bufferedJob = null
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob = null
            toggleLikeJob?.cancel()
            toggleLikeJob = null
            updateNotificationJob?.cancel()
            updateNotificationJob = null
            loadJob?.cancel()
            loadJob = null
            songEntityJob?.cancel()
            songEntityJob = null
            getSkipSegmentsJob?.cancel()
            getSkipSegmentsJob = null
            getFormatJob?.cancel()
            getFormatJob = null
            playbackReporter.stop()
            getDataOfNowPlayingTrackStateJob?.cancel()
            getDataOfNowPlayingTrackStateJob = null

            // Cancel coroutine scope
            coroutineScope.cancel()
            backgroundScope.cancel()

            Logger.w("ServiceHandler", "Handler released successfully. Scope active: ${coroutineScope.isActive}")
        } catch (e: Exception) {
            Logger.e("ServiceHandler", "Error during release ${e.message}")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val loaded =
            player.bufferedPosition.let {
                if (it > 0) {
                    it
                } else {
                    0
                }
            }
        val current =
            player.currentPosition.let {
                if (it > 0) {
                    it
                } else {
                    0
                }
            }
        when (playbackState) {
            PlayerConstants.STATE_IDLE -> {
                _simpleMediaState.value = SimpleMediaState.Initial
                Logger.d(TAG, "onPlaybackStateChanged: Idle")
            }

            PlayerConstants.STATE_ENDED -> {
                _simpleMediaState.value = SimpleMediaState.Ended
                Logger.d(TAG, "onPlaybackStateChanged: Ended")
            }

            PlayerConstants.STATE_READY -> {
                Logger.d(TAG, "onPlaybackStateChanged: Ready")
                _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
            }

            else -> {
                if (current >= loaded) {
                    _simpleMediaState.value = SimpleMediaState.Buffering(player.currentPosition)
                    Logger.d(TAG, "onPlaybackStateChanged: Buffering")
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _controlState.value = _controlState.value.copy(isPlaying = isPlaying)
        if (isPlaying) {
            unavailableSkips = 0
            startProgressUpdate()
        } else {
            stopProgressUpdate()
            mayBeSaveRecentSong()
            mayBeSavePlaybackState()
        }
        updateNextPreviousTrackAvailability()
    }

    override fun onSeeked(positionMs: Long) {
        // System-panel / notification seeks bypass the handler and land straight on ExoPlayer.
        // Paused, the progress ticker is stopped, so nothing else tells the UI the position
        // moved: every seek bar, clock and lyric line would wait for play to catch up (#2480).
        if (!player.isPlaying) {
            _simpleMediaState.value = SimpleMediaState.Progress(positionMs)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {
        Logger.w(TAG, "Checking current state before transition ${simpleMediaState.value}")
        val lastPlayed = nowPlayingState.value.songEntity
        val currentState = simpleMediaState.value
        if (currentState is SimpleMediaState.Progress && lastPlayed != null && lastPlayed.durationSeconds > 0) {
            mayBeTrackingListeningLocal(lastPlayed, currentState.progress)
        }
        Logger.w(TAG, "Smooth Switching Transition Current Position: ${player.currentPosition}")
        publishTrackInfo()
        Logger.w(TAG, "REASON onMediaItemTransition: $reason")
        Logger.d(TAG, "Media Item Transition Media Item: ${mediaItem?.metadata?.title}")
        if (mediaItem?.mediaId != _nowPlaying.value?.mediaId) {
            _nowPlaying.value = mediaItem
        }
        if (mediaItem?.mediaId != nowPlayingState.value.mediaItem.mediaId) {
            Logger.w(TAG, "onMediaItemTransition: ${mediaItem?.mediaId}")
            if (mediaItem != null) {
                getDataOfNowPlayingState(mediaItem)
            } else {
                _nowPlayingState.update {
                    NowPlayingTrackState
                        .initial()
                }
            }
        } else if (mediaItem != null) {
            // Repeat-one replays the same mediaId without reloading now-playing data. Each replay
            // is a listen of its own on YouTube, so it is reported again.
            startReporting(nowPlayingState.value.songEntity?.videoId ?: mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO))
        }
        queueData.value.data.listTracks.let { list ->
            if ((list.size > 3 || endlessQueueOn.value) &&
                list.size - player.currentMediaItemIndex < 3 &&
                list.size - player.currentMediaItemIndex >= 0 &&
                queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED
            ) {
                Logger.d("Check loadMore", "loadMore")
                loadMore()
            }
        }
        updateNextPreviousTrackAvailability()
        updateNotification()
        if (player.currentMediaItemIndex == 0) {
            resetCrossfade()
        }
        mayBeSaveRecentSong()
    }

    private fun mayBeTrackingListeningLocal(
        song: SongEntity,
        currentPositionMillis: Long,
    ) {
        coroutineScope.launch {
            val trackingEnabled = dataStoreManager.localTrackingEnabled.first() == TRUE
            if (!trackingEnabled) {
                return@launch
            }
            val percent = (currentPositionMillis / (song.durationSeconds * 1000f))
            Logger.w(TAG, "${song.title} - $currentPositionMillis ms listened, duration: ${song.durationSeconds * 1000} ms, percent: $percent")
            if (percent < 0.2f) {
                Logger.d(TAG, "Not enough listening time for ${song.title}, skipping tracking")
                return@launch
            }
            Logger.d(TAG, "Tracking listening for ${song.title} at position $currentPositionMillis ms")
            analyticsRepository
                .insertPlaybackEvent(
                    videoId = song.videoId,
                    channelIds = song.artistId ?: emptyList(),
                    albumBrowseId = song.albumId,
                    durationSecond = song.durationSeconds.toLong(),
                    listenedSecond =
                        if (percent >= 0.8f) {
                            song.durationSeconds.toLong()
                        } else {
                            (currentPositionMillis / 1000)
                        },
                ).collect {
                    Logger.d(TAG, "Inserted playback event for ${song.title}: $it")
                }
        }
    }

    override fun onTracksChanged(tracks: GenericTracks) {
        Logger.d(TAG, "onTracksChanged: ${tracks.groups.size}")
    }

    override fun onPlayerError(error: PlayerError) {
        Logger.e("Player Error", "onPlayerError (${error.errorCode}): ${error.message}")
        // Timeouts and Wi-Fi-only refusals are expected, not faults worth reporting.
        if (error.errorCode != PlayerConstants.ERROR_CODE_TIMEOUT && error.errorCodeName != WIFI_ONLY_ERROR_CODE_NAME) {
            pushPlayerError(error)
        }
        when (val action = playerErrorAction(error, hasValidatedInternet(), player.hasNextMediaItem(), unavailableSkips)) {
            PlayerErrorAction.Skip -> {
                unavailableSkips++
                if (isAppInForeground()) showToast(ToastType.TrackSkipped)
                // seekTo already loads and plays; prepare() here reloaded the failed track first.
                player.seekToNext()
                player.play()
            }

            is PlayerErrorAction.Pause -> {
                if (isAppInForeground()) showToast(action.toast)
                player.pause()
            }
        }
    }

    override fun onShuffleModeEnabledChanged(
        shuffleModeEnabled: Boolean,
        list: List<GenericMediaItem>,
    ) {
        when (shuffleModeEnabled) {
            true -> {
                _controlState.value = _controlState.value.copy(isShuffle = true)
            }

            false -> {
                _controlState.value = _controlState.value.copy(isShuffle = false)
            }
        }
        reorderShuffledQueue(list)
        updateNextPreviousTrackAvailability()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNextPreviousTrackAvailability()
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_OFF -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.None)
            }

            PlayerConstants.REPEAT_MODE_ONE -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.One)
            }

            PlayerConstants.REPEAT_MODE_ALL -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.All)
            }
        }
    }

    /**
     * Publishes exactly one state, and it matches the argument.
     *
     * It used to write Loading unconditionally and then call stopBufferedUpdate, which wrote
     * Loading again. `_simpleMediaState` is a StateFlow collected from another thread, so those
     * writes conflate and the UI settled on Loading even when buffering had just *finished* —
     * leaving a spinner over playback that was already running.
     */
    override fun onIsLoadingChanged(isLoading: Boolean) {
        if (isLoading) {
            startBufferedUpdate()
            // Already holding more than the playhead needs: the stall is nominal, so do not put a
            // spinner over playback that is about to continue.
            if (player.bufferedPosition > player.currentPosition) {
                _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
            } else {
                _simpleMediaState.value =
                    SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
            }
        } else {
            stopBufferedUpdate()
            _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
        }
    }

    override fun onCrossfadeStateChanged(isCrossfading: Boolean) {
        _controlState.update {
            it.copy(isCrossfading = isCrossfading)
        }
    }

    override fun onTimelineChanged(
        list: List<GenericMediaItem>,
        reason: String,
    ) {
        super.onTimelineChanged(list, reason)
        Logger.d(TAG, "onTimelineChanged: Reason: $reason, Items: ${list.size}")
        reorderShuffledQueue(list)
    }

    override fun onCastStateChanged(castState: GenericCastState) {
        _castState.value = castState
    }

    /**
     * Puts the queue in the player's play order (its shuffle order while shuffling), so Up next and
     * the artwork pager show what will really play next. Matching consumes each queued track once
     * and ignores the "Video" prefix on video items; it used to look tracks up by bare id, so a
     * video item or a song queued twice made the sizes differ and the reorder was silently skipped.
     */
    private fun reorderShuffledQueue(list: List<GenericMediaItem>) {
        val listTrack = queueData.value.data.listTracks
        if (list.isEmpty()) return
        val pool = listTrack.groupBy { it.videoId }.mapValues { ArrayDeque(it.value) }
        val sorted =
            list.mapNotNull { item ->
                pool[item.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)]?.removeFirstOrNull()
            }
        if (sorted.size != listTrack.size) {
            Logger.w(TAG, "Reordering shuffled queue skipped: ${sorted.size} of ${listTrack.size} matched")
            return
        }
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = sorted,
                    ),
            )
        }
    }
}

private fun isAppInForeground(): Boolean {
    val appProcessInfo = RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(appProcessInfo)
    return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
