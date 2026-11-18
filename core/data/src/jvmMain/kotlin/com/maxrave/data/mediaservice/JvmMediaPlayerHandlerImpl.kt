package com.maxrave.data.mediaservice

import com.maxrave.domain.mediaservice.handler.playerErrorAction
import com.maxrave.domain.mediaservice.handler.WIFI_ONLY_ERROR_CODE_NAME
import com.maxrave.domain.mediaservice.handler.PlayerErrorAction
import kotlinx.coroutines.async
import com.maxrave.data.platform.hasValidatedInternet
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
import com.maxrave.data.mediaservice.mac.MacOSMediaIntegration
import com.maxrave.data.mediaservice.mac.MacOSRemoteCommandListener
import com.maxrave.data.mediaservice.mac.NowPlayingInfo
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.AppOnScreen
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
import com.maxrave.domain.mediaservice.handler.PlaybackSnapshot
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
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
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform.getKoin
import org.simpmusic.nowplayingcenter.NPYC
import org.simpmusic.nowplayingcenter.domain.NowPlayingListener
import org.simpmusic.nowplayingcenter.domain.Platform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.firstOrNull

private val TAG = "JvmMediaPlayerHandler"

class JvmMediaPlayerHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val nypc =
        if (getPlatform() is Platform.Linux || getPlatform() is Platform.Windows) {
            runCatching { NPYC(getPlatform()) }.getOrNull()
        } else {
            null
        }

    private val macOSMediaIntegration: MacOSMediaIntegration? by lazy {
        if (MacOSMediaIntegration.isSupported()) {
            MacOSMediaIntegration.getInstance()
        } else {
            null
        }
    }

    private fun getPlatform(): Platform {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            Platform.Windows
        } else if (os.contains("mac")) {
            Platform.MacOs
        } else {
            Platform.Linux(
                "Refine Music",
                "com.maxrave.simpmusic",
            )
        }
    }

    override val player: MediaPlayerInterface = getKoin().get()

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
                volume = player.volume,
            ),
        )

    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingTrackState>(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState(false, 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _skipSegments: MutableStateFlow<List<SponsorSkipSegments>?> = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex: MutableStateFlow<Int> = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    // Desktop never casts; expose a fixed not-casting state to satisfy the interface.
    override val castState: StateFlow<GenericCastState> = MutableStateFlow(GenericCastState.NOT_CASTING).asStateFlow()

    private var skipSilent = false

    private val playbackReporter = PlaybackReporter(streamRepository, dataStoreManager, coroutineScope)

    private val endlessQueueOn: StateFlow<Boolean> =
        dataStoreManager.endlessQueue
            .map { it == TRUE }
            .stateIn(coroutineScope, SharingStarted.Eagerly, false)

    private val autoplayAllowed: Boolean
        get() = endlessQueueOn.value && queueData.value?.data?.playlistType != PlaylistType.PODCAST

    private var unavailableSkips = 0

    private val networkResume = NetworkResumeWatcher(coroutineScope) { player.play() }

    private fun startReporting(videoId: String) {
        playbackReporter.start(
            videoId = videoId,
            playlistId = queueData.value.data.playlistId,
            positionMs = { player.currentPosition },
            durationMs = { player.duration },
            isPlaying = { player.isPlaying },
        )
    }

    private var sleepTimerJob: Job? = null

    private val sleepFadeDurationMs = 5_000L

    private val sleepFadeSteps = 50

    private val sleepFadeTailMs = 800L

    private var getSkipSegmentsJob: Job? = null

    private val sponsorBlockOn =
        dataStoreManager.sponsorBlockEnabled
            .map { it == TRUE }
            .stateIn(coroutineScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    @Volatile
    private var sponsorBlockCategories: List<String> = emptyList()

    private var getFormatJob: Job? = null

    private var progressJob: Job? = null

    private var bufferedJob: Job? = null

    private var updateNotificationJob: Job? = null

    private var toggleLikeJob: Job? = null

    private var loadJob: Job? = null
    private val restoreQueueMutex = Mutex()

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

    init {
        player.addListener(this)
        progressJob = Job()
        bufferedJob = Job()
        sleepTimerJob = Job()
        updateNotificationJob = Job()
        toggleLikeJob = Job()
        loadJob = Job()
        songEntityJob = Job()
        getSkipSegmentsJob = Job()
        getFormatJob = Job()
        skipSilent = runBlocking { dataStoreManager.skipSilent.first() == TRUE }
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
        player.volume = runBlocking { dataStoreManager.playerVolume.first() }
        mayBeRestoreQueue()
        nypc?.setListener(
            object : NowPlayingListener {
                override fun onPlayPause() {
                    coroutineScope.launch {
                        onPlayerEvent(PlayerEvent.PlayPause)
                    }
                }

                override fun onNext() {
                    coroutineScope.launch {
                        onPlayerEvent(PlayerEvent.Next)
                    }
                }

                override fun onPrevious() {
                    coroutineScope.launch {
                        onPlayerEvent(PlayerEvent.Previous)
                    }
                }

                override fun onStop() {
                    coroutineScope.launch {
                        onPlayerEvent(PlayerEvent.Stop)
                    }
                }
            },
        )
        initializeMacOSMediaIntegration()
        coroutineScope.launch {
            val controlStateJob =
                launch {
                    controlState.collectLatest {
                        updateNotification()
                    }
                }
            launch {
                sponsorBlockOn.collect { on ->
                    val videoId = _nowPlaying.value?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    if (on && videoId != null) getSkipSegments(videoId) else if (!on) _skipSegments.value = null
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
                            if (sponsorBlockOn.value) {
                                if (player.duration > 0L) {
                                    val skipSegments = skipSegments.value
                                    val listCategory = sponsorBlockCategories
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
                    }.distinctUntilChanged().collectLatest { pair ->
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
                ?.find { it.videoId == videoId }
        _nowPlayingState.update {
            it.copy(
                mediaItem = mediaItem,
                track = track,
            )
        }
        _format.value = null
        _skipSegments.value = null
        startReporting(videoId)
        getDataOfNowPlayingTrackStateJob?.cancel()
        getDataOfNowPlayingTrackStateJob =
            coroutineScope.launch {
                Logger.w(TAG, "getDataOfNowPlayingState: $videoId")
                songRepository.getSongById(videoId).cancellable().singleOrNull().let { songEntity ->
                    val song: SongEntity =
                        if (songEntity != null) {
                            _controlState.update { it.copy(isLiked = songEntity.liked) }
                            val thumbUrl =
                                Regex("=w\\d+-h\\d+").replace(
                                    track?.thumbnails?.lastOrNull()?.url
                                        ?: songEntity.thumbnails
                                        ?: "https://i.ytimg.com/vi/${songEntity.videoId}/maxresdefault.jpg",
                                    "=w544-h544",
                                )
                            if (songEntity.thumbnails != thumbUrl) {
                                songRepository.updateThumbnailsSongEntity(thumbUrl, songEntity.videoId).singleOrNull()?.let {
                                    Logger.w(TAG, "getDataOfNowPlayingState: Updated thumbs $it")
                                }
                            }
                            // normalize() drops anything that is not a real type, so an unknown never overwrites a known one.
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
                            songEntity.copy(thumbnails = thumbUrl)
                        } else {
                            _controlState.update { it.copy(isLiked = false) }
                            val thumbUrl =
                                Regex("=w\\d+-h\\d+").replace(
                                    track?.thumbnails?.lastOrNull()?.url
                                        ?: "https://i.ytimg.com/vi/${track?.videoId}/maxresdefault.jpg",
                                    "=w544-h544",
                                )
                            val newSong =
                                (track?.toSongEntity() ?: mediaItem.toSongEntity()).copy(
                                    thumbnails = thumbUrl,
                                )
                            songRepository
                                .insertSong(newSong)
                                .singleOrNull()
                                ?.let {
                                    Logger.w(TAG, "getDataOfNowPlayingState: $it")
                                }
                            newSong
                        }
                    Logger.w(TAG, "getDataOfNowPlayingState: $songEntity")
                    Logger.w(TAG, "getDataOfNowPlayingState: $track")
                    _nowPlayingState.update {
                        it.copy(
                            songEntity = song,
                        )
                    }
                    nypc?.setNowPlaying(
                        song.title,
                        song.artistName?.joinToString(", ") ?: "",
                        song.albumName ?: "",
                        song.thumbnails,
                    )
                    updateMacOSNowPlayingInfo(song)
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
                if (sponsorBlockOn.value) {
                    getSkipSegments(videoId)
                }
                getFormat(videoId)
            }
    }

    private fun getSkipSegments(videoId: String) {
        _skipSegments.value = null
        getSkipSegmentsJob?.cancel()
        getSkipSegmentsJob =
            coroutineScope.launch {
            sponsorBlockCategories = dataStoreManager.getSponsorBlockCategories()
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
        coroutineScope.launch {
            nypc?.setButtonEnabled(
                isPlaying = controlState.value.isPlaying,
                canGoNext = controlState.value.isNextAvailable,
                canGoPrevious = controlState.value.isPreviousAvailable,
            )
        }
        updateMacOSCommandsEnabled()
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

    private fun moveInPlayOrder(
        fromPosition: Int,
        toPosition: Int,
    ) {
        player.moveMediaItemInPlayOrder(fromPosition, toPosition)
        _currentSongIndex.value = player.currentMediaItemIndex
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
                        GenericCommandButton.Repeat(repeatState = _controlState.value.repeatState),
                        GenericCommandButton.Radio,
                        GenericCommandButton.Shuffle(isShuffled = _controlState.value.isShuffle),
                    ),
                )
            }
    }

    // Region: Override functions
    override fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                val positionPersistIntervalMs = 5_000L
                var sinceLastPositionSaveMs = 0L
                while (true) {
                    val intervalMs = if (AppOnScreen.onScreen.value) 100L else 1_000L
                    delay(intervalMs)
                    _simpleMediaState.value = SimpleMediaState.Progress(player.currentPosition)
                    updateMacOSElapsedTime()
                    sinceLastPositionSaveMs += intervalMs
                    if (sinceLastPositionSaveMs >= positionPersistIntervalMs) {
                        sinceLastPositionSaveMs = 0
                        mayBeSaveRecentPosition()
                    }
                }
            }
    }

    override fun startBufferedUpdate() {
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
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        if (playerEvent !is PlayerEvent.UpdateVolume) networkResume.disarm()
        when (playerEvent) {
            is PlayerEvent.UpdateVolume -> {
                Logger.w(TAG, "onPlayerEvent: UpdateVolume ${playerEvent.newVolume}")
                player.volume = playerEvent.newVolume
            }

            PlayerEvent.Backward -> {
                player.seekBack()
            }

            PlayerEvent.Forward -> {
                player.seekForward()
            }

            PlayerEvent.PlayPause -> {
                if (player.isPlaying) {
                    stopProgressUpdate()
                    player.pause()
                } else {
                    player.play()
                    startProgressUpdate()
                }
            }

            PlayerEvent.Next -> {
                player.skipToNext()
            }

            PlayerEvent.Previous -> {
                player.skipToPrevious()
            }

            PlayerEvent.SkipToPrevious -> {
                player.skipToPreviousMediaItem()
            }

            PlayerEvent.Stop -> {
                stopProgressUpdate()
                player.stop()
                _nowPlayingState.value = NowPlayingTrackState.initial()
            }

            is PlayerEvent.UpdateProgress -> {
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
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = -1)
                        }
                        var remaining: Long
                        while (true) {
                            val duration = player.duration
                            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                            remaining =
                                if (duration > 0L) ((duration - player.currentPosition).coerceAtLeast(0L) / speed).toLong() else Long.MAX_VALUE
                            if (remaining <= sleepFadeDurationMs + sleepFadeTailMs) break
                            delay(250)
                        }
                        val fadeMs = sleepFadeDurationMs.coerceAtMost(remaining)
                        val tailMs = sleepFadeTailMs.coerceAtMost(remaining - fadeMs)
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
                        // Measured against a clock that keeps running while the CPU sleeps, so the timer never drifts.
                        val deadline = System.currentTimeMillis() + minutes * 60_000L
                        val fadeAt = deadline - sleepFadeDurationMs - sleepFadeTailMs
                        var shown = minutes
                        while (true) {
                            val left = fadeAt - System.currentTimeMillis()
                            if (left <= 0L) break
                            delay(left.coerceAtMost(1_000L))
                            val minutesLeft = ((deadline - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                            if (minutesLeft != shown) {
                                shown = minutesLeft
                                _sleepTimerState.update { it.copy(isDone = false, timeRemaining = minutesLeft) }
                            }
                        }
                        fadeOutForSleep(sleepFadeDurationMs)
                        delay(sleepFadeTailMs)
                        Logger.i(TAG, "Sleep timer elapsed after $minutes minutes; pausing")
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    }
                } finally {
                    if (!stoppedPlayback) player.sleepFadeFactor = 1f
                }
            }
    }

    private suspend fun fadeOutForSleep(durationMs: Long) {
        if (durationMs <= 0L) return
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
        if (position !in listTracks.indices) {
            Logger.w(TAG, "removeMediaItem: $position is outside the queue (${listTracks.size})")
            return
        }
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

    override fun currentOrderIndex(): Int =
        if (player.shuffleModeEnabled) {
            queueData.value.data.listTracks.indexOfLast {
                it.videoId == player.currentMediaItem?.mediaId
            }
        } else {
            currentSongIndex()
        }

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
        if (from < to) {
            for (i in from until to) {
                moveItemDown(i)
            }
        } else {
            for (i in from downTo to + 1) {
                moveItemUp(i)
            }
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
                                if (autoplayAllowed) {
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
        } else if (autoplayAllowed) {
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
        Logger.w(TAG, "setQueueData: $queueData")
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) {
        moveInPlayOrder(position, position - 1)
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
        moveInPlayOrder(position, position + 1)
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
            if (thumbUrl.contains("w120")) {
                thumbUrl = Regex("([wh])120").replace(thumbUrl, "$1544")
            }
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
                if (thumbUrl.contains("w120")) {
                    thumbUrl = Regex("([wh])120").replace(thumbUrl, "$1544")
                }
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

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
    }

    override suspend fun playNext(track: Track) {
        insertTracksAt(listOf(track), forQueue = false) { current -> current + 1 }
    }

    override suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (player.shuffleModeEnabled) {
            loadMoreCatalog(ArrayList(tracks), isAddToQueue = true)
            return
        }
        insertTracksAt(tracks, forQueue = true) { current -> queueData.value.data.addToQueueIndex(current) }
    }

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
        _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZING) }
        try {
            val response = songRepository.getRadioFromEndpoint(endpoint).lastOrNull()
            val (tracks, continuation) = (response as? Resource.Success)?.data ?: return false
            val data = queueData.value.data
            val current = player.currentMediaItemIndex
            val from = maxOf(current + 1, data.autoplayStartIndex ?: (current + 1))
            val kept = data.listTracks.take(from).map { it.videoId }.toSet()
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
        if (track.isExplicit && runBlocking { dataStoreManager.explicitContentEnabled.first() } == FALSE) {
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

    private fun mayBeSaveRecentPosition() {
        coroutineScope.launch {
            val videoId = nowPlayingState.value.songEntity?.videoId ?: return@launch
            dataStoreManager.saveRecentSong(videoId, player.contentPosition)
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

    override suspend fun restoreQueueAndPlay(): Boolean = restoreSavedQueue(playWhenReady = true)

    private suspend fun restoreSavedQueue(playWhenReady: Boolean): Boolean =
        restoreQueueMutex.withLock {
            if (player.mediaItemCount > 0) {
                if (playWhenReady) player.play()
                return@withLock true
            }
            val currentPlayingTrack =
                songRepository
                    .getSongById(dataStoreManager.recentMediaId.first())
                    .lastOrNull()
                    ?.toTrack() ?: return@withLock false
            val savedPosition = dataStoreManager.recentPosition.first().toLongOrNull() ?: 0L
            val savedQueueEntity =
                songRepository
                    .getSavedQueue()
                    .singleOrNull()
                    ?.firstOrNull()
            val savedTracks = savedQueueEntity?.listTrack.orEmpty()
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
            addMediaItem(currentPlayingTrack.toGenericMediaItem(), playWhenReady = playWhenReady)
            loadPlaylistOrAlbum(index = index)
            loadJob?.join()
            player.seekTo(index, savedPosition)
            _simpleMediaState.value = SimpleMediaState.Progress(savedPosition)
            true
        }

    override suspend fun loadSnapshot(snapshot: PlaybackSnapshot): Boolean =
        restoreQueueMutex.withLock {
            val tracks = snapshot.queue.listTracks
            val index = snapshot.index.takeIf { it in tracks.indices } ?: return@withLock false
            val current = tracks[index]
            loadJob?.cancel()
            networkResume.disarm()
            if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
            songRepository.insertSong(current.toSongEntity()).lastOrNull()
            current.durationSeconds?.let { songRepository.updateDurationSeconds(it, current.videoId) }
            setQueueData(snapshot.queue.copy(listTracks = ArrayList(tracks), firstPlayedTrack = current))
            addMediaItem(current.toGenericMediaItem(), playWhenReady = snapshot.playWhenReady)
            if (snapshot.positionMs > 0L) player.seekTo(0, snapshot.positionMs)
            loadPlaylistOrAlbum(index = index)
            loadJob?.join()
            snapshot.queue.autoplayStartIndex?.let { start ->
                _queueData.update { it.copy(data = it.data.copy(autoplayStartIndex = start)) }
            }
            if (!snapshot.playWhenReady) {
                player.seekTo(index, snapshot.positionMs)
                _simpleMediaState.value = SimpleMediaState.Progress(snapshot.positionMs)
            }
            true
        }

    override fun shouldReleaseOnTaskRemoved() =
        runBlocking {
            dataStoreManager.killServiceOnExit.first() == TRUE
        }

    override fun release() {
        Logger.w("ServiceHandler", "Starting release process")
        nypc?.removeListener()
        clearMacOSNowPlayingInfo()
        macOSMediaIntegration?.release()
        try {
            mayBeSaveRecentSong(true)
            mayBeSavePlaybackState()

            player.removeListener(this)

            progressJob?.cancel()
            progressJob = null
            bufferedJob?.cancel()
            bufferedJob = null
            sleepTimerJob?.cancel()
            sleepTimerJob = null
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

            coroutineScope.cancel()
            backgroundScope.cancel()

            Logger.w("ServiceHandler", "Handler released successfully. Scope active: ${coroutineScope.isActive}")
        } catch (e: Exception) {
            Logger.e("ServiceHandler", "Error during release ${e.message}")
        }
    }

    override fun onVolumeChanged(volume: Float) {
        super.onVolumeChanged(volume)
        _controlState.update {
            it.copy(
                volume = volume,
            )
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

    override fun onPlaybackConfirmed() {
        unavailableSkips = 0
        networkResume.disarm()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _controlState.value = _controlState.value.copy(isPlaying = isPlaying)
        if (isPlaying) {
            startProgressUpdate()
        } else {
            stopProgressUpdate()
            mayBeSaveRecentSong()
            mayBeSavePlaybackState()
        }
        updateNextPreviousTrackAvailability()
        updateMacOSPlaybackState(isPlaying)
    }

    override fun onSeeked(positionMs: Long) {
        if (!player.isPlaying) {
            _simpleMediaState.value = SimpleMediaState.Progress(positionMs)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {
        Logger.w(TAG, "Checking current state before transition ${simpleMediaState.value}")
        Logger.w(TAG, "Smooth Switching Transition Current Position: ${player.currentPosition}")
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
            startReporting(nowPlayingState.value.songEntity?.videoId ?: mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO))
        }
        queueData.value.data.listTracks.let { list ->
            if ((list.size > 3 || autoplayAllowed) &&
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
        mayBeSaveRecentSong()
    }

    override fun onTracksChanged(tracks: GenericTracks) {
        Logger.d(TAG, "onTracksChanged: ${tracks.groups.size}")
    }

    override fun onPlayerError(error: PlayerError) {
        Logger.e("Player Error", "onPlayerError (${error.errorCode}): ${error.message}")
        if (error.errorCode != PlayerConstants.ERROR_CODE_TIMEOUT && error.errorCodeName != WIFI_ONLY_ERROR_CODE_NAME) {
            pushPlayerError(error)
        }
        when (val action = playerErrorAction(error, hasValidatedInternet(), player.hasNextMediaItem(), unavailableSkips)) {
            PlayerErrorAction.Skip -> {
                unavailableSkips++
                showToast(ToastType.TrackSkipped)
                player.seekToNext()
                player.play()
            }

            is PlayerErrorAction.Pause -> {
                showToast(action.toast)
                player.pause()
                if (action.network) networkResume.arm()
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

    override fun onTimelineChanged(
        list: List<GenericMediaItem>,
        reason: String,
    ) {
        super.onTimelineChanged(list, reason)
        Logger.d(TAG, "onTimelineChanged: $reason, items: ${list.size}")
        reorderShuffledQueue(list)
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

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Logger.d(
            TAG,
            "onIsLoadingChanged: $isLoading (buffered=${player.bufferedPosition}ms, " +
                "position=${player.currentPosition}ms, duration=${player.duration}ms)",
        )
        if (isLoading) {
            startBufferedUpdate()
            // Already holding more than the playhead needs: the stall is nominal, so do not put a spinner over playback that is about to continue.
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

    private fun initializeMacOSMediaIntegration() {
        macOSMediaIntegration?.let { integration ->
            if (integration.initialize()) {
                integration.setRemoteCommandListener(
                    object : MacOSRemoteCommandListener {
                        override fun onPlay() {
                            Logger.d(TAG, "macOS Remote: Play")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.PlayPause)
                            }
                        }

                        override fun onPause() {
                            Logger.d(TAG, "macOS Remote: Pause")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.PlayPause)
                            }
                        }

                        override fun onTogglePlayPause() {
                            Logger.d(TAG, "macOS Remote: Toggle Play/Pause")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.PlayPause)
                            }
                        }

                        override fun onStop() {
                            Logger.d(TAG, "macOS Remote: Stop")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.Stop)
                            }
                        }

                        override fun onNextTrack() {
                            Logger.d(TAG, "macOS Remote: Next Track")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.Next)
                            }
                        }

                        override fun onPreviousTrack() {
                            Logger.d(TAG, "macOS Remote: Previous Track")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.Previous)
                            }
                        }

                        override fun onSeekForward() {
                            Logger.d(TAG, "macOS Remote: Seek Forward")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.Forward)
                            }
                        }

                        override fun onSeekBackward() {
                            Logger.d(TAG, "macOS Remote: Seek Backward")
                            coroutineScope.launch {
                                onPlayerEvent(PlayerEvent.Backward)
                            }
                        }

                        override fun onChangePlaybackPosition(positionSeconds: Double) {
                            Logger.d(TAG, "macOS Remote: Seek to ${positionSeconds}s")
                            coroutineScope.launch {
                                player.seekTo((positionSeconds * 1000).toLong())
                            }
                        }
                    },
                )
                Logger.d(TAG, "macOS media integration initialized successfully")
            }
        }
    }

    private fun updateMacOSNowPlayingInfo(songEntity: SongEntity) {
        macOSMediaIntegration?.updateNowPlayingInfo(
            NowPlayingInfo(
                title = songEntity.title,
                artist = songEntity.artistName?.connectArtists() ?: "Unknown Artist",
                album = songEntity.albumName ?: "",
                durationSeconds = getPlayerDuration() / 1000.0,
                elapsedTimeSeconds = player.contentPosition / 1000.0,
                playbackRate = 1.0,
                artworkUrl = songEntity.thumbnails,
                queueIndex = player.currentMediaItemIndex,
                queueCount = queueData.value.data.listTracks.size,
            ),
        )

        updateMacOSCommandsEnabled()

        val artworkUrl = songEntity.thumbnails
        if (!artworkUrl.isNullOrEmpty()) {
            coroutineScope.launch {
                macOSMediaIntegration?.loadAndSetArtwork(artworkUrl)
            }
        }
    }

    private fun updateMacOSPlaybackState(isPlaying: Boolean) {
        macOSMediaIntegration?.updatePlaybackState(isPlaying)
    }

    private fun updateMacOSCommandsEnabled() {
        val hasNext = _controlState.value.isNextAvailable
        val hasPrevious = _controlState.value.isPreviousAvailable
        val canSeek = getPlayerDuration() > 0
        macOSMediaIntegration?.updateCommandsEnabled(
            hasNext = hasNext,
            hasPrevious = hasPrevious,
            canSeek = canSeek,
        )
    }

    private fun updateMacOSElapsedTime() {
        macOSMediaIntegration?.updateElapsedTime(player.currentPosition / 1000.0, 1.0)
    }

    private fun clearMacOSNowPlayingInfo() {
        macOSMediaIntegration?.clearNowPlayingInfo()
    }
}
