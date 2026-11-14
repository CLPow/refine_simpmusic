package com.maxrave.simpmusic.viewModel

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config.ALBUM_CLICK
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.RECOVER_TRACK_QUEUE
import com.maxrave.common.Config.SHARE
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.common.Config.VIDEO_CLICK
import com.maxrave.common.STATUS_DONE
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.intent.GenericIntent
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.domain.extension.decodeHtmlEntities
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.lyrics.LyricsSourceOrder
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.repository.PlaybackPingStatus
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toLyrics
import com.maxrave.domain.utils.toLyricsEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_album_added_to_queue
import simpmusic.composeapp.generated.resources.ytm_playlist_added_to_queue
import simpmusic.composeapp.generated.resources.ytm_song_added_to_queue
import simpmusic.composeapp.generated.resources.ytm_songs_added_to_queue
import simpmusic.composeapp.generated.resources.added_to_youtube_liked
import simpmusic.composeapp.generated.resources.error
import simpmusic.composeapp.generated.resources.removed_from_youtube_liked
import simpmusic.composeapp.generated.resources.shared
import simpmusic.composeapp.generated.resources.updated
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
class SharedViewModel(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val lyricsCanvasRepository: LyricsCanvasRepository,
    private val cacheRepository: CacheRepository,
) : BaseViewModel() {
    /** See [StreamRepository.lastPlaybackPingStatus]. Null until a track has played. */
    fun lastPlaybackPingStatus(): PlaybackPingStatus? = streamRepository.lastPlaybackPingStatus()

    var isFirstLiked: Boolean = false
    var isFirstMiniplayer: Boolean = false
    var isFirstSuggestions: Boolean = false
    private var _liked: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val liked: SharedFlow<Boolean> = _liked.asSharedFlow()

    var isServiceRunning: Boolean = false

    private var _sleepTimerState = MutableStateFlow(SleepTimerState(false, 0))
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState

    private var regionCode: String? = null

    private var _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow(null)
    val format: SharedFlow<NewFormatEntity?> = _format.asSharedFlow()

    /**
     * Which extractor and cipher decoder produced the current track's URLs, shown in the info sheet.
     *
     * Read alongside the format rather than stored with it: the format row is cached and reused,
     * while this describes the extraction that happened in this run of the app.
     */
    private val _extractSource: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _intent: MutableStateFlow<GenericIntent?> = MutableStateFlow(null)
    val intent: StateFlow<GenericIntent?> = _intent

    private val _showNotificationPermissionDialog = MutableStateFlow(false)
    val showNotificationPermissionDialog: StateFlow<Boolean> = _showNotificationPermissionDialog

    private var getFormatFlowJob: Job? = null

    var playlistId: MutableStateFlow<String?> = MutableStateFlow(null)

    private var _nowPlayingState = MutableStateFlow<NowPlayingTrackState?>(null)
    val nowPlayingState: StateFlow<NowPlayingTrackState?> = _nowPlayingState

    fun getQueueDataState() = mediaPlayerHandler.queueData

    private var _controllerState =
        MutableStateFlow<ControlState>(
            ControlState(
                isPlaying = false,
                isShuffle = false,
                repeatState = RepeatState.None,
                isLiked = false,
                isNextAvailable = false,
                isPreviousAvailable = false,
                volume = 1f,
            ),
        )
    val controllerState: StateFlow<ControlState> = _controllerState
    private var _timeline =
        MutableStateFlow<TimeLine>(
            TimeLine(
                current = -1L,
                total = -1L,
                bufferedPercent = 0,
                loading = true,
            ),
        )
    val timeline: StateFlow<TimeLine> = _timeline

    private var _nowPlayingScreenData =
        MutableStateFlow<NowPlayingScreenData>(
            NowPlayingScreenData.initial(),
        )
    val nowPlayingScreenData: StateFlow<NowPlayingScreenData> = _nowPlayingScreenData

    private var _likeStatus = MutableStateFlow<Boolean>(false)
    val likeStatus: StateFlow<Boolean> = _likeStatus

    init {
        viewModelScope.launch {
            log("SharedViewModel init")
            // Before anything reads a cached lyric.
            runCatching { lyricsCanvasRepository.purgeLyricsCacheIfStale(dataStoreManager) }
            val timeLineJob =
                launch {
                    nowPlayingState
                        .filterNotNull()
                        .flatMapLatest { nowPlayingState ->
                            timeline.map { timeLine ->
                                Pair(timeLine, nowPlayingState)
                            }
                        }.distinctUntilChanged { old, new ->
                            (old.first.total.toString() + old.second.songEntity?.videoId).hashCode() ==
                                (new.first.total.toString() + new.second.songEntity?.videoId).hashCode()
                        }.collectLatest {
                            log("Timeline job ${(it.first.total.toString() + it.second.songEntity?.videoId).hashCode()}")
                            val nowPlaying = it.second
                            val timeline = it.first
                            if (timeline.total > 0 && nowPlaying.songEntity != null) {
                                nowPlaying.songEntity?.let { song ->
                                    if (nowPlayingScreenData.value.lyricsData == null) {
                                        Logger.w(tag, "Get lyrics from format")
                                        getLyricsFromFormat(nowPlaying.mediaItem.isVideo(), song, (timeline.total / 1000).toInt())
                                    }
                                }
                            }
                        }
                }
            val lyricsProviderJob =
                launch {
                    dataStoreManager.lyricsProvider.distinctUntilChanged().collectLatest {
                        setLyricsProvider()
                    }
                }
            timeLineJob.join()
            lyricsProviderJob.join()
        }

        viewModelScope.launch {
            dataStoreManager.getString("miniplayer_guide").first().let {
                isFirstMiniplayer = it != STATUS_DONE
            }
            dataStoreManager.getString("suggest_guide").first().let {
                isFirstSuggestions = it != STATUS_DONE
            }
            dataStoreManager.getString("liked_guide").first().let {
                isFirstLiked = it != STATUS_DONE
            }
        }
        viewModelScope.launch {
            mediaPlayerHandler.nowPlayingState
                .distinctUntilChangedBy {
                    it.songEntity?.videoId
                }.collectLatest { state ->
                    Logger.w(tag, "NowPlayingState is $state")
                    _nowPlayingState.value = state

                    // Seed the timeline from METADATA as soon as the track is known, instead of
                    // waiting for the player. SimpleMediaState.Ready carries a duration and only
                    // fires once the container is parsed; after a queue restore nothing plays, the
                    // position poll never starts (it runs only while isPlaying), and so nothing
                    // ever reports a length — which is why a restored queue showed no times at all.
                    // SongEntity has known the length since the song was first seen.
                    //
                    // Written on EVERY track change, not just when missing: leaving the old value
                    // in place would show the previous track's length over the new one.
                    // Order matters: metadata first because it is available immediately, then the
                    // player's own duration, and only -1 ("not known yet") when neither has one.
                    //
                    // Writing -1 whenever metadata is missing — which this did at first — makes
                    // every radio track and every first-time track flash NA:NA on the clock until
                    // the container is parsed, because those rows have no durationSeconds stored.
                    // Asking the player before giving up covers exactly that case: on a normal
                    // track change it usually already knows.
                    val metadataDurationMs = (state.songEntity?.durationSeconds ?: 0).toLong() * 1000L
                    val seededTotal =
                        metadataDurationMs.takeIf { it > 0L }
                            ?: mediaPlayerHandler.getPlayerDuration().takeIf { it > 0L }
                            ?: -1L
                    _timeline.update { it.copy(total = seededTotal) }
                    state.songEntity?.let { track ->
                        _nowPlayingScreenData.value =
                            NowPlayingScreenData(
                                nowPlayingTitle = track.title,
                                artistName =
                                    track
                                        .artistName
                                        ?.joinToString(", ") ?: "",
                                isVideo = false,
                                thumbnailURL = null,
                                lyricsData = null,
                                songInfoData = null,
                                playlistName =
                                    mediaPlayerHandler.queueData.value
                                        ?.data
                                        ?.playlistName ?: "",
                            )
                    }
                    state.mediaItem.let { now ->
                        getLikeStatus(now.mediaId)
                        getSongInfo(now.mediaId)
                        getFormat(now.mediaId)
                        _nowPlayingScreenData.update {
                            it.copy(
                                thumbnailURL = now.metadata.artworkUri,
                                isVideo = now.isVideo(),
                            )
                        }
                    }
                    state.songEntity?.let { song ->
                        _liked.value = song.liked == true
                        _nowPlayingScreenData.update {
                            it.copy(
                                isExplicit = song.isExplicit,
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            val job1 =
                launch {
                    mediaPlayerHandler.simpleMediaState.collect { mediaState ->
                        when (mediaState) {
                            is SimpleMediaState.Buffering -> {
                                _timeline.update {
                                    it.copy(
                                        loading = true,
                                    )
                                }
                            }

                            SimpleMediaState.Initial -> {
                                _timeline.update { it.copy(loading = true) }
                            }

                            SimpleMediaState.Ended -> {
                                // Park at the end of the track rather than at -1. The only formatter
                                // for these fields renders any negative as "NA:NA", and nothing here
                                // is guaranteed to follow: at the end of the queue the player simply
                                // stays ended, so a -1 written here stays on screen. Worse, the
                                // Progress branch below ignores negative values and the Loading
                                // branch restores `total` without touching `current`, which is how
                                // the player ends up showing a correct duration next to "NA:NA".
                                _timeline.update {
                                    it.copy(
                                        current = it.total.coerceAtLeast(0L),
                                        bufferedPercent = 0,
                                        loading = false,
                                    )
                                }
                            }

                            is SimpleMediaState.Progress -> {
                                if (mediaState.progress >= 0L && mediaState.progress != _timeline.value.current) {
                                    if (_timeline.value.total > 0L) {
                                        _timeline.update {
                                            it.copy(
                                                total = mediaPlayerHandler.getPlayerDuration().takeIf { d -> d > 0L } ?: it.total,
                                                current = mediaState.progress,
                                                loading = false,
                                            )
                                        }
                                    } else {
                                        _timeline.update {
                                            it.copy(
                                                current = mediaState.progress,
                                                loading = true,
                                                total = mediaPlayerHandler.getPlayerDuration().takeIf { d -> d > 0L } ?: it.total,
                                            )
                                        }
                                    }
                                }
                                // When progress hasn't changed (same value polled again) or is negative,
                                // don't modify loading state. The loading flag is already managed by
                                // Buffering/Ready/Loading state events. Setting loading=true here would
                                // cause rapid flickering because the progress poll interval (100ms) is
                                // shorter than the adapter's position cache update interval (200ms),
                                // resulting in duplicate position values that incorrectly triggered loading.
                            }

                            is SimpleMediaState.Loading -> {
                                _timeline.update {
                                    it.copy(
                                        bufferedPercent = mediaState.bufferedPercentage,
                                        total = mediaState.duration,
                                        loading = true,
                                    )
                                }
                            }

                            is SimpleMediaState.Ready -> {
                                _timeline.update {
                                    it.copy(
                                        current = mediaPlayerHandler.getProgress(),
                                        loading = false,
                                        // The player's own duration wins, but ONLY when it has one.
                                        // ExoPlayer answers C.TIME_UNSET (a large negative, not
                                        // null) until it has parsed the container, and Ready is
                                        // also published from onIsLoadingChanged — which fires
                                        // before STATE_READY. Writing that would throw away the
                                        // metadata duration seeded on the track change above.
                                        total = mediaState.duration.takeIf { d -> d > 0L } ?: it.total,
                                    )
                                }
                            }
                        }
                    }
                }
            val controllerJob =
                launch {
                    Logger.w(tag, "ControllerJob is running")
                    mediaPlayerHandler.controlState.collectLatest {
                        Logger.w(tag, "ControlState is $it")
                        _controllerState.value = it
                    }
                }
            val sleepTimerJob =
                launch {
                    mediaPlayerHandler.sleepTimerState.collectLatest {
                        _sleepTimerState.value = it
                    }
                }
            val playlistNameJob =
                launch {
                    mediaPlayerHandler.queueData.collectLatest {
                        _nowPlayingScreenData.update {
                            it.copy(playlistName = it.playlistName)
                        }
                    }
                }
            job1.join()
            controllerJob.join()
            sleepTimerJob.join()
            playlistNameJob.join()
        }
        // Reset downloading songs & playlists to not downloaded
        checkAllDownloadingSongs()
        checkAllDownloadingPlaylists()
        checkAllDownloadingLocalPlaylists()
    }

    fun setIntent(intent: GenericIntent?) {
        _intent.value = intent
    }

    fun showNotificationPermissionDialog() {
        _showNotificationPermissionDialog.value = true
    }

    fun dismissNotificationPermissionDialog(doNotShowAgain: Boolean) {
        _showNotificationPermissionDialog.value = false
        if (doNotShowAgain) {
            putString("notification_permission_do_not_ask", "true")
        }
    }

    private fun getLikeStatus(videoId: String?) {
        viewModelScope.launch {
            if (videoId != null) {
                _likeStatus.value = false
                songRepository.getLikeStatus(videoId).collectLatest { status ->
                    _likeStatus.value = status
                    // Liked on YouTube elsewhere (the web, another phone): show it as liked here
                    // too, the way YouTube Music's thumb follows the account.
                    val id = videoId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    if (status && !mediaPlayerHandler.controlState.value.isLiked) {
                        songRepository.updateLikeStatus(id, 1)
                        mediaPlayerHandler.like(true)
                    }
                }
            }
        }
    }

    suspend fun readString(key: String): String? = dataStoreManager.getString(key).first()

    fun putString(
        key: String,
        value: String,
    ) {
        viewModelScope.launch { dataStoreManager.putString(key, value) }
    }

    fun stopSleepTimer() {
        mediaPlayerHandler.sleepStop()
    }

    private var _downloadState: MutableStateFlow<DownloadHandler.Download?> = MutableStateFlow(null)
    var downloadState: StateFlow<DownloadHandler.Download?> = _downloadState.asStateFlow()

    fun checkIsRestoring() {
        viewModelScope.launch {
            val downloadedCacheKeys = cacheRepository.getAllCacheKeys(DOWNLOAD_CACHE)
            songRepository.getDownloadedSongs().first().let { songs ->
                songs?.forEach { song ->
                    if (!downloadedCacheKeys.contains(song.videoId)) {
                        songRepository.updateDownloadState(
                            song.videoId,
                            DownloadState.STATE_NOT_DOWNLOADED,
                        )
                    }
                }
            }
            playlistRepository.getAllDownloadedPlaylist().first().let { list ->
                for (data in list) {
                    when (data) {
                        is AlbumEntity -> {
                            val tracks = data.tracks ?: emptyList()
                            if (tracks.isEmpty() ||
                                (
                                    !downloadedCacheKeys.containsAll(
                                        tracks,
                                    )
                                )
                            ) {
                                albumRepository.updateAlbumDownloadState(
                                    data.browseId,
                                    DownloadState.STATE_NOT_DOWNLOADED,
                                )
                            }
                        }

                        is PlaylistEntity -> {
                            val tracks = data.tracks ?: emptyList()
                            if (tracks.isEmpty() ||
                                (
                                    !downloadedCacheKeys.containsAll(
                                        tracks,
                                    )
                                )
                            ) {
                                playlistRepository.updatePlaylistDownloadState(
                                    data.id,
                                    DownloadState.STATE_NOT_DOWNLOADED,
                                )
                            }
                        }

                        is LocalPlaylistEntity -> {
                            val tracks = data.tracks ?: emptyList()
                            if (tracks.isEmpty() ||
                                (
                                    !downloadedCacheKeys.containsAll(
                                        tracks,
                                    )
                                )
                            ) {
                                localPlaylistRepository.updateLocalPlaylistDownloadState(
                                    DownloadState.STATE_NOT_DOWNLOADED,
                                    data.id,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun insertLyrics(lyrics: LyricsEntity) {
        viewModelScope.launch {
            lyricsCanvasRepository.insertLyrics(lyrics)
        }
    }

    // A single read, inside the lyrics job: a collector of its own outlived the song and wrote a
    // later database change for it over the next song's lyrics.
    private suspend fun getSavedLyrics(track: Track) {
        run {
            val lyrics = lyricsCanvasRepository.getSavedLyrics(track.videoId).firstOrNull()
            run {
                if (lyrics != null) {
                    val lyricsData = lyrics.toLyrics()
                    Logger.d(tag, "Saved Lyrics $lyricsData")
                    updateLyrics(
                        track.videoId,
                        track.durationSeconds ?: 0,
                        lyricsData,
                        LyricsProvider.OFFLINE,
                    )
                } else {
                    // The end of the chain, and the only place that can honestly say there are
                    // none: every source has been asked and nothing was saved from an earlier run.
                    // Previously this branch did not exist, so a track with no lyrics anywhere left
                    // the screen in whatever state it was already in, with no indication that the
                    // search had finished.
                    val plain = youTubePlainLyrics?.takeIf { it.first == track.videoId }?.second
                    if (plain != null) {
                        updateLyrics(track.videoId, track.durationSeconds ?: 0, plain, LyricsProvider.YOUTUBE_MUSIC)
                        return
                    }
                    Logger.d(tag, "No lyrics from any source for ${track.videoId}")
                    updateLyrics(
                        track.videoId,
                        track.durationSeconds ?: 0,
                        null,
                        LyricsProvider.OFFLINE,
                    )
                }
            }
        }
    }

    fun loadSharedMediaItem(videoId: String) {
        viewModelScope.launch {
            val localSong = songRepository.getSongById(videoId).firstOrNull()
            if (localSong != null) {
                val track = localSong.toTrack()
                mediaPlayerHandler.setQueueData(
                    QueueData.Data(
                        listTracks = arrayListOf(track),
                        firstPlayedTrack = track,
                        playlistId = "RDAMVM$videoId",
                        playlistName = getString(Res.string.shared),
                        playlistType = PlaylistType.RADIO,
                        continuation = null,
                    ),
                )
                loadMediaItemFromTrack(track, SONG_CLICK)
            } else {
                streamRepository.getFullMetadata(videoId).collectLatest { response ->
                    val track = response.data
                    when (response) {
                        is Resource.Success if (track != null) -> {
                            mediaPlayerHandler.setQueueData(
                                QueueData.Data(
                                    listTracks = arrayListOf(track),
                                    firstPlayedTrack = track,
                                    playlistId = "RDAMVM$videoId",
                                    playlistName = getString(Res.string.shared),
                                    playlistType = PlaylistType.RADIO,
                                    continuation = null,
                                ),
                            )
                            loadMediaItemFromTrack(track, SONG_CLICK)
                        }

                        else -> {
                            log("Load shared media item error: ${response.message}", LogLevel.WARN)
                            makeToast("${getString(Res.string.error)}: ${response.message}")
                        }
                    }
                }
            }
        }
    }

    fun loadMediaItemFromTrack(
        track: Track,
        type: String,
        index: Int? = null,
    ) {
        viewModelScope.launch {
            mediaPlayerHandler.clearMediaItems()
            songRepository.insertSong(track.toSongEntity()).lastOrNull()?.let {
                println("insertSong: $it")
                songRepository
                    .getSongById(track.videoId)
                    .collect { songEntity ->
                        if (songEntity != null) {
                            Logger.w("Check like", "loadMediaItemFromTrack ${songEntity.liked}")
                            _liked.value = songEntity.liked
                        }
                    }
            }
            track.durationSeconds?.let {
                songRepository.updateDurationSeconds(
                    it,
                    track.videoId,
                )
            }
            withContext(Dispatchers.Main) {
                mediaPlayerHandler.addMediaItem(track.toGenericMediaItem(), playWhenReady = type != RECOVER_TRACK_QUEUE)
            }

            when (type) {
                SONG_CLICK -> {
                    mediaPlayerHandler.getRelated(track.videoId)
                }

                VIDEO_CLICK -> {
                    mediaPlayerHandler.getRelated(track.videoId)
                }

                SHARE -> {
                    mediaPlayerHandler.getRelated(track.videoId)
                }

                PLAYLIST_CLICK -> {
                    if (index == null) {
//                                        fetchSourceFromQueue(downloaded = downloaded ?: 0)
                        loadPlaylistOrAlbum(index = 0)
                    } else {
//                                        fetchSourceFromQueue(index!!, downloaded = downloaded ?: 0)
                        loadPlaylistOrAlbum(index = index)
                    }
                }

                ALBUM_CLICK -> {
                    if (index == null) {
//                                        fetchSourceFromQueue(downloaded = downloaded ?: 0)
                        loadPlaylistOrAlbum(index = 0)
                    } else {
//                                        fetchSourceFromQueue(index!!, downloaded = downloaded ?: 0)
                        loadPlaylistOrAlbum(index = index)
                    }
                }
            }
        }
    }

    fun onUIEvent(uiEvent: UIEvent) =
        viewModelScope.launch {
            when (uiEvent) {
                UIEvent.Backward -> {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.Backward,
                    )
                }

                UIEvent.Forward -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Forward)
                }

                UIEvent.PlayPause -> {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.PlayPause,
                    )
                }

                UIEvent.Next -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)
                }

                UIEvent.Previous -> {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.Previous,
                    )
                }

                UIEvent.SkipToPrevious -> {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.SkipToPrevious,
                    )
                }

                UIEvent.Stop -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Stop)
                }

                is UIEvent.UpdateProgress -> {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.UpdateProgress(
                            uiEvent.newProgress,
                        ),
                    )
                }

                UIEvent.Repeat -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Repeat)
                }

                UIEvent.Shuffle -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }

                UIEvent.ToggleLike -> {
                    Logger.w(tag, "ToggleLike")
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.ToggleLike)
                }

                UIEvent.Dislike -> mediaPlayerHandler.dislike()

                is UIEvent.UpdateVolume -> {
                    val newVolume = uiEvent.newVolume
                    // Apply to the player first: persisting to DataStore is a suspending disk write
                    // and must not sit between the user's gesture and the audible change.
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateVolume(newVolume))
                    dataStoreManager.setPlayerVolume(newVolume)
                }
            }
        }

    override fun onCleared() {
        Logger.w("Check onCleared", "onCleared")
    }

    fun getLocation() {
        viewModelScope.launch {
            regionCode = dataStoreManager.location.first()
        }
    }

    private fun checkAllDownloadingLocalPlaylists() {
        viewModelScope.launch {
            localPlaylistRepository.getAllDownloadingLocalPlaylists().collectLatest { playlists ->
                playlists.forEach { playlist ->
                    localPlaylistRepository.updateDownloadState(playlist.id, 0, successMessage = getString(Res.string.updated)).lastOrNull()
                }
            }
        }
    }

    private fun checkAllDownloadingPlaylists() {
        viewModelScope.launch {
            playlistRepository.getAllDownloadingPlaylist().collectLatest { list ->
                list.forEach { data ->
                    when (data) {
                        is AlbumEntity -> {
                            albumRepository.updateAlbumDownloadState(data.browseId, 0)
                        }

                        is PlaylistEntity -> {
                            playlistRepository.updatePlaylistDownloadState(data.id, 0)
                        }

                        else -> {
                            // Skip
                        }
                    }
                }
            }
        }
    }

    private fun checkAllDownloadingSongs() {
        viewModelScope.launch {
            songRepository.getDownloadingSongs().collect { songs ->
                songs?.forEach { song ->
                    songRepository.updateDownloadState(
                        song.videoId,
                        DownloadState.STATE_NOT_DOWNLOADED,
                    )
                }
            }
            songRepository.getPreparingSongs().collect { songs ->
                songs.forEach { song ->
                    songRepository.updateDownloadState(
                        song.videoId,
                        DownloadState.STATE_NOT_DOWNLOADED,
                    )
                }
            }
        }
    }

    private fun getFormat(mediaId: String?) {
        if (mediaId != _format.value?.videoId && !mediaId.isNullOrEmpty()) {
            _format.value = null
            _extractSource.value = streamRepository.getExtractSource(mediaId)
            getFormatFlowJob?.cancel()
            getFormatFlowJob =
                viewModelScope.launch {
                    streamRepository.getFormatFlow(mediaId).cancellable().collectLatest { f ->
                        Logger.w(tag, "Get format for $mediaId: $f")
                        if (f != null) {
                            _format.emit(f)
                        } else {
                            _format.emit(null)
                        }
                        // Re-read on every emission: the first one usually lands before the
                        // extractor has finished, so the source is only known on a later pass.
                        _extractSource.value = streamRepository.getExtractSource(mediaId)
                    }
                }
        }
    }

    private var songInfoJob: Job? = null

    fun getSongInfo(mediaId: String?) {
        songInfoJob?.cancel()
        songInfoJob =
            viewModelScope.launch {
                if (mediaId != null) {
                    songRepository.getSongInfo(mediaId).collect { song ->
                        _nowPlayingScreenData.update {
                            it.copy(
                                songInfoData = song,
                            )
                        }
                    }
                }
            }
    }

    fun stopPlayer() {
        _nowPlayingScreenData.value = NowPlayingScreenData.initial()
        _nowPlayingState.value = null
        mediaPlayerHandler.resetSongAndQueue()
        onUIEvent(UIEvent.Stop)
    }

    private fun loadPlaylistOrAlbum(index: Int? = null) {
        mediaPlayerHandler.loadPlaylistOrAlbum(index)
    }

    private suspend fun updateLyrics(
        videoId: String,
        duration: Int,
        foundLyrics: Lyrics?,
        foundProvider: LyricsProvider = LyricsProvider.SIMPMUSIC,
    ) {
        if (lyricsVideoId != null && videoId != lyricsVideoId) {
            Logger.d(tag, "Dropping lyrics for $videoId: now showing $lyricsVideoId")
            return
        }
        // YouTube Music's own text beats an untimed match from elsewhere (matched by title and
        // length, possibly another song): only a timed source may replace it.
        val youTubePlain = youTubePlainLyrics?.takeIf { it.first == videoId }?.second
        val untimed = foundLyrics?.syncType == null || foundLyrics.syncType == "UNSYNCED"
        val useYouTubePlain = youTubePlain != null && foundProvider != LyricsProvider.YOUTUBE_MUSIC && foundLyrics != null && untimed
        val inputLyrics = if (useYouTubePlain) youTubePlain else foundLyrics
        val lyricsProvider = if (useYouTubePlain) LyricsProvider.YOUTUBE_MUSIC else foundProvider
        if (inputLyrics == null) {
            _nowPlayingScreenData.update {
                it.copy(
                    lyricsData = null,
                )
            }
            return
        }

        val lyrics =
            inputLyrics.copy(
                lines =
                    inputLyrics.lines?.map { line ->
                        line.copy(
                            words = decodeHtmlEntities(line.words),
                        )
                    },
            )

        if (_nowPlayingState.value?.songEntity?.videoId == videoId) {
            _nowPlayingScreenData.update {
                it.copy(
                    lyricsData =
                        NowPlayingScreenData.LyricsData(
                            lyrics = lyrics,
                            lyricsProvider = lyricsProvider,
                            sourceText = youTubeMusicLyricsSource.takeIf { lyricsProvider == LyricsProvider.YOUTUBE_MUSIC },
                        ),
                )
            }
            // Save lyrics to database
            viewModelScope.launch {
                lyricsCanvasRepository.insertLyrics(
                    LyricsEntity(
                        videoId = videoId,
                        error = false,
                        lines = lyrics.lines,
                        syncType = lyrics.syncType,
                    ),
                )
            }
        }
    }

    private fun getLyricsFromFormat(
        isVideo: Boolean,
        song: SongEntity,
        duration: Int,
    ) {
        // One search at a time: the previous song's is cancelled, so its late answers (or its
        // final "none found") cannot land on this one. updateLyrics also drops anything not for
        // this song, for the paths that run outside the job.
        lyricsJob?.cancel()
        lyricsVideoId = song.videoId
        lyricsJob = viewModelScope.launch {
            val videoId = song.videoId
            log("Get Lyrics From Format for $videoId", LogLevel.WARN)
            val artistName = song.artistName
            val artist =
                if (artistName?.firstOrNull() != null &&
                    artistName
                        .firstOrNull()
                        ?.contains("Various Artists") == false
                ) {
                    artistName.firstOrNull()
                } else {
                    mediaPlayerHandler.nowPlaying
                        .first()
                        ?.metadata
                        ?.artist
                        ?: ""
                }
            // A fresh attempt: every source is eligible again for this track.
            triedLyricsSources.clear()
            youTubePlainLyrics = null
            youTubeMusicLyricsSource = null
            // No per-user provider any more (the setting went with the other non-YouTube-Music
            // options): every track takes the same best-timed-first order.
            tryLyricsSource(
                preferred = null,
                videoId = videoId,
                song = song,
                artist = artist ?: "",
                duration = duration,
            )
        }
    }

    /**
     * Which sources have already been asked for the track being loaded.
     *
     * Confined to the single coroutine that loads lyrics, so no synchronisation is needed; cleared
     * at the start of each attempt.
     */
    private val triedLyricsSources = mutableSetOf<String>()

    /** The running lyrics search; cancelled when the next song's starts. */
    private var lyricsJob: Job? = null

    /** The song whose lyrics are being searched for; results for any other are dropped. */
    private var lyricsVideoId: String? = null

    /**
     * Order the sources are consulted in once the user's choice has been tried.
     *
     * Shared with the Android Auto overlay, which resolves lyrics on its own — the phone and the
     * car asking in different orders would mean a track with lyrics in one and none in the other.
     */
    private val lyricsSourceOrder = LyricsSourceOrder.DEFAULT

    /**
     * Runs the next source that has not been tried, starting with [preferred].
     *
     * Every source used to hard-code which one it fell back to, which left BetterLyrics and YouTube
     * captions unreachable unless explicitly selected, and made LRCLIB a dead end. Routing every
     * miss through here means a track is only reported as having no lyrics once every source has
     * actually been asked.
     */
    private suspend fun tryLyricsSource(
        preferred: String?,
        videoId: String,
        song: SongEntity,
        artist: String,
        duration: Int,
    ) {
        val next =
            preferred?.takeIf { it !in triedLyricsSources }
                ?: lyricsSourceOrder.firstOrNull { it !in triedLyricsSources }

        if (next == null) {
            // Everything has been asked. Fall through to whatever was saved locally on an earlier
            // run; that function reports the empty case, so the user is told rather than left
            // watching a spinner that never resolves.
            getSavedLyrics(song.toTrack().copy(durationSeconds = duration))
            return
        }
        triedLyricsSources += next

        when (next) {
            LyricsSourceOrder.YOUTUBE_MUSIC -> getYouTubeMusicLyrics(videoId, song, artist, duration)
            DataStoreManager.SIMPMUSIC -> getSimpMusicLyrics(videoId, song, artist, duration)
            DataStoreManager.LRCLIB -> getLrclibLyrics(song, artist, duration)
            DataStoreManager.BETTER_LYRICS -> getBetterLyrics(song, artist, duration)
            DataStoreManager.YOUTUBE -> getYouTubeCaption(videoId, song, artist, duration)
            // An unrecognised stored value used to fall through the `when` and quietly load nothing.
            else -> tryLyricsSource(null, videoId, song, artist, duration)
        }
    }

    /**
     * YouTube Music's plain-text lyrics for the track being loaded, held back while the other
     * sources are asked for timed ones; shown only if none has any. Confined to the lyrics
     * coroutine like [triedLyricsSources].
     */
    private var youTubePlainLyrics: Pair<String, Lyrics>? = null

    /** The credit line for the YouTube Music lyrics being shown. */
    private var youTubeMusicLyricsSource: String? = null

    /**
     * YouTube Music's own lyrics: timed for this recording when YouTube has timing, which ends the
     * search. Plain text only is kept for the end of the chain, since a later source may still
     * have timing for it.
     */
    private suspend fun getYouTubeMusicLyrics(
        videoId: String,
        song: SongEntity,
        artist: String,
        duration: Int,
    ) {
        val response = lyricsCanvasRepository.getYouTubeMusicLyrics(videoId).lastOrNull()
        val data = response?.data
        if (response is Resource.Success && data != null) {
            val (lyrics, source) = data
            if (lyrics.syncType == "LINE_SYNCED") {
                Logger.d(tag, "YouTube Music timed lyrics for $videoId (${lyrics.lines?.size} lines, $source)")
                youTubeMusicLyricsSource = source
                updateLyrics(videoId, duration, lyrics, LyricsProvider.YOUTUBE_MUSIC)
                return
            }
            youTubePlainLyrics = videoId to lyrics
            youTubeMusicLyricsSource = source
        }
        tryLyricsSource(null, videoId, song, artist, duration)
    }

    private suspend fun getSimpMusicLyrics(
        videoId: String,
        song: SongEntity,
        artist: String?,
        duration: Int,
    ) {
        lyricsCanvasRepository.getSimpMusicLyrics(videoId).collectLatest {
            Logger.w(tag, "Get SimpMusic Lyrics for $videoId: $it")
            val data = it.data
            if (it is Resource.Success && data != null) {
                Logger.d(tag, "Get SimpMusic Lyrics Success")
                updateLyrics(
                    videoId,
                    duration,
                    data,
                    LyricsProvider.SIMPMUSIC,
                )
                insertLyrics(
                    data.toLyricsEntity(videoId),
                )
            } else {
                // Spotify used to sit here behind its own toggle, and LRCLIB was the hard-coded
                // tail. Both are now just entries in the chain, so nothing is skipped because a
                // switch happened to be off.
                tryLyricsSource(null, videoId, song, artist ?: "", duration)
            }
        }
    }

    private suspend fun getYouTubeCaption(
        videoId: String,
        song: SongEntity,
        artist: String?,
        duration: Int,
    ) {
        lyricsCanvasRepository
            .getYouTubeCaption(videoId)
            .cancellable()
            .collect { response ->
                val data = response.data
                when (response) {
                    is Resource.Success if (data != null) -> {
                        insertLyrics(data.toLyricsEntity(videoId))
                        updateLyrics(
                            videoId,
                            duration,
                            data,
                            LyricsProvider.YOUTUBE,
                        )
                    }

                    else -> {
                        tryLyricsSource(null, videoId, song, artist ?: "", duration)
                    }
                }
            }
    }

    private suspend fun getLrclibLyrics(
        song: SongEntity,
        artist: String,
        duration: Int,
    ) {
        run {
            lyricsCanvasRepository
                .getLrclibLyricsData(
                    artist,
                    song.title,
                    duration,
                ).collectLatest { res ->
                    val data = res.data
                    when (res) {
                        is Resource.Success if (data != null) -> {
                            Logger.d(tag, "Get Lyrics Data Success")
                            updateLyrics(
                                song.videoId,
                                duration,
                                res.data,
                                LyricsProvider.LRCLIB,
                            )
                            insertLyrics(
                                res.data?.toLyricsEntity(
                                    song.videoId,
                                ) ?: return@collectLatest,
                            )
                        }

                        else -> {
                            tryLyricsSource(null, song.videoId, song, artist, duration)
                        }
                    }
                }
        }
    }

    private suspend fun getBetterLyrics(
        song: SongEntity,
        artist: String,
        duration: Int,
    ) {
        run {
            lyricsCanvasRepository
                .getBetterLyrics(
                    artist,
                    song.title,
                    duration,
                ).collectLatest { res ->
                    val data = res.data
                    when (res) {
                        is Resource.Success if (data != null) -> {
                            Logger.d(tag, "Get BetterLyrics Success")
                            updateLyrics(
                                song.videoId,
                                duration,
                                data,
                                LyricsProvider.BETTER_LYRICS,
                            )
                            insertLyrics(
                                data.toLyricsEntity(
                                    song.videoId,
                                ),
                            )
                        }

                        else -> {
                            log("Get BetterLyrics Error: ${res.message}")
                            tryLyricsSource(null, song.videoId, song, artist, duration)
                        }
                    }
                }
        }
    }

    private fun setLyricsProvider() {
        viewModelScope.launch {
            val songEntity = nowPlayingState.value?.songEntity ?: return@launch
            val isVideo = nowPlayingState.value?.mediaItem?.isVideo() ?: false
            getLyricsFromFormat(isVideo, songEntity, timeline.value.total.toInt() / 1000)
        }
    }

    private var _recreateActivity: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val recreateActivity: StateFlow<Boolean> = _recreateActivity

    fun activityRecreate() {
        _recreateActivity.value = true
    }

    fun activityRecreateDone() {
        _recreateActivity.value = false
    }

    /**
     * YouTube Music's "Add to queue" for a song, album or playlist ([kind] picks its wording).
     * A single song used to be turned into "play next" while Autoplay was on, because the queue
     * end was behind every Autoplay song; it now goes ahead of them, as in YouTube Music.
     */
    fun addListToQueue(
        listTrack: ArrayList<Track>,
        kind: QueueItemKind = QueueItemKind.SONGS,
    ) {
        viewModelScope.launch {
            if (listTrack.isEmpty()) return@launch
            mediaPlayerHandler.addToQueue(listTrack)
            makeToast(
                when {
                    kind == QueueItemKind.ALBUM -> getString(Res.string.ytm_album_added_to_queue)
                    kind == QueueItemKind.PLAYLIST -> getString(Res.string.ytm_playlist_added_to_queue)
                    listTrack.size == 1 -> getString(Res.string.ytm_song_added_to_queue)
                    else -> getString(Res.string.ytm_songs_added_to_queue, listTrack.size)
                },
            )
        }
    }

    /** Swiping a song row: one song to the queue, with YouTube Music's confirmation. */
    fun addSongToQueue(track: Track) = addListToQueue(arrayListOf(track))

    fun addToYouTubeLiked() {
        viewModelScope.launch {
            val videoId = mediaPlayerHandler.nowPlaying.first()?.mediaId
            if (videoId != null) {
                val like = likeStatus.value
                if (!like) {
                    songRepository
                        .addToYouTubeLiked(
                            mediaPlayerHandler.nowPlaying.first()?.mediaId,
                        ).collect { response ->
                            if (response == 200) {
                                makeToast(getString(Res.string.added_to_youtube_liked))
                                getLikeStatus(videoId)
                            } else {
                                makeToast(getString(Res.string.error))
                            }
                        }
                } else {
                    songRepository
                        .removeFromYouTubeLiked(
                            mediaPlayerHandler.nowPlaying.first()?.mediaId,
                        ).collect {
                            if (it == 200) {
                                makeToast(getString(Res.string.removed_from_youtube_liked))
                                getLikeStatus(videoId)
                            } else {
                                makeToast(getString(Res.string.error))
                            }
                        }
                }
            }
        }
    }

    /**
     * Starts the radio behind [endpoint] (a mix, a station, an artist's shuffle) and plays its first
     * song, as YouTube Music's Mix and Shuffle buttons do. Songs only: video rows are left out by
     * the repository, so a station that is itself a live video plays the music around it.
     */
    fun playRadio(
        endpoint: YouTubeWatchEndpoint,
        title: String,
    ) {
        viewModelScope.launch {
            val result = songRepository.getRadioFromEndpoint(endpoint).firstOrNull()
            val tracks = result?.data?.first.orEmpty()
            if (result !is Resource.Success || tracks.isEmpty()) {
                makeToast(result?.message ?: "Couldn't start this radio")
                return@launch
            }
            setQueueData(
                QueueData.Data(
                    listTracks = ArrayList(tracks),
                    firstPlayedTrack = tracks.first(),
                    playlistId = endpoint.playlistId,
                    playlistName = title,
                    playlistType = PlaylistType.RADIO,
                    continuation = result.data?.second,
                ),
            )
            loadMediaItem(tracks.first(), PLAYLIST_CLICK, 0)
        }
    }

    private val _reloadDestination: MutableStateFlow<KClass<*>?> = MutableStateFlow(null)
    val reloadDestination: StateFlow<KClass<*>?> = _reloadDestination.asStateFlow()

    fun reloadDestination(destination: KClass<*>) {
        _reloadDestination.value = destination
    }

    fun reloadDestinationDone() {
        _reloadDestination.value = null
    }

    fun setBitmap(bitmap: ImageBitmap?) {
        _nowPlayingScreenData.update {
            it.copy(bitmap = bitmap)
        }
    }

    // Kept warm so these synchronous checks (an activity's onDestroy, the player) never block on
    // DataStore, whose reads now wait for pending writes.
    private val killServiceOnExit = dataStoreManager.killServiceOnExit.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val signedInCookie = dataStoreManager.cookie.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun shouldStopMusicService(): Boolean = killServiceOnExit.value == TRUE

    fun isUserLoggedIn(): Boolean = signedInCookie.value.isNotEmpty()

    // Flow-based variant of [isUserLoggedIn] so composables can collect login state once
    // instead of calling runBlocking inside composition (used by NowPlayingScreenContent).
    fun isUserLoggedInFlow(): Flow<Boolean> = dataStoreManager.cookie.map { it.isNotEmpty() }
}

