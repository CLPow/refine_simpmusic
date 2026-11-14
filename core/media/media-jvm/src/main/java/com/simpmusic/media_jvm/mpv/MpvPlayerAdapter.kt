package com.simpmusic.media_jvm.mpv

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.simpmusic.media_jvm.download.getDownloadPath
import com.simpmusic.media_jvm.memory.MemoryTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MpvPlayerAdapter"

private const val MPV_ERROR_CODE = 2000

private const val PRECACHE_MAX_AGE_MS = 3 * 60 * 60 * 1000L

private const val MAX_RECOVERIES_PER_TRACK = 4

private const val CONFIRM_PROGRESS_MS = 500L

class MpvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    private enum class InternalState {
        IDLE,
        PREPARING,
        READY,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR,
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    init {
        if (MpvLibrary.INSTANCE == null) {
            Logger.e(TAG, "libmpv not available — playback will fail until mpv is installed.")
        }
    }

    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: MpvPlayer? = null

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var pendingSeek: Pair<Int, Long>? = null

    private var internalVolume = 1.0f

    @Volatile
    private var internalSleepFadeFactor = 1.0f

    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    private var positionUpdateJob: Job? = null

    private class PrecachedPlayer(
        val player: MpvPlayer,
        val mediaItem: GenericMediaItem,
        val source: PlayableSource,
    ) {
        private val createdAtMs = System.currentTimeMillis()

        @Volatile
        var failed = false

        fun isUsable(): Boolean = !failed && System.currentTimeMillis() - createdAtMs < PRECACHE_MAX_AGE_MS
    }

    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    private var recoveryCount = 0
    private var recoveryVideoId: String? = null

    @Volatile
    private var confirmFromMs = 0L

    @Volatile
    private var playbackConfirmed = false

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    private var currentLoadJob: Job? = null

    fun getCurrentPlayer(): MpvPlayer? = currentPlayer

    private data class PlayableSource(
        val url: String,
    )

    override fun play() {
        Logger.d(TAG, "play() called (current state: $internalState)")
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        if (internalState == InternalState.ENDED) {
                            Logger.d(TAG, "Play: replaying from the start after end of queue")
                            player.seekTo(0L)
                            cachedPosition = 0L
                        }
                        Logger.d(TAG, "Play: calling mpv play")
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
                    if (!cachedIsLoading) {
                        cachedIsLoading = true
                        notifyListeners { onIsLoadingChanged(true) }
                    }
                    internalPlayWhenReady = true
                    Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    cachedIsLoading = false
                }

                InternalState.ERROR -> {
                    val index = localCurrentMediaItemIndex
                    val videoId = playlist.getOrNull(index)?.mediaId
                    if (videoId != null) {
                        Logger.d(TAG, "Play: reloading $videoId after an error")
                        internalPlayWhenReady = true
                        withContext(Dispatchers.IO) { streamRepository.invalidateFormat(videoId) }
                        loadAndPlayTrackInternal(index, cachedPosition.coerceAtLeast(0L), shouldPlay = true, announce = false)
                    }
                }

