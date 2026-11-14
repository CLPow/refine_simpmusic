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

/** Generic source error, the value ExoPlayer uses for IO_UNSPECIFIED. */
private const val MPV_ERROR_CODE = 2000

/** A precached handle older than this may hold an expired stream URL (they last about six hours). */
private const val PRECACHE_MAX_AGE_MS = 3 * 60 * 60 * 1000L

/** Error recoveries (reload on a fresh URL) allowed for one track before the error is reported. */
private const val MAX_RECOVERIES_PER_TRACK = 4

/** Playback this far past where a track was started counts as "audio is really playing". */
private const val CONFIRM_PROGRESS_MS = 500L

/**
 * mpv (libmpv via JNA) implementation of [MediaPlayerInterface].
 *
 * Structural port of `VlcPlayerAdapter`: the queue, shuffle order, repeat modes, precaching,
 * URL resolution and 403 retry handling are carried over unchanged. Only the native-call layer
 * differs — see [MpvPlayer] for the vlcj → libmpv mapping.
 *
 * Differences from the VLC backend that are inherent to mpv rather than choices:
 *  - There is no factory object; each [MpvPlayer] owns its own `mpv_handle`, so there is nothing
 *    to release globally in [release].
 *  - Events are pulled on a per-handle pump thread instead of pushed from a native thread, so the
 *    "never call stop()/release() from inside a callback" hazard that VLC has does not exist here.
 */
class MpvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    // Internal state enum for proper state machine
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

    // ========== State Management ==========
    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: MpvPlayer? = null


    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    /**
     * A seek requested while no handle existed yet, tagged with the track it was meant for.
     *
     * [seekTo] used to drop such a request on the floor — `currentPlayer?.let { }` with a null
     * player is a no-op — while still having moved [cachedPosition] to the target. The bar showed
     * the middle of the track and playback began at zero. The tag means a request cannot leak onto
     * whatever track happens to load next.
     */
    private var pendingSeek: Pair<Int, Long>? = null

    private var internalVolume = 1.0f

    /** Sleep-timer fade gain, kept apart from [internalVolume] so the user's level survives it. */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    /**
     * Pitch shift the user asked for, 1.0 = unchanged.
     */
    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    // Position tracking
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    // Position update job (fallback polling of time and length)
    private var positionUpdateJob: Job? = null

    // Precaching system
    private class PrecachedPlayer(
        val player: MpvPlayer,
        val mediaItem: GenericMediaItem,
        val source: PlayableSource,
    ) {
        private val createdAtMs = System.currentTimeMillis()

        /** Set by the handle's own listener: its load failed, or it ran to the end, while it waited. */
        @Volatile
        var failed = false

        /**
         * A handle whose load failed sits idle, and unpausing it "played" the track at 0:00 forever.
         * One that has waited for hours holds an expired URL.
         */
        fun isUsable(): Boolean = !failed && System.currentTimeMillis() - createdAtMs < PRECACHE_MAX_AGE_MS
    }

    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // Retry system - mirrors Android ExoPlayerAdapter retry logic
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // retryCount starts again each time the track plays; this one only on another track.
    private var recoveryCount = 0
    private var recoveryVideoId: String? = null

    /** Position the current track was started at, and whether it has been seen to move past it. */
    @Volatile
    private var confirmFromMs = 0L

    @Volatile
    private var playbackConfirmed = false

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    // Loading management
    private var currentLoadJob: Job? = null

    fun getCurrentPlayer(): MpvPlayer? = currentPlayer

    // ========== Playback Source ==========
    private data class PlayableSource(
        val url: String,
    )

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (current state: $internalState)")
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        // At the end of the queue the handle is parked at EOF, and telling mpv to
                        // play there does nothing — the press would look ignored. Rewind first so
                        // the last track replays, which is what the Play button is offering.
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
                    // After a failed track the handle holds nothing playable, so Play used to do
                    // nothing at all. Load the track again where it stopped, on a fresh URL.
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
                // Playback has genuinely stopped by this point, so a sleep-timer attenuation has
                // served its purpose and is cleared here rather than by the caller, which cannot
                // tell when that happened: pause() only queues this coroutine. In `finally` so a
                // failure above cannot leave the mixer attenuated with nothing to restore it.
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
        // Updated here rather than inside the coroutine so the UI reflects the seek immediately.
        cachedPosition = positionMs
        // The mpv call hops to the player thread, same as the volume setters: reaching into a
        // handle from the caller's thread races the release that happens on the player thread, and
        // a handle destroyed between the isReleased check and the native call is a use-after-free.
        coroutineScope.launch {
            val player = currentPlayer
            if (player == null) {
                // Mid-load: the handle is released and rebuilt, so there is nothing to seek yet.
                // Hand the position to the load instead of losing it.
                pendingSeek = localCurrentMediaItemIndex to positionMs
                Logger.d(TAG, "Seek deferred to load: $positionMs")
            } else {
                try {
                    player.seekTo(positionMs)
                    Logger.d(TAG, "Seeked to position: $positionMs")
                    // Same contract as the Android adapter: every seek is reported, whoever asked
                    // for it (seek bar, lyric tap, ±5 s, Control Center, SponsorBlock).
                    notifyListeners { onSeeked(positionMs) }
                } catch (e: Exception) {
                    // Same reasoning as the null case: the bar has already moved, so the position
                    // has to be applied somewhere or the two stay disagreeing.
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

            // Cancel any ongoing load
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
            // Standard music player behavior:
            // Position > 3s → seek to start of current track
            // Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            if (cachedPosition > positionThresholdMs || !hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms — seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
                // Same track, so nothing reloads: a paused player has to be told to play.
                if (andPlay) play()
            } else {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms <= ${positionThresholdMs}ms — going to previous track")
                seekTo(getPreviousMediaItemIndex(), 0)
            }
        }
    }

    // A listener's skip plays the track it lands on (see MediaPlayerInterface.skipToNext). The
    // intent alone is enough for a track change: seekTo(index) loads with internalPlayWhenReady.
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
            // Always go to the previous track — skips the 3-second "seek to start"
            // rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

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
            // Bounds check MUST run inside the launch, on the same player thread/queue as removeAt.
            // If it sits outside (on the caller thread), another queued op (clearMediaItems/setMediaItem)
            // can empty the playlist between the check and removeAt → IndexOutOfBounds (issue #2156 /
            // SIMPMUSIC-DESKTOP-3Y: "Index 0 out of bounds for length 0").
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
            // Same reason as removeMediaItem (issue #2156): re-check bounds inside the launch, since the
            // playlist can be emptied by another queued op between an outside-launch guard and removeAt.
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

            // The handle prepared for the item that was here, not for the one replacing it.
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

    // ========== Playback State Properties ==========

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

    // ========== Navigation ==========

    /**
     * Repeat-one loops a song that ends by itself; it does not trap Next and Previous on it.
     * Navigation treats it as repeat off. It used to make Next, a thumbs-down and the
     * explicit-content skip replay the same song.
     */
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

    // ========== Playback Modes ==========

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
            // Hopped to the player thread for the same reason as seekTo and the volume setters.
            coroutineScope.launch {
                // Every live handle, not just the current one: these are playback-wide settings, so
                // the precached handles have to carry them too or the next track starts back at 1.0x.
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

    // ========== Audio Settings ==========

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            // Same hop as sleepFadeFactor below, and for the same reason: writing mpv properties
            // straight from the caller's thread races the player thread that releases handles, and
            // a handle destroyed between the isReleased check and the property write is a
            // use-after-free. The field itself is set synchronously so the getter reads back what
            // was just written; only the mpv calls are deferred.
            coroutineScope.launch {
                // mpv volume: 0-100 (100 = unattenuated). Map our 0.0-1.0 to 0-100.
                val percent = (internalVolume * 100).toInt()
                forEachLiveHandle { it.setMasterVolume(percent) }
            }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            // Hop onto the player thread instead of writing from the caller's — the sleep timer
            // ramps this from Main. Three separate things depend on that hop:
            //  - [MpvPlayer.applyVolume] reads three fields and issues two native calls, so a
            //    second writer can interleave and publish a stale combination.
            //  - A handle can be released between its `isReleased` check and mpv_set_property,
            //    which is a use-after-free that has already produced a SIGSEGV here once (see the
            //    join comment in MpvPlayer.release). Releases happen on this same thread, so
            //    sharing it removes the window entirely.
            //  - pause() queues onto this same single-threaded scope. Going through it is what
            //    guarantees the timer's closing restore to 1f lands *after* playback has actually
            //    stopped, instead of briefly re-opening the mixer over the last of the audio.
            coroutineScope.launch {
                // Re-read rather than capture: if the queue backs up, the pending writes collapse
                // onto the newest value instead of replaying a stale ramp.
                val percent = (internalSleepFadeFactor * 100).toInt()
                forEachLiveHandle { it.setSleepFadeVolume(percent) }
            }
        }

    /**
     * Every handle that can currently reach the audio device.
     *
     * `ao-volume` is not per-handle: on Windows they all land in the process' default WASAPI
     * session (mpv passes a NULL AudioSessionGuid), so one ISimpleAudioVolume covers the lot, and
     * a handle left holding an older level re-asserts it on its own AUDIO_RECONFIG and overwrites
     * everyone else's. Missing one is therefore not "that handle stays wrong", it is "that handle
     * silently undoes the others".
     */
    private fun forEachLiveHandle(action: (MpvPlayer) -> Unit) {
        currentPlayer?.let(action)
        precachedPlayers.values.forEach { action(it.player) }
    }

    /**
     * Push the playback-wide settings onto a handle that is about to become audible.
     *
     * A fresh mpv handle starts at 100/100 volume and 1.0x speed, so anything already in force has
     * to be re-asserted here — otherwise a fade in progress jumps back to full volume mid-ramp, and
     * a track started while the user has speed turned up plays at normal speed instead.
     *
     * The two volume levels go down in one call: setting them separately would publish a
     * full-volume master onto the shared `ao-volume` before the fade is applied, which every other
     * handle hears.
     */
    private fun MpvPlayer.applyPlaybackLevels() {
        setVolumeLevels(
            master = (internalVolume * 100).toInt(),
            sleep = (internalSleepFadeFactor * 100).toInt(),
        )
        setRate(internalPlaybackSpeed)
        applyPitch()
    }

    /**
     * Put the user's pitch shift on this handle, if pitch is available at all right now.
     */
    private fun MpvPlayer.applyPitch() {
        if (internalPlaybackPitch == 1.0f) {
            clearAudioFilters()
        } else {
            // rubberband also absorbs the tempo change once present, so setRate keeps working.
            // Only drive the shift if mpv actually took the filter — a build without rubberband
            // rejects it, and af-command against a filter that is not in the chain fails every time.
            if (installPitchShift()) {
                setPitchScale(internalPlaybackPitch)
            } else {
                Logger.w(TAG, "Pitch shift unavailable: mpv rejected the rubberband filter")
            }
        }
    }

    override var skipSilenceEnabled: Boolean = false

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()

        // No factory to release: libmpv has no factory concept, each handle is destroyed by its
        // own MpvPlayer.release() via mpv_terminate_destroy().
    }

    // ========== Internal Methods ==========

    /**
     * Transition internal state and notify listeners.
     * Listeners are called on the service thread (not Main) because
     * JvmMediaPlayerHandlerImpl uses thread-safe StateFlow updates
     * and contains runBlocking calls that would deadlock on Main.
     */
    private fun transitionToState(
        newState: InternalState,
        emitError: Boolean = true,
    ) {
        if (internalState == newState) return

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Query duration from mpv
        currentPlayer?.let { player ->
            val dur = player.length
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners on the service thread — safe because JvmMediaPlayerHandlerImpl
        // uses thread-safe StateFlow/MutableStateFlow for all state updates.
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
                // A generic report for the load failures that have no error of their own. A caller
                // that already reported the real error passes false: a second report counted as a
                // second failure, and skipped a good track.
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

    /**
     * Give the C allocator's free pages back to the OS now that playback has gone quiet.
     *
     * Only ever called from the PAUSED / IDLE transitions: the underlying call walks the heap while
     * holding the allocator lock, so anything that mallocs — mpv's decoder threads included — stalls
     * until it returns. See [MemoryTrimmer] for the measurements that motivate this.
     *
     * Dispatched off the single service thread because that thread also drives the transport; a
     * heap walk there would show up as a laggy pause button. [MemoryTrimmer] throttles itself, so
     * firing this on every pause is cheap.
     */
    private fun trimNativeMemory(reason: String) {
        coroutineScope.launch(Dispatchers.IO) {
            MemoryTrimmer.trim(reason)
        }
    }

    /**
     * Load and play track - MUST run on coroutineScope
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
        // False when the same track is loaded again to recover from an error: announcing it as a
        // new item counted every recovery as another listen.
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

                    // Notify media item transition (fire-and-forget to avoid
                    // blocking the service thread with runBlocking inside handler)
                    if (announce) {
                        notifyListeners {
                            onMediaItemTransition(
                                mediaItem,
                                PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }

                    // Detach event listener from old player IMMEDIATELY before
                    // spending time on URL extraction. This prevents stale events
                    // (error/finished) from the old player seeing the updated
                    // playlist/index and interfering with the new track load.
                    cleanupEventListenerInternal()
                    stopPositionUpdates()

                    // Extract URL on IO thread (network), mpv native calls stay on service thread
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

                    // mpv native calls on the service thread
                    cleanupCurrentPlayerInternal()
                    currentPlayer = player
                    setupPlayerEventsInternal(player)
                    player.applyPlaybackLevels()

                    if (cachedPrecache != null) {
                        // The precached handle already has the file loaded and held paused;
                        // unpausing is all that is needed (VLC needed an explicit play() here too).
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

                    // A seek the user asked for while this track was still loading wins over the
                    // position the load was started with — it is the newer intent. Read and cleared
                    // unconditionally so a request for a track that never loads cannot linger.
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

                    // Always transition to READY first so UI receives
                    // isLoading=false via STATE_READY. The READY handler
                    // will auto-call play() when internalPlayWhenReady is true.
                    transitionToState(InternalState.READY)

                    // Start position updates
                    startPositionUpdates()

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Load track error: ${e.message}", e)
                        transitionToState(InternalState.ERROR)
                    }
                }
            }
    }

    /** The URL handed to `loadfile`: the audio stream, or a downloaded file's path. */
    private fun buildPlaybackUrl(source: PlayableSource): String = source.url

    /**
     * Create an audio-only mpv player instance (`vid=no`/`vo=null`), so no video stream is ever
     * decoded or fetched.
     */
    private fun createMediaPlayerInternal(
        source: PlayableSource,
        mediaId: String,
    ): MpvPlayer? {
        // VLC used --network-caching=10000.
        val player = MpvPlayer.create(networkCacheSeconds = 10)
        // A fresh handle starts at masterPercent = 100, and every handle shares a
        // single `ao-volume` because they all register under the same
        // audio-client-name. The handle's first AUDIO_RECONFIG therefore pushes
        // that default 100 onto the shared mixer — audible as a jump to full
        // volume, and for a precache handle it lands while the previous track is
        // still playing. Seeding the user's level here means the value
        // AUDIO_RECONFIG re-applies is already the right one, whether or not the
        // audio output exists yet.
        player?.setMasterVolume((internalVolume * 100).toInt())
        return player
    }

    /**
     * Setup mpv event listeners for a player.
     *
     * Unlike VLC — whose callbacks arrive on a native thread where calling stop()/release()
     * deadlocks — mpv events are pulled by [MpvPlayer]'s own pump thread. Work is still dispatched
     * to [coroutineScope] so the state machine stays serialized on one thread.
     */
    private fun setupPlayerEventsInternal(player: MpvPlayer) {
        // Remove old listener
        cleanupEventListenerInternal()

        val listener =
            object : MpvPlayerEventAdapter() {
                override fun finished(player: MpvPlayer) {
                    Logger.d(TAG, "End of stream reached")
                    coroutineScope.launch {
                        // Ignore events from a player that is no longer current
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
                        // Ignore errors from a player that is no longer current.
                        // This can happen when the old player fires error() after
                        // a new track has started loading (playlist/index already updated).
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring error() from stale player")
                            return@launch
                        }
                        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                        if (currentVideoId != null) {
                            // Reset retry count for new track
                            if (retryVideoId != currentVideoId) {
                                retryVideoId = currentVideoId
                                retryCount = 0
                            }
                            if (retryCount < maxRetryCount && recoveryCount < MAX_RECOVERIES_PER_TRACK) {
                                retryCount++
                                recoveryCount++
                                Logger.w(TAG, "Retrying playback (attempt $retryCount/$maxRetryCount) for $currentVideoId")
                                // Where it failed, not 0:00: an expired URL after a long pause used
                                // to restart the song from the beginning.
                                val resumePositionMs = cachedPosition.coerceAtLeast(0L)
                                try {
                                    // Invalidate cached format so fresh URL is fetched
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    // Evict stale precache
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    // Reload the track
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
                            // Not a timeout: mpv does not say why it failed, so the shared policy
                            // decides from the connection (offline pauses and waits for it, online
                            // moves past the track). As a timeout it always paused for good.
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
                            // Reset retry counter on successful playback
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
                    // Update buffered position first
                    if (cachedDuration > 0) {
                        cachedBufferedPosition = (cachedDuration * newCache / 100f).toLong()
                    }

                    // Only report loading when buffer is actually behind the playhead
                    // AND the player intends to play. Ignore buffering while paused
                    // to avoid showing a loading spinner when user has explicitly paused.
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

    /**
     * Clean up event listener from current player
     */
    private fun cleanupEventListenerInternal() {
        currentPlayer?.setEventListener(null)
    }

    /**
     * Cleanup a player instance
     */
    private fun cleanupPlayerInternal(player: MpvPlayer) {
        try {
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    /**
     * Cleanup current player
     */
    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupEventListenerInternal()

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    /**
     * Handle track end
     */
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

    // ========== Position Updates ==========

    /**
     * Start position updates: a periodic poll of time and length, backing up mpv's observed
     * `time-pos`.
     */
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
                        // Ignore query errors
                    }

                    delay(200) // Update every 200ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Trigger precaching
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    // What plays next, in the order it will play: with shuffle on, index + 1 is
                    // almost never the next track, so those handles were built and thrown away.
                    val upcoming = upcomingIndices(maxPrecacheCount)
                    val indicesToPrecache = upcoming.filter { !precachedPlayers.containsKey(playlist.getOrNull(it)?.mediaId) }

                    // Handles precached for tracks that are no longer next, that failed, or whose
                    // URL has aged out are released. Left alone they stayed alive, paused, for the
                    // whole session, and every volume change was pushed to each of them.
                    val wanted = upcoming.mapNotNull { playlist.getOrNull(it)?.mediaId }.toSet()
                    precachedPlayers.entries.removeIf { (videoId, cached) ->
                        val stale = (videoId !in wanted && videoId != currentMediaItem?.mediaId) || !cached.isUsable()
                        if (stale) cleanupPlayerInternal(cached.player)
                        stale
                    }

                    for (idx in indicesToPrecache) {
                        if (!isActive) break

                        val mediaItem = playlist.getOrNull(idx) ?: continue

                        // Network extraction on IO, mpv native calls on the service thread
                        val source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }

                        if (source != null && source.url.isNotEmpty()) {
                            try {
                                // mpv has no separate "parse without playing" call like vlcj's
                                // media().prepare(); loading held at pause=yes is the equivalent,
                                // and additionally warms the demuxer cache.
                                val player = createMediaPlayerInternal(source, mediaItem.mediaId)
                                if (player == null) {
                                    Logger.w(TAG, "Precaching skipped for $idx: could not create mpv player")
                                } else {
                                    val precached = PrecachedPlayer(player, mediaItem, source)
                                    // Nothing else listens to a waiting handle, so a failed load
                                    // would go unseen until the track was "played".
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

    /** The next [count] playlist indices in play order (shuffle and repeat-all included). */
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

    /**
     * Dispatch listener notifications on the service thread.
     * Listeners (JvmMediaPlayerHandlerImpl) use thread-safe StateFlow updates
     * and contain runBlocking calls that would deadlock on Main/Swing EDT.
     */
    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch {
            listeners.forEach(block)
        }
    }

    // ========== Shuffle Management ==========

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

    // ========== URL Extraction ==========

    /** The audio URL for [mediaItem]: a downloaded file first, then the format API, then stream extraction. */
    private suspend fun extractPlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        Logger.w(TAG, "Extracting playable URL for ${mediaItem.mediaId}")
        val videoId = mediaItem.mediaId

        // Check downloads first
        val downloadFiles =
            File(getDownloadPath()).listFiles()?.filter {
                it.name.contains(videoId)
            }
        if (!downloadFiles.isNullOrEmpty()) {
            val audioFile = downloadFiles.firstOrNull { !it.name.contains(MERGING_DATA_TYPE.VIDEO) }
            if (audioFile != null && audioFile.length() > 0 && audioFile.exists()) {
                // mpv accepts absolute file paths directly
                return PlayableSource(url = audioFile.absolutePath)
            }
        }

        // Try the new format API
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { stored ->
            val audioUrl = stored.audioUrl
            // A stored URL of another quality (the setting was changed since) is not reused: it
            // used to play at the old quality for as long as the URL lived, about six hours.
            val wantedQuality = streamRepository.deliversQuality(stored.itag, streamRepository.wantedAudioItag(dataStoreManager))
            if (!audioUrl.isNullOrEmpty() && wantedQuality && streamRepository.is403Url(audioUrl).firstOrNull() == false) {
                Logger.w("Stream", "Audio from format")
                return PlayableSource(url = audioUrl)
            }
        }

        // Fallback to stream extraction
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