sealed class UIEvent {
    data object PlayPause : UIEvent()

    data object Backward : UIEvent()

    data object Forward : UIEvent()

    data object Next : UIEvent()

    data object Previous : UIEvent()

    /**
     * Always advances to the previous track — bypasses the 3-second
     * "seek to start of current track" rule used by [Previous]. Used by the
     * NowPlaying artwork pager swipe.
     */
    data object SkipToPrevious : UIEvent()

    data object Stop : UIEvent()

    data object Shuffle : UIEvent()

    data object Repeat : UIEvent()

    data class UpdateProgress(
        val newProgress: Float,
    ) : UIEvent()

    data class UpdateVolume(
        val newVolume: Float,
    ) : UIEvent()

    data object ToggleLike : UIEvent()

    /** YouTube Music's thumbs down: rates the song down and skips it. */
    data object Dislike : UIEvent()
}

enum class LyricsProvider {
    YOUTUBE_MUSIC,
    SIMPMUSIC,
    YOUTUBE,
    LRCLIB,
    BETTER_LYRICS,
    OFFLINE,
}

data class NowPlayingScreenData(
    val playlistName: String,
    val nowPlayingTitle: String,
    val artistName: String,
    val isVideo: Boolean,
    val isExplicit: Boolean = false,
    val thumbnailURL: String?,
    val lyricsData: LyricsData? = null,
    val songInfoData: SongInfoEntity? = null,
    val bitmap: ImageBitmap? = null,
) {
    data class LyricsData(
        val lyrics: Lyrics,
        val lyricsProvider: LyricsProvider,
        /** YouTube's own credit line ("Source: Musixmatch"), shown as is when present. */
        val sourceText: String? = null,
    )

    companion object {
        fun initial(): NowPlayingScreenData =
            NowPlayingScreenData(
                nowPlayingTitle = "",
                artistName = "",
                isVideo = false,
                thumbnailURL = null,
                lyricsData = null,
                songInfoData = null,
                playlistName = "",
            )
    }
}

/** What "Add to queue" was used on, for YouTube Music's wording of the confirmation. */
enum class QueueItemKind { SONGS, ALBUM, PLAYLIST }
