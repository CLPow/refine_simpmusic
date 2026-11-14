package com.maxrave.data.mediaservice

import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.repository.RemoteCommand
import com.maxrave.domain.repository.RemotePlayback
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val REMOTE_PROGRESS_TICK_MS = 500L

private const val PENDING_QUEUE_TTL_MS = 15_000L

private const val SEEK_STEP_MS = 5_000L

internal class RoutingMediaPlayerHandler(
    private val local: MediaPlayerHandler,
    private val control: RefineControlRepository,
    scope: CoroutineScope,
) : MediaPlayerHandler by local {
    private val remoteNowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
    private val remoteNowPlaying = MutableStateFlow<GenericMediaItem?>(null)
    private val remoteQueue = MutableStateFlow<QueueData?>(QueueData())
    private val remoteControlState =
        MutableStateFlow(
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
    private val remoteMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    private val remoteIndex = MutableStateFlow(0)

    private var pendingQueue: QueueData.Data? = null
    private var pendingQueueJob: Job? = null
    private var pendingItem: Pair<String, Boolean>? = null
    private val scope = scope

    private val isRemote: Boolean get() = control.target.value is ControlTarget.Remote

    private fun startingSomethingNew() {
        if (isRemote && control.autoAttached) control.controlThisDevice()
    }

    override val simpleMediaState: StateFlow<SimpleMediaState> = routed(local.simpleMediaState, remoteMediaState)
    override val nowPlaying: StateFlow<GenericMediaItem?> = routed(local.nowPlaying, remoteNowPlaying)
    override val queueData: StateFlow<QueueData?> = routed(local.queueData, remoteQueue)
    override val controlState: StateFlow<ControlState> = routed(local.controlState, remoteControlState)
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = routed(local.nowPlayingState, remoteNowPlayingState)
    override val currentSongIndex: StateFlow<Int> = routed(local.currentSongIndex, remoteIndex)

    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = routed(local.skipSegments, MutableStateFlow(null))
    override val format: StateFlow<NewFormatEntity?> = routed(local.format, MutableStateFlow(null))

    init {
        scope.launch {
            control.remote.collectLatest { remote ->
                if (remote == null) return@collectLatest
                mirror(remote)
                while (remote.isPlaying && !remote.loading) {
                    delay(REMOTE_PROGRESS_TICK_MS)
                    remoteMediaState.value = SimpleMediaState.Progress(remote.currentPositionMs())
                }
            }
        }
    }

    private fun <T> routed(
        own: StateFlow<T>,
        remote: StateFlow<T>,
    ): StateFlow<T> = RoutedStateFlow(control.target, own, remote)

    private fun mirror(remote: RemotePlayback) {
        val track = remote.track
        val before = remoteNowPlayingState.value
        if (track == null) {
            remoteNowPlayingState.value = NowPlayingTrackState.initial()
            remoteNowPlaying.value = null
        } else if (before.track != track || before.songEntity?.liked != remote.liked) {
            val item = track.toGenericMediaItem()
            remoteNowPlayingState.value = NowPlayingTrackState(item, track, track.toSongEntity().copy(liked = remote.liked))
            remoteNowPlaying.value = item
        }
        if (pendingQueue == null) remoteQueue.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, remote.queue)
        remoteIndex.value = remote.index.coerceAtLeast(0)
        remoteControlState.value =
            ControlState(
                isPlaying = remote.isPlaying,
                isShuffle = remote.shuffle,
                repeatState = remote.repeat,
                isLiked = remote.liked,
                isNextAvailable = remote.nextAvailable,
                isPreviousAvailable = remote.previousAvailable,
                volume = remote.volume,
            )
        val position = remote.currentPositionMs()
        remoteMediaState.value = if (remote.loading) SimpleMediaState.Buffering(position) else SimpleMediaState.Progress(position)
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        if (!isRemote) return local.onPlayerEvent(playerEvent)
        val remote = control.remote.value
        when (playerEvent) {
            PlayerEvent.PlayPause -> control.send(if (remote?.isPlaying == true) RemoteCommand.Pause else RemoteCommand.Play)
            PlayerEvent.Next -> control.send(RemoteCommand.Next)
            PlayerEvent.Previous -> control.send(RemoteCommand.Previous)
            PlayerEvent.SkipToPrevious -> control.send(RemoteCommand.SkipToPrevious)
            PlayerEvent.Backward -> seekRemote((getProgress() - SEEK_STEP_MS).coerceAtLeast(0L))
            PlayerEvent.Forward -> seekRemote(getProgress() + SEEK_STEP_MS)
            PlayerEvent.Shuffle -> control.send(RemoteCommand.ToggleShuffle)
            PlayerEvent.Repeat -> control.send(RemoteCommand.CycleRepeat)
            PlayerEvent.ToggleLike -> control.send(RemoteCommand.ToggleLike)
            is PlayerEvent.UpdateVolume -> control.send(RemoteCommand.SetVolume(playerEvent.newVolume.coerceIn(0f, 1f)))
            is PlayerEvent.UpdateProgress -> {
                val duration = getPlayerDuration()
                if (duration > 0L) seekRemote((duration * (playerEvent.newProgress / 100f)).toLong())
            }
            PlayerEvent.Stop -> control.controlThisDevice()
        }
    }

    private fun seekRemote(positionMs: Long) {
        val duration = getPlayerDuration()
        control.send(RemoteCommand.SeekTo(if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs))
    }

    override fun toggleLike() {
        if (isRemote) control.send(RemoteCommand.ToggleLike) else local.toggleLike()
    }

    override fun dislike() {
        if (isRemote) control.send(RemoteCommand.Dislike) else local.dislike()
    }

    override fun like(liked: Boolean) {
        if (!isRemote) local.like(liked)
    }

    override fun toggleRadio() {
        if (!isRemote) local.toggleRadio()
    }

    override fun resetSongAndQueue() {
        if (!isRemote) local.resetSongAndQueue()
    }

    override fun removeMediaItem(position: Int) {
        if (isRemote) control.send(RemoteCommand.RemoveAt(position)) else local.removeMediaItem(position)
    }

    override fun playMediaItemInMediaSource(index: Int) {
        if (isRemote) control.send(RemoteCommand.PlayIndex(index)) else local.playMediaItemInMediaSource(index)
    }

    override fun currentSongIndex(): Int = if (isRemote) remoteIndex.value else local.currentSongIndex()

    override fun currentOrderIndex(): Int = if (isRemote) remoteIndex.value else local.currentOrderIndex()

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
        if (isRemote) control.send(RemoteCommand.Move(from, to)) else local.swap(from, to)
    }

    override suspend fun moveItemUp(position: Int) {
        if (isRemote) control.send(RemoteCommand.Move(position, position - 1)) else local.moveItemUp(position)
    }

    override suspend fun moveItemDown(position: Int) {
        if (isRemote) control.send(RemoteCommand.Move(position, position + 1)) else local.moveItemDown(position)
    }

    override suspend fun playNext(track: Track) {
        if (isRemote) control.send(RemoteCommand.PlayNext(track)) else local.playNext(track)
    }

    override suspend fun addToQueue(tracks: List<Track>) {
        if (isRemote) control.send(RemoteCommand.AddToQueue(tracks)) else local.addToQueue(tracks)
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = if (isRemote) remoteNowPlaying.value else local.getCurrentMediaItem()

    override fun getPlayerDuration(): Long = if (isRemote) control.remote.value?.durationMs ?: 0L else local.getPlayerDuration()

    override fun getProgress(): Long = if (isRemote) control.remote.value?.currentPositionMs() ?: 0L else local.getProgress()

    override fun loadMore() {
        if (!isRemote) local.loadMore()
    }

    override suspend fun replaceUpcomingRadio(endpoint: YouTubeWatchEndpoint): Boolean = if (isRemote) false else local.replaceUpcomingRadio(endpoint)

    override fun reset() {
        if (!isRemote) local.reset()
    }

    override fun setQueueData(queueData: QueueData.Data) {
        startingSomethingNew()
        if (!isRemote) return local.setQueueData(queueData)
        pendingQueue = queueData
        remoteQueue.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, queueData)
        pendingQueueJob?.cancel()
        pendingQueueJob =
            scope.launch {
                delay(PENDING_QUEUE_TTL_MS)
                pendingQueue = null
                control.remote.value?.let { remoteQueue.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, it.queue) }
            }
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        startingSomethingNew()
        if (!isRemote) return local.loadMediaItem(anyTrack, type, index)
        val track =
            when (anyTrack) {
                is Track -> anyTrack
                is SongEntity -> anyTrack.toTrack()
                else -> return
            }
        playOnRemote(track, type, index)
    }

    override fun clearMediaItems() {
        if (!isRemote) local.clearMediaItems()
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        if (!isRemote) return local.addMediaItem(mediaItem, playWhenReady)
        pendingItem = mediaItem.mediaId to playWhenReady
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        if (!isRemote) local.addMediaItemList(mediaItemList)
    }

    override fun getRelated(videoId: String) {
        if (!isRemote) return local.getRelated(videoId)
        playPendingItem(videoId, SONG_CLICK, null)
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        if (!isRemote) return local.loadPlaylistOrAlbum(index)
        playPendingItem(pendingItem?.first ?: return, PLAYLIST_CLICK, index)
    }

    private fun playPendingItem(
        videoId: String,
        type: String,
        index: Int?,
    ) {
        val item = pendingItem
        pendingItem = null
        if (item?.second == false) return
        val queue = pendingQueue ?: return
        val track = queue.listTracks.firstOrNull { it.videoId == videoId } ?: queue.firstPlayedTrack ?: return
        playOnRemote(track, type, index)
    }

    private fun playOnRemote(
        track: Track,
        type: String,
        index: Int?,
        shuffle: Boolean = false,
    ) {
        val queue = pendingQueue ?: QueueData.Data(listTracks = arrayListOf(track), firstPlayedTrack = track)
        pendingQueueJob?.cancel()
        pendingQueue = null
        control.send(RemoteCommand.PlayQueue(queue, track, type, index, shuffle))
    }

    override fun shufflePlaylist(randomTrackIndex: Int) {
        startingSomethingNew()
        if (!isRemote) return local.shufflePlaylist(randomTrackIndex)
        val queue = pendingQueue ?: return
        val track = queue.firstPlayedTrack ?: queue.listTracks.getOrNull(randomTrackIndex) ?: return
        val index = queue.listTracks.indexOfFirst { it.videoId == track.videoId }.takeIf { it >= 0 }
        playOnRemote(track, PLAYLIST_CLICK, index, shuffle = true)
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (!isRemote) local.addFirstMediaItemToIndex(mediaItem, index)
    }

    override fun addQueueToPlayer() {
        if (!isRemote) local.addQueueToPlayer()
    }

    override fun setCurrentSongIndex(index: Int) {
        if (!isRemote) local.setCurrentSongIndex(index)
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class RoutedStateFlow<T>(
    private val target: StateFlow<ControlTarget>,
    private val own: StateFlow<T>,
    private val remote: StateFlow<T>,
) : StateFlow<T> {
    override val value: T get() = if (target.value is ControlTarget.Remote) remote.value else own.value

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        target
            .map { it is ControlTarget.Remote }
            .distinctUntilChanged()
            .flatMapLatest { if (it) remote else own }
            .conflate()
            .distinctUntilChanged()
            .collect(collector)
        awaitCancellation()
    }
}