                else -> {
                    Logger.w(TAG, "Play: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun pause() {
        Logger.d(TAG, "pause() called (current state: $internalState)")
        coroutineScope.launch {
            try {
                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            Logger.d(TAG, "Pause: calling mpv pause")
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                        Logger.d(TAG, "Pause: During PREPARING - will not auto-play")
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                if (internalSleepFadeFactor < 1f) {
                    internalSleepFadeFactor = 1f
                    forEachLiveHandle { it.setSleepFadeVolume(100) }
                }
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        cachedPosition = positionMs
        coroutineScope.launch {
            val player = currentPlayer
            if (player == null) {
                pendingSeek = localCurrentMediaItemIndex to positionMs
                Logger.d(TAG, "Seek deferred to load: $positionMs")
            } else {
                try {
                    player.seekTo(positionMs)
                    Logger.d(TAG, "Seeked to position: $positionMs")
                    notifyListeners { onSeeked(positionMs) }
                } catch (e: Exception) {
                    pendingSeek = localCurrentMediaItemIndex to positionMs
                    Logger.e(TAG, "Seek exception, deferred to load: ${e.message}", e)
                }
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return

        coroutineScope.launch {
            val shouldPlay = internalPlayWhenReady

            currentLoadJob?.cancel()

            localCurrentMediaItemIndex = mediaItemIndex
            currentPlayer?.release()
            currentPlayer = null
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (cachedPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val newPosition = (cachedPosition + 5000).coerceAtMost(cachedDuration)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            if (hasNextMediaItem()) {
                seekTo(getNextMediaItemIndex(), 0)
            }
        }
    }

    override fun seekToPrevious() = previous(andPlay = false)

    private fun previous(andPlay: Boolean) {
        coroutineScope.launch {
            val positionThresholdMs = 3000L
            if (cachedPosition > positionThresholdMs || !hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms — seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
                if (andPlay) play()
            } else {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms <= ${positionThresholdMs}ms — going to previous track")
                seekTo(getPreviousMediaItemIndex(), 0)
            }
        }
    }

    override fun skipToNext() {
        internalPlayWhenReady = true
        seekToNext()
    }

    override fun skipToPrevious() {
        internalPlayWhenReady = true
        previous(andPlay = true)
    }

    override fun skipToPreviousMediaItem() {
        internalPlayWhenReady = true
        seekToPreviousMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    private fun pointAtFirstItemIfUnset() {
        if (localCurrentMediaItemIndex < 0 && playlist.isNotEmpty()) localCurrentMediaItemIndex = 0
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)
        pointAtFirstItemIfUnset()

        if (internalShuffleModeEnabled) {
            createShuffleOrder()
        }

        notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

        if (playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount) {
            coroutineScope.launch {
                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()
            }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index in 0..playlist.size) {
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            pointAtFirstItemIfUnset()

            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                coroutineScope.launch {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        coroutineScope.launch {
            if (index !in playlist.indices) return@launch
            val track = playlist.removeAt(index)

            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coroutineScope.launch {
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@launch
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun moveMediaItemInPlayOrder(
        fromPosition: Int,
        toPosition: Int,
    ) {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            moveMediaItem(fromPosition, toPosition)
            return
        }
        coroutineScope.launch {
            if (fromPosition !in shuffleOrder.indices || toPosition !in shuffleOrder.indices) return@launch
            val moved = shuffleOrder.removeAt(fromPosition)
            shuffleOrder.add(toPosition, moved)
            shuffleIndices.clear()
            shuffleIndices.addAll(List(playlist.size) { 0 })
            shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
                if (originalIndex < shuffleIndices.size) shuffleIndices[originalIndex] = shuffledPos
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in playlist.indices) return

        coroutineScope.launch {
            val replaced = playlist[index]
            playlist[index] = mediaItem

            precachedPlayers.remove(replaced.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
        if (internalShuffleModeEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    override val isPlaying: Boolean
        get() = internalState == InternalState.PLAYING

    override val currentPosition: Long
        get() = cachedPosition

    override val duration: Long
        get() = cachedDuration

    override val bufferedPosition: Long
        get() = cachedBufferedPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = cachedPosition

    override val playbackState: Int
        get() =
            when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }

    private val navigationRepeatMode: Int
        get() = if (internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE) PlayerConstants.REPEAT_MODE_OFF else internalRepeatMode

    override fun hasNextMediaItem(): Boolean =
        when (navigationRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos >= 0 && currentShufflePos < shuffleOrder.lastIndex
                } else {
                    localCurrentMediaItemIndex < playlist.size - 1
                }
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (navigationRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos > 0
                } else {
                    localCurrentMediaItemIndex > 0
                }
        }

    private fun getNextMediaItemIndex(): Int =
        when (navigationRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex < playlist.size - 1) {
                        localCurrentMediaItemIndex + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (navigationRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex > 0) {
                        localCurrentMediaItemIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
                }
            }
        }

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            if (internalShuffleModeEnabled == value) return

            internalShuffleModeEnabled = value

            if (value) {
                createShuffleOrder()
            } else {
                clearShuffleOrder()
            }

            val mediaItemList = getShuffledMediaItemList()
            notifyListeners { onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            notifyListeners { onRepeatModeChanged(value) }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            coroutineScope.launch {
                forEachLiveHandle {
                    try {
                        it.setRate(value.speed)
                        it.applyPitch()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to set playback speed/pitch: ${e.message}")
                    }
                }
            }
        }

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            coroutineScope.launch {
                val percent = (internalVolume * 100).toInt()
                forEachLiveHandle { it.setMasterVolume(percent) }
            }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            coroutineScope.launch {
                val percent = (internalSleepFadeFactor * 100).toInt()
                forEachLiveHandle { it.setSleepFadeVolume(percent) }
            }
        }

    private fun forEachLiveHandle(action: (MpvPlayer) -> Unit) {
        currentPlayer?.let(action)
        precachedPlayers.values.forEach { action(it.player) }
    }

    private fun MpvPlayer.applyPlaybackLevels() {
        setVolumeLevels(
            master = (internalVolume * 100).toInt(),
            sleep = (internalSleepFadeFactor * 100).toInt(),
        )
        setRate(internalPlaybackSpeed)
        applyPitch()
    }

    private fun MpvPlayer.applyPitch() {
        if (internalPlaybackPitch == 1.0f) {
            clearAudioFilters()
        } else {
            if (installPitchShift()) {
                setPitchScale(internalPlaybackPitch)
            } else {
                Logger.w(TAG, "Pitch shift unavailable: mpv rejected the rubberband filter")
            }
        }
    }

    override var skipSilenceEnabled: Boolean = false

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()

    }

    private fun transitionToState(
        newState: InternalState,
        emitError: Boolean = true,
    ) {
        if (internalState == newState) return

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        currentPlayer?.let { player ->
            val dur = player.length
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("paused")
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("idle")
            }

            InternalState.PREPARING -> {
                Logger.d(TAG, "transitionToState PREPARING -> isLoading=true")
                cachedIsLoading = true
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }

            InternalState.READY -> {
                Logger.d(TAG, "transitionToState READY -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                Logger.d(TAG, "transitionToState PLAYING -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                if (emitError) {
                    listeners.forEach {
                        it.onPlayerError(
                            PlayerError(
                                errorCode = 403,
                                errorCodeName = "ERROR_UNKNOWN",
                                message = "Can not extract playable URL or playback error",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun trimNativeMemory(reason: String) {
        coroutineScope.launch(Dispatchers.IO) {
            MemoryTrimmer.trim(reason)
        }
    }

    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
        announce: Boolean = true,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId
        if (announce && recoveryVideoId != videoId) {
            recoveryVideoId = videoId
            recoveryCount = 0
        }

        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    transitionToState(InternalState.PREPARING)

                    if (announce) {
                        notifyListeners {
                            onMediaItemTransition(
                                mediaItem,
                                PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }

                    cleanupEventListenerInternal()
                    stopPositionUpdates()

                    val cachedPrecache =
                        precachedPlayers.remove(videoId)?.let { cached ->
                            if (cached.isUsable()) {
                                cached
                            } else {
                                Logger.w(TAG, "Precached handle for $videoId failed or is too old; loading it again")
                                cleanupPlayerInternal(cached.player)
                                null
                            }
                        }
                    var resolvedSource: PlayableSource? = null
                    val player =
                        if (cachedPrecache != null) {
                            cachedPrecache.player
                        } else {
                            var source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            if (source == null || source.url.isEmpty()) {
                                Logger.w(TAG, "First extract failed for $videoId, invalidating and retrying...")
                                withContext(Dispatchers.IO) {
                                    streamRepository.invalidateFormat(videoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                                }
                                source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            }
                            if (source == null || source.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract playable URL for $videoId after retry")
                                transitionToState(InternalState.ERROR)
                                return@launch
                            }
                            resolvedSource = source
                            createMediaPlayerInternal(source, mediaItem.mediaId)
                        }

                    if (player == null) {
                        Logger.e(TAG, "Failed to create mpv player for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }

                    cleanupCurrentPlayerInternal()
                    currentPlayer = player
                    setupPlayerEventsInternal(player)
                    player.applyPlaybackLevels()

                    if (cachedPrecache != null) {
                        if (shouldPlay) {
                            player.play()
                        }
                        Logger.d(TAG, "Playing from precache for $videoId")
                    } else {
                        val source = resolvedSource
                        if (source != null) {
                            player.loadFile(buildPlaybackUrl(source), startPaused = !shouldPlay)
                        } else {
                            Logger.e(TAG, "resolvedSource is null — should not happen")
                            transitionToState(InternalState.ERROR)
                            return@launch
                        }
                    }

                    // Read and cleared unconditionally so a request for a track that never loads cannot linger.
                    val deferred = pendingSeek?.takeIf { it.first == index }?.second
                    pendingSeek = null
                    val resumeAt = deferred ?: startPositionMs
                    confirmFromMs = resumeAt.coerceAtLeast(0L)
                    playbackConfirmed = false
                    if (resumeAt > 0) {
                        delay(100)
                        player.seekTo(resumeAt)
                        cachedPosition = resumeAt
                    }

                    transitionToState(InternalState.READY)

                    startPositionUpdates()

                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Load track error: ${e.message}", e)
                        transitionToState(InternalState.ERROR)
                    }
                }
            }
    }

    private fun buildPlaybackUrl(source: PlayableSource): String = source.url

    private fun createMediaPlayerInternal(
        source: PlayableSource,
        mediaId: String,
    ): MpvPlayer? {
        val player = MpvPlayer.create(networkCacheSeconds = 10)
        player?.setMasterVolume((internalVolume * 100).toInt())
        return player
    }

    private fun setupPlayerEventsInternal(player: MpvPlayer) {
        cleanupEventListenerInternal()

        val listener =
            object : MpvPlayerEventAdapter() {
                override fun finished(player: MpvPlayer) {
                    Logger.d(TAG, "End of stream reached")
                    coroutineScope.launch {
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring finished() from stale player")
                            return@launch
                        }
                        transitionToState(InternalState.ENDED)
                        handleTrackEndInternal()
                    }
                }

                override fun error(player: MpvPlayer) {
                    Logger.e(TAG, "mpv playback error")
                    coroutineScope.launch {
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring error() from stale player")
                            return@launch
                        }
                        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                        if (currentVideoId != null) {
                            if (retryVideoId != currentVideoId) {
                                retryVideoId = currentVideoId
                                retryCount = 0
                            }
                            if (retryCount < maxRetryCount && recoveryCount < MAX_RECOVERIES_PER_TRACK) {
                                retryCount++
                                recoveryCount++
                                Logger.w(TAG, "Retrying playback (attempt $retryCount/$maxRetryCount) for $currentVideoId")
                                val resumePositionMs = cachedPosition.coerceAtLeast(0L)
                                try {
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, resumePositionMs, shouldPlay = true, announce = false)
                                    return@launch
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                }
                            }
                            Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                        }
                        val error =
                            PlayerError(
                                errorCode = MPV_ERROR_CODE,
                                errorCodeName = "MPV_ERROR",
                                message = "Playback error",
                            )
                        listeners.forEach { it.onPlayerError(error) }
                        transitionToState(InternalState.ERROR, emitError = false)
                    }
                }

                override fun playing(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            retryCount = 0
                            retryVideoId = null
                        }
                    }
                }

                override fun paused(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        if (internalState == InternalState.PLAYING) {
                            transitionToState(InternalState.PAUSED)
                        }
                    }
                }

                override fun timeChanged(
                    player: MpvPlayer,
                    newTimeMs: Long,
                ) {
                    cachedPosition = newTimeMs
                    if (!playbackConfirmed && newTimeMs > confirmFromMs + CONFIRM_PROGRESS_MS) {
                        playbackConfirmed = true
                        coroutineScope.launch {
                            if (currentPlayer === player) notifyListeners { onPlaybackConfirmed() }
                        }
                    }
                }

                override fun lengthChanged(
                    player: MpvPlayer,
                    newLengthMs: Long,
                ) {
                    if (newLengthMs > 0) {
                        cachedDuration = newLengthMs
                    }
                }

                override fun buffering(
                    player: MpvPlayer,
                    newCache: Float,
                ) {
                    if (cachedDuration > 0) {
                        cachedBufferedPosition = (cachedDuration * newCache / 100f).toLong()
                    }

                    val isStalled = newCache < 100f && cachedBufferedPosition <= cachedPosition && internalPlayWhenReady
                    if (isStalled != cachedIsLoading) {
                        Logger.w(TAG, "isLoading changed: $cachedIsLoading -> $isStalled")
                        cachedIsLoading = isStalled
                        notifyListeners { onIsLoadingChanged(isStalled) }
                    }
                }

                override fun opening(player: MpvPlayer) {
                    Logger.d(TAG, "mpv opening media")
                }
            }

        player.setEventListener(listener)
    }

    private fun cleanupEventListenerInternal() {
        currentPlayer?.setEventListener(null)
    }

    private fun cleanupPlayerInternal(player: MpvPlayer) {
        try {
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupEventListenerInternal()

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    private fun handleTrackEndInternal() {
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                seekTo(localCurrentMediaItemIndex, 0)
            }

            else -> {
                if (hasNextMediaItem()) {
                    seekToNext()
                }
            }
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        if (internalState == InternalState.PLAYING ||
                            internalState == InternalState.READY ||
                            internalState == InternalState.PAUSED
                        ) {
                            val player = currentPlayer
                            if (player != null) {
                                val pos = player.time
                                val dur = player.length
                                if (pos > 0) cachedPosition = pos
                                if (dur > 0) cachedDuration = dur
                            }
                        }
                    } catch (_: Exception) {
                    }

                    delay(200)
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    val upcoming = upcomingIndices(maxPrecacheCount)
                    val indicesToPrecache = upcoming.filter { !precachedPlayers.containsKey(playlist.getOrNull(it)?.mediaId) }

                    val wanted = upcoming.mapNotNull { playlist.getOrNull(it)?.mediaId }.toSet()
                    precachedPlayers.entries.removeIf { (videoId, cached) ->
                        val stale = (videoId !in wanted && videoId != currentMediaItem?.mediaId) || !cached.isUsable()
                        if (stale) cleanupPlayerInternal(cached.player)
                        stale
                    }

                    for (idx in indicesToPrecache) {
                        if (!isActive) break

                        val mediaItem = playlist.getOrNull(idx) ?: continue

                        val source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }

                        if (source != null && source.url.isNotEmpty()) {
                            try {
                                val player = createMediaPlayerInternal(source, mediaItem.mediaId)
                                if (player == null) {
                                    Logger.w(TAG, "Precaching skipped for $idx: could not create mpv player")
                                } else {
                                    val precached = PrecachedPlayer(player, mediaItem, source)
                                    player.setEventListener(
                                        object : MpvPlayerEventAdapter() {
                                            override fun error(player: MpvPlayer) {
                                                precached.failed = true
                                            }

                                            override fun finished(player: MpvPlayer) {
                                                precached.failed = true
                                            }
                                        },
                                    )
                                    player.applyPlaybackLevels()
                                    player.loadFile(buildPlaybackUrl(source), startPaused = true)
                                    precachedPlayers.put(mediaItem.mediaId, precached)?.let { cleanupPlayerInternal(it.player) }
                                    Logger.d(TAG, "Precached player for index $idx")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Precaching error for $idx: ${e.message}")
                            }
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    private fun upcomingIndices(count: Int): List<Int> {
        val current = localCurrentMediaItemIndex
        val shuffled = internalShuffleModeEnabled && shuffleOrder.isNotEmpty()
        val order = if (shuffled) shuffleOrder.toList() else playlist.indices.toList()
        val position = if (shuffled) shuffleIndices.getOrNull(current) ?: return emptyList() else current
        if (position !in order.indices) return emptyList()
        val result = mutableListOf<Int>()
        for (offset in 1..count) {
            var next = position + offset
            if (next >= order.size) {
                if (internalRepeatMode != PlayerConstants.REPEAT_MODE_ALL) break
                next %= order.size
            }
            val index = order[next]
            if (index != current && index !in result) result += index
        }
        return result
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        precachedPlayers.entries.removeIf { (videoId, cached) ->
            if (videoId != currentMediaItem?.mediaId) {
                cleanupPlayerInternal(cached.player)
                true
            } else {
                false
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }

    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch {
            listeners.forEach(block)
        }
    }

    private fun createShuffleOrder() {
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()
        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) return

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        notifyListeners { onTimelineChanged(list, reason) }
    }

    fun setPrecachingEnabled(enabled: Boolean) {
        precacheEnabled = enabled
        if (!enabled) {
            clearPrecacheExceptCurrentInternal()
        } else {
            triggerPrecachingInternal()
        }
    }

    private suspend fun extractPlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        Logger.w(TAG, "Extracting playable URL for ${mediaItem.mediaId}")
        val videoId = mediaItem.mediaId

        val downloadFiles =
            File(getDownloadPath()).listFiles()?.filter {
                it.name.contains(videoId)
            }
        if (!downloadFiles.isNullOrEmpty()) {
            val audioFile = downloadFiles.firstOrNull { !it.name.contains(MERGING_DATA_TYPE.VIDEO) }
            if (audioFile != null && audioFile.length() > 0 && audioFile.exists()) {
                return PlayableSource(url = audioFile.absolutePath)
            }
        }

        streamRepository.getNewFormat(videoId).lastOrNull()?.let { stored ->
            val audioUrl = stored.audioUrl
            val wantedQuality = streamRepository.deliversQuality(stored.itag, streamRepository.wantedAudioItag(dataStoreManager))
            if (!audioUrl.isNullOrEmpty() && wantedQuality && streamRepository.is403Url(audioUrl).firstOrNull() == false) {
                Logger.w("Stream", "Audio from format")
                return PlayableSource(url = audioUrl)
            }
        }

        val audioUrl =
            streamRepository
                .getStream(
                    dataStoreManager,
                    videoId,
                    isDownloading = false,
                    isVideo = false,
                ).lastOrNull()
        if (audioUrl != null) {
            Logger.d(TAG, "Stream Audio $audioUrl")
            return PlayableSource(url = audioUrl)
        }

        return null
    }
}
