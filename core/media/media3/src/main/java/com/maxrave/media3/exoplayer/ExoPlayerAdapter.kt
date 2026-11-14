package com.maxrave.media3.exoplayer

import com.maxrave.media3.di.WifiOnlyException
import com.maxrave.domain.mediaservice.handler.WIFI_ONLY_ERROR_CODE_NAME
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.audio.SleepFadeAudioProcessor
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ExoPlayerAdapter"

/** A precached player older than this may hold an expired stream URL (they last about six hours). */
private const val PRECACHE_MAX_AGE_MS = 3 * 60 * 60 * 1000L

/** Audio buffered before playback resumes after a stall. */
private const val REBUFFER_RESUME_MS = 2_000

/** Error recoveries (reload on a fresh URL) allowed for one track before the error is reported. */
private const val MAX_RECOVERIES_PER_TRACK = 4

/**
 * ExoPlayer implementation of [MediaPlayerInterface].
 *
 * - Internal playlist management (not ExoPlayer's playlist)
 * - Multi-player instance model: each track gets its own ExoPlayer
 * - Precaching system for smooth transitions
 * - [DelegatingForwardingPlayer] for MediaSession integration
 * - [MergingMediaSourceFactory] + ResolvingDataSource for URL resolution: each ExoPlayer gets a
 *   single [MediaItem] and auto-resolves the stream URL
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
internal class ExoPlayerAdapter(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val mediaSourceFactory: MergingMediaSourceFactory,
    private val audioAttributes: AudioAttributes,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    // ========== Internal State Enum (same as GstreamerPlayerAdapter) ==========

    private enum class InternalState {
        IDLE, // No media loaded
        PREPARING, // Loading media
        READY, // Ready to play/paused
        PLAYING, // Currently playing
        PAUSED,
        ENDED, // Playback ended
        ERROR, // Error state
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    // ========== State Management ==========

    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: ExoPlayer? = null

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    /**
     * Sleep-timer fade attenuation. Applied on a separate volume line — every player's
     * [SleepFadeAudioProcessor] reads this value straight out of here, so writing it once covers
     * every player and nothing on the `volume` line has to be touched.
     */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalSkipSilence = false

    // Position tracking - updated periodically, not on every query
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    private var positionUpdateJob: Job? = null

    // Active Player.Listener (equivalent to BusListeners in GstreamerPlayerAdapter)
    // Only ONE listener instance, attached to ONE ExoPlayer at a time.
    private var activePlayerListener: Player.Listener? = null

    // ========== Audio Focus (manual, session-scoped) — #2155 ==========
    // The multi-player swap model means audio focus must NOT be tied to any single
    // ExoPlayer: releasing the outgoing player would abandon focus, and the incoming
    // player (built with handleAudioFocus=false) never re-requests it — the root cause
    // of "music stops between tracks" (see androidx/media#2100). Instead we hold one
    // app-level AudioFocusRequest at the adapter level so focus survives every swap.

    private val duckVolumeFactor = 0.2f

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @Volatile
    private var hasAudioFocus = false

    /** True when focus was lost transiently so playback should auto-resume on regain. */
    @Volatile
    private var resumeOnFocusGain = false

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    currentPlayer?.volume = internalVolume
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss (another app took over): pause and stop tracking focus.
                    resumeOnFocusGain = false
                    hasAudioFocus = false
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Temporary loss (e.g. an incoming call): pause and remember to resume.
                    resumeOnFocusGain = internalState == InternalState.PLAYING
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Lower the volume instead of pausing (e.g. a navigation prompt).
                    currentPlayer?.volume = internalVolume * duckVolumeFactor
                }
            }
        }

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes
                    .Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
    }

    /** Request app-level audio focus once; idempotent while focus is held. */
    private fun requestAudioFocusInternal(): Boolean {
        val am = audioManager ?: return true
        if (hasAudioFocus) return true
        val granted = am.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        Logger.d(TAG, "requestAudioFocus -> granted=$granted")
        return granted
    }

    private fun abandonAudioFocusInternal() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        am.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        resumeOnFocusGain = false
        Logger.d(TAG, "abandonAudioFocus")
    }

    // ========== Precaching System ==========

    private data class PrecachedPlayer(
        val player: ExoPlayer,
        val mediaItem: GenericMediaItem,
        /** `elapsedRealtime` when it was prepared: its stream URL expires, so an old one is not reused. */
        val createdAtMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        /**
         * Nothing listens to a precached player, so one whose load failed (a dead spot, a Wi-Fi-only
         * refusal, an extraction error) sits in STATE_IDLE with an error nobody saw. Playing it
         * showed the track at 0:00 forever, with no error and no skip.
         */
        fun isUsable(): Boolean =
            player.playerError == null &&
                player.playbackState != Player.STATE_IDLE &&
                android.os.SystemClock.elapsedRealtime() - createdAtMs < PRECACHE_MAX_AGE_MS
    }

    // VideoId -> PrecachedPlayer
    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // ========== Retry on Source Error ==========
    // Track retry attempts per media item to avoid infinite retry loops
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // retryCount starts again each time the track plays, so a URL that keeps failing after its
    // first bytes recovered without end. This one only starts again on another track.
    private var recoveryCount = 0
    private var recoveryVideoId: String? = null

    // ========== Playlist Management ==========

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    // Loading management
    private var currentLoadJob: Job? = null

    // ========== ForwardingPlayer for MediaSession ==========

    // Create an initial idle ExoPlayer for MediaSession to hold
    private val initialPlayer = createExoPlayerInstance()

    /**
     * Stable [Player] reference for MediaSession.
     * Delegates all calls to the currently active [ExoPlayer] instance.
     * Updated via [DelegatingForwardingPlayer.swapDelegate] when the active player changes.
     */
    val forwardingPlayer: DelegatingForwardingPlayer = DelegatingForwardingPlayer(initialPlayer)

    init {
        currentPlayer = initialPlayer

        // Wire up playlist navigation so ForwardingPlayer (and thus MediaSession)
        // can see the full playlist state instead of the single-item ExoPlayer state.
        // Only navigation commands are overridden — NOT getMediaItemCount/getCurrentMediaItemIndex
        // which must stay consistent with ExoPlayer's internal Timeline to avoid crashes.
        forwardingPlayer.playlistNavigationProvider =
            object : DelegatingForwardingPlayer.PlaylistNavigationProvider {
                override fun hasNextMediaItem(): Boolean = this@ExoPlayerAdapter.hasNextMediaItem()

                override fun hasPreviousMediaItem(): Boolean = this@ExoPlayerAdapter.hasPreviousMediaItem()

                // Every call here comes from a controller (notification, headset, Android Auto),
                // i.e. the listener pressed a button: a skip lands on the track playing.
                override fun seekToNext(): Unit = this@ExoPlayerAdapter.skipToNext()

                override fun seekToPrevious(): Unit = this@ExoPlayerAdapter.skipToPrevious()

                override fun seekToPreviousMediaItem(): Unit = this@ExoPlayerAdapter.skipToPreviousMediaItem()

                override fun play(): Unit = this@ExoPlayerAdapter.play()

                override fun pause(): Unit = this@ExoPlayerAdapter.pause()

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    this@ExoPlayerAdapter.playWhenReady = playWhenReady
                }
            }
    }

    // ========== Cast Remote Routing ==========

    /**
     * While a Cast session is active this holds the session-level [Player] (the unified
     * CastPlayer wrapping [forwardingPlayer]): transport calls and position/state getters
     * are routed to it, and playback-start requests are handed to [castPlaybackRouter]
     * instead of the local ExoPlayer machinery. The playlist itself stays local — the
     * receiver only ever sees a small resolved-URL window of it.
     */
    @Volatile
    private var castRemotePlayer: Player? = null

    internal val isCastActive: Boolean
        get() = castRemotePlayer != null

    /** Set by CastHandoffManager: (playlistIndex, startPositionMs, playWhenReady) -> load on receiver. */
    internal var castPlaybackRouter: ((Int, Long, Boolean) -> Unit)? = null

    internal fun setCastActive(
        remotePlayer: Player?,
        deviceName: String?,
    ) {
        if (remotePlayer != null) {
            if (castRemotePlayer === remotePlayer) return
            castRemotePlayer = remotePlayer
            Logger.w(TAG, "Cast session active on ${deviceName ?: "unknown device"} — local playback suspended")
            coroutineScope.launch {
                // Kill anything that makes local noise or wastes battery while remote.
                cancelPrecaching()
                clearAllPrecacheInternal()
                currentPlayer?.pause()
                stopPositionUpdates()
                abandonAudioFocusInternal()
            }
            listeners.forEach { it.onCastStateChanged(GenericCastState(isRemote = true, deviceName = deviceName)) }
        } else {
            if (castRemotePlayer == null) return
            castRemotePlayer = null
            Logger.w(TAG, "Cast session ended — back to local playback")
            listeners.forEach { it.onCastStateChanged(GenericCastState.NOT_CASTING) }
        }
    }

    /** Remote queue advanced — keep the local playlist pointer and UI in sync. */
    internal fun notifyRemoteTransition(playlistIndex: Int) {
        if (!isCastActive || playlistIndex !in playlist.indices) return
        if (playlistIndex == localCurrentMediaItemIndex) return
        localCurrentMediaItemIndex = playlistIndex
        val item = playlist[playlistIndex]
        listeners.forEach {
            it.onMediaItemTransition(item, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
    }

    internal fun notifyRemoteIsPlaying(isPlaying: Boolean) {
        if (!isCastActive) return
        internalPlayWhenReady = isPlaying
        listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    internal fun notifyRemotePlaybackState(playbackState: Int) {
        if (!isCastActive) return
        listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    // ========== ExoPlayer Instance Factory ==========

    /**
     * Create a new ExoPlayer instance with its own sleep-fade processor.
     *
     * Audio focus is NOT handled per-player: it is managed once at the adapter level
     * (see the Audio Focus section) so it survives every player swap (#2155).
     */
    private fun createExoPlayerInstance(): ExoPlayer {
        val sleepFade = SleepFadeAudioProcessor { internalSleepFadeFactor }

        val perPlayerRenderers =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf(sleepFade),
                                SilenceSkippingAudioProcessor(
                                    2_000_000,
                                    // Retain 1 % of each silence (20 ms of a 2 s gap). This was
                                    // written `(20_000 / 2_000_000).toFloat()`, integer division
                                    // that always passed 0.
                                    0.01f,
                                    2_000_000,
                                    0,
                                    256,
                                ),
                                SonicAudioProcessor(),
                            ),
                        ).build()
            }

        val player =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(audioAttributes, false)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                            // A track starts on its first bytes...
                            0,
                            // ...but after a stall it waits for two seconds of audio: resuming on
                            // the first bytes stuttered in and out on a weak connection.
                            REBUFFER_RESUME_MS,
                        ).build(),
                ).setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(5000)
                .setSeekBackIncrementMs(5000)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(perPlayerRenderers)
                .build()

        return player
    }

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = true
            remote.play()
            return
        }
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        requestAudioFocusInternal()
                        // At the end of the queue `play()` only sets playWhenReady, which does
                        // nothing while the player sits in STATE_ENDED — the press would look
                        // ignored. Rewind first so the last track replays.
                        if (internalState == InternalState.ENDED) {
                            Logger.d(TAG, "Play: replaying from the start after end of queue")
                            player.seekTo(0L)
                        }
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = true
                    Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    cachedIsLoading = false
                }

                InternalState.ERROR -> {
                    // After a failed track the player holds nothing playable, so Play used to do
                    // nothing at all. Load the track again where it stopped, on a fresh URL.
                    val index = localCurrentMediaItemIndex
                    val videoId = playlist.getOrNull(index)?.mediaId
                    if (videoId != null) {
                        Logger.d(TAG, "Play: reloading $videoId after an error")
                        internalPlayWhenReady = true
                        streamRepository.invalidateFormat(videoId)
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
        Logger.d(TAG, "pause() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = false
            remote.pause()
            // The coroutine below never runs on this path, so the sleep attenuation has to be
            // cleared here too. Left set, it would outlive the cast session: the processor stays in
            // the local chain and would keep multiplying every sample by ~0, leaving the app silent
            // with a full volume slider until the process restarts.
            internalSleepFadeFactor = 1f
            return
        }
        coroutineScope.launch {
            try {
                forwardingPlayer.suppressPlaybackEnded = false
                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                // Playback has stopped, so a sleep-timer attenuation has served its purpose.
                // Cleared here rather than by the caller, which cannot tell when this actually
                // happened — pause() only queues this coroutine.
                //
                // In `finally` because the block above can throw or be cancelled. Anything that skips this leaves the
                // factor near zero, and since the processor multiplies every sample by it, that is
                // permanent silence with a full volume slider — no code path recovers from it.
                internalSleepFadeFactor = 1f
            }
        }
    }

    override fun stop() {
        castRemotePlayer?.let { remote ->
            remote.stop()
            return
        }
        coroutineScope.launch {
            forwardingPlayer.suppressPlaybackEnded = false
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
                abandonAudioFocusInternal()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        castRemotePlayer?.let { remote ->
            remote.seekTo(positionMs)
            cachedPosition = positionMs
            return
        }
        // Reflected immediately so the progress bar does not snap back while the seek is queued.
        cachedPosition = positionMs
        coroutineScope.launch {
            currentPlayer?.let { player ->
                try {
                    player.seekTo(positionMs)
                } catch (e: Exception) {
                    Logger.e(TAG, "Seek exception: ${e.message}", e)
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

            // Load the new track
            localCurrentMediaItemIndex = mediaItemIndex
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val end = duration.takeIf { it > 0 } ?: cachedDuration
        val newPosition = (currentPosition + 5000).coerceAtMost(end)
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
            // - Position > 3s  → seek to start of current track
            // - Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            val position = currentPosition
            if (position > positionThresholdMs || !hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms — seeking to start")
                seekTo(0)
                // Same track, so nothing reloads: a paused player has to be told to play.
                if (andPlay) play()
            } else {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms <= ${positionThresholdMs}ms — going to previous track")
                seekTo(getPreviousMediaItemIndex(), 0)
            }
        }
    }

    // A listener's skip plays the track it lands on. Setting the intent (without calling play())
    // is enough for a track change: seekTo(index) loads with shouldPlay = internalPlayWhenReady.
    // Calling play() instead would resume the old track for a moment before the new one loads.
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
            // Always advance to the previous track regardless of `cachedPosition` —
            // skips the 3-second "seek to start" rule used by seekToPrevious().
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
            // Cancel ongoing operations
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

            // Adjust current index if needed
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }

            // Update shuffle order if enabled
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
        if (index !in playlist.indices) return

        coroutineScope.launch {
            val track = playlist.removeAt(index)

            // Remove from precache
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
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return

        coroutineScope.launch {
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            // Update current index
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

            // The player prepared for the item that was here, not for the one replacing it.
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
        get() = castRemotePlayer?.isPlaying ?: (internalState == InternalState.PLAYING)

    override val currentPosition: Long
        get() {
            castRemotePlayer?.let { return it.currentPosition }
            // Prefer the live ExoPlayer clock. cachedPosition is only refreshed by the 200 ms
            // poll and is wrong whenever that job is idle (pause, buffer, just after seek) —
            // which made Android Auto lyrics lag a second or more behind the music.
            val live = currentPlayer?.currentPosition
            return if (live != null && live >= 0) live else cachedPosition
        }

    override val duration: Long
        get() {
            castRemotePlayer?.let { remote ->
                return remote.duration.takeIf { it > 0 } ?: 0L
            }
            return currentPlayer?.duration ?: cachedDuration
        }

    override val bufferedPosition: Long
        get() = castRemotePlayer?.bufferedPosition ?: cachedBufferedPosition

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
        get() = castRemotePlayer?.contentPosition ?: cachedPosition

    override val playbackState: Int
        get() {
            // Media3 Player.STATE_* values match PlayerConstants 1:1.
            castRemotePlayer?.let { return it.playbackState }
            return when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }
        }

    // ========== Navigation ==========

    /**
     * Repeat-one loops a song that ends by itself; it does not trap Next and Previous on it.
     * Navigation treats it as repeat off, as Media3's own players do. It used to make Next, a
     * thumbs-down and the explicit-content skip replay the same song.
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
            listeners.forEach { it.onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            Logger.d(TAG, "Shuffle mode ${if (value) "enabled" else "disabled"}")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            listeners.forEach { it.onRepeatModeChanged(value) }
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
            val params = PlaybackParameters(value.speed, value.pitch)
            currentPlayer?.playbackParameters = params
            // Receiver support varies (speed only, no pitch) — best-effort while casting.
            castRemotePlayer?.let { remote ->
                runCatching { remote.playbackParameters = params }
            }
        }

    // ========== Audio Settings ==========

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            castRemotePlayer?.volume = internalVolume
            currentPlayer?.volume = internalVolume
            listeners.forEach { it.onVolumeChanged(internalVolume) }
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            // Nothing to push: each player's SleepFadeAudioProcessor samples this on every buffer,
            // so the user's own level on the `volume` line is never touched.
            //
            // Deliberately not forwarded to castRemotePlayer, unlike `volume` above. While casting
            // the local pipeline produces no audio, so the processor never sees data and the fade
            // would have to be rebuilt as a volume ramp on the receiver. Decided against: the sleep
            // timer simply stops the cast device without fading.
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
        }

    override var skipSilenceEnabled: Boolean
        get() = internalSkipSilence
        set(value) {
            internalSkipSilence = value
            currentPlayer?.skipSilenceEnabled = value
        }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        // Cancel all ongoing jobs
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        abandonAudioFocusInternal()
        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()
    }

    // ========== Internal: State Transition ==========

    /**
     * State transition helper - mirrors GstreamerPlayerAdapter.transitionToState()
     */
    private fun propagatePlayerError(error: PlaybackException) {
        val genericError =
            PlayerError(
                errorCode =
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerConstants.ERROR_CODE_TIMEOUT
                        else -> error.errorCode
                    },
                // The cause is lost on the way to the handler (the message is only "Source error"),
                // so a stream refused under "Stream via Wi-Fi only" is named here.
                errorCodeName = if (error.isWifiOnlyRefusal()) WIFI_ONLY_ERROR_CODE_NAME else error.errorCodeName,
                message = error.message,
            )
        Logger.e(TAG, "Playback error: ${error.message}")
        listeners.forEach { it.onPlayerError(genericError) }
        transitionToState(InternalState.ERROR, emitError = false)
    }

    private fun Throwable.isWifiOnlyRefusal(): Boolean = generateSequence(this) { it.cause }.any { it is WifiOnlyException }

    private fun transitionToState(
        newState: InternalState,
        emitError: Boolean = true,
    ) {
        if (internalState == newState) {
            Logger.d(TAG, "State transition ignored: already in $newState")
            return
        }

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Update cached duration from player
        currentPlayer?.let {
            val dur = it.duration
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
            }

            InternalState.READY -> {
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
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

    // ========== Internal: Load and Play Track ==========

    /**
     * Load and play track - mirrors GstreamerPlayerAdapter.loadAndPlayTrackInternal()
     *
     * Key difference: instead of extracting URL + creating PlayBin,
     * we create an ExoPlayer, set a MediaItem, and let MediaSourceFactory resolve the URL.
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

        // While casting, playback starts on the receiver — never on a local ExoPlayer.
        castPlaybackRouter?.takeIf { isCastActive }?.let { router ->
            currentLoadJob?.cancel()
            listeners.forEach {
                it.onMediaItemTransition(mediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
            router(index, startPositionMs, shouldPlay)
            return
        }

        // Cancel previous load
        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition
                    if (announce) {
                        listeners.forEach {
                            it.onMediaItemTransition(
                                mediaItem,
                                PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }

                    // Use the precached player if it is still good
                    val cachedPlayerEntry = precachedPlayers.remove(videoId)
                    val player: ExoPlayer
                    if (cachedPlayerEntry != null && cachedPlayerEntry.isUsable()) {
                        Logger.d(TAG, "Using precached player for $videoId")
                        player = cachedPlayerEntry.player
                    } else {
                        if (cachedPlayerEntry != null) {
                            Logger.w(TAG, "Precached player for $videoId failed or is too old; loading it again")
                            cleanupPlayerInternal(cachedPlayerEntry.player)
                        }
                        Logger.d(TAG, "Creating new player for $videoId")
                        player = createExoPlayerInstance()
                        player.setMediaItem(mediaItem.toMedia3MediaItem())
                        player.prepare()
                    }

                    // === CAREFUL ORDER for ForwardingPlayer integration ===

                    // 1. Remove our active listener from old player
                    cleanupPlayerListenerInternal()
                    stopPositionUpdates()

                    // 2. Save old player reference
                    val oldPlayer = currentPlayer

                    // 3. Set new player as current
                    currentPlayer = player

                    // 4. Setup our listener on new player
                    setupPlayerListenerInternal(player)

                    // 5. Swap ForwardingPlayer delegate (moves MediaSession's listeners from old to new)
                    forwardingPlayer.swapDelegate(player)

                    // 5b. Notify MediaSession about the new media item
                    // The MediaItem was set before the swap (either during precache or above),
                    // so MediaSession's listener missed the onMediaItemTransition event.
                    // play() below will trigger onIsPlayingChanged which causes MediaSession
                    // to re-query metadata, but this explicit notify is safer and ensures
                    // the notification updates immediately even if play() is delayed.
                    forwardingPlayer.notifyMediaItemChanged()

                    // 6. NOW release old player (it has no listeners anymore)
                    if (oldPlayer != null && oldPlayer !== player) {
                        try {
                            oldPlayer.stop()
                            oldPlayer.release()
                        } catch (e: Exception) {
                            Logger.w(TAG, "Error releasing old player: ${e.message}")
                        }
                    }

                    // Audio focus is held at the adapter level (see Audio Focus section),
                    // not per-player, so it survives this swap (#2155).

                    // Apply settings
                    player.volume = internalVolume
                    player.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                    player.skipSilenceEnabled = internalSkipSilence

                    // Seek if needed
                    if (startPositionMs > 0) {
                        player.seekTo(startPositionMs)
                        cachedPosition = startPositionMs
                    }

                    // Auto-play if requested
                    if (shouldPlay) {
                        requestAudioFocusInternal()
                        player.play()
                        transitionToState(InternalState.PLAYING)
                    } else {
                        player.pause()
                        transitionToState(InternalState.READY)
                    }

                    forwardingPlayer.suppressPlaybackEnded = false

                    // Start position updates
                    startPositionUpdates()

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Load track error: ${e.message}", e)
                    forwardingPlayer.suppressPlaybackEnded = false
                    transitionToState(InternalState.ERROR)
                }
            }
    }

    // ========== Internal: Player Listener Management ==========
    // Equivalent to setupPlayerListenersInternal / cleanupBusListenersInternal

    /**
     * Setup Player.Listener on the given player.
     * First removes any existing listener from the old player (like cleanupBusListenersInternal),
     * then creates and attaches a new listener to the given player, so only the active player's
     * events (STATE_ENDED in particular) are ever handled.
     */
    private fun setupPlayerListenerInternal(player: ExoPlayer) {
        // Clean up old listener first
        cleanupPlayerListenerInternal()

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Logger.d(TAG, "End of stream reached")
                            if (hasNextMediaItem() || internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE) {
                                forwardingPlayer.suppressPlaybackEnded = true
                                transitionToState(InternalState.PREPARING)
                            } else {
                                transitionToState(InternalState.ENDED)
                            }
                            handleTrackEndInternal()
                        }

                        Player.STATE_READY -> {
                            // Always clear loading state when ExoPlayer is ready
                            // (handles both initial load AND mid-playback rebuffer)
                            if (cachedIsLoading && player == currentPlayer) {
                                cachedIsLoading = false
                                listeners.forEach { it.onIsLoadingChanged(false) }
                            }
                            // Duration should be available now
                            val dur = player.duration
                            if (dur > 0) cachedDuration = dur
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                            if (player == currentPlayer) listeners.forEach { it.onPlaybackConfirmed() }
                        }

                        Player.STATE_BUFFERING -> {
                            // Playback is stalled waiting for data — report buffering
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    if (isPlaying) {
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                        }
                    } else {
                        if (internalState == InternalState.PLAYING) {
                            if (!player.playWhenReady) {
                                transitionToState(InternalState.PAUSED)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlayerError from non-current player")
                        return
                    }

                    // A stream refused under "Stream via Wi-Fi only" is final: retrying would
                    // invalidate the stored format and reload into the same refusal.
                    if (error.isWifiOnlyRefusal()) {
                        propagatePlayerError(error)
                        return
                    }

                    // Retry for source errors (expired/invalid stream URL)
                    // ERROR_CODE_PARSING_CONTAINER_MALFORMED (3001) = server returned non-media response (e.g. HTML error page)
                    // ERROR_CODE_IO_BAD_HTTP_STATUS (2004) = HTTP 403/410 from expired URL
                    // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001) = connection refused
                    // ERROR_CODE_IO_FILE_NOT_FOUND (2005) = the resolver served a cache hit as a bare
                    //   media id and the cached spans were evicted (or cleared) mid-read, so
                    //   DefaultDataSource fell through to FileDataSource on a scheme-less URI. Media3
                    //   lists FileNotFoundException as non-retriable, so this layer is the only chance
                    //   to recover; reloading re-runs the resolver, which now sees an incomplete cache
                    //   and resolves a real URL.
                    val isRetryableSourceError =
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                        // Without this the crash report is a bare FileDataSourceException with
                        // nothing tying it back to a cache decision made three layers up.
                        Logger.w(
                            TAG,
                            "Cache disappeared mid-read for $currentVideoId — the resolver had served it " +
                                "as a fully cached bare media id. Retrying to resolve a real URL.",
                        )
                    }
                    if (isRetryableSourceError && currentVideoId != null) {
                        // Reset retry count if this is a different track
                        if (retryVideoId != currentVideoId) {
                            retryVideoId = currentVideoId
                            retryCount = 0
                        }
                        if (retryCount < maxRetryCount && recoveryCount < MAX_RECOVERIES_PER_TRACK) {
                            retryCount++
                            recoveryCount++
                            Logger.w(TAG, "Retryable source error (attempt $retryCount/$maxRetryCount) for $currentVideoId: ${error.errorCodeName}")
                            // Snapshot the current position so the retry resumes where playback
                            // failed instead of restarting the track from the beginning.
                            val resumePositionMs = cachedPosition.coerceAtLeast(0L)
                            coroutineScope.launch {
                                try {
                                    // Invalidate cached format so ResolvingDataSource fetches a fresh URL
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    // Evict from precache (it may hold a stale player)
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    // Reload the track at the saved position
                                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, resumePositionMs, shouldPlay = true, announce = false)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                    propagatePlayerError(error)
                                }
                            }
                            return
                        }
                        Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                    }

                    propagatePlayerError(error)
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    // ExoPlayer reports isLoading=true during normal background buffer refill,
                    // not just when playback is stalled. Only propagate loading=true when
                    // playback is actually stalled (STATE_BUFFERING) AND the player intends
                    // to play. Ignore buffering events while paused to avoid showing
                    // a loading spinner when the user has explicitly paused.
                    val isPlaybackStalled = isLoading && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
                    val isCurrentPlayer = player == currentPlayer
                    Logger.d(TAG, "onIsLoadingChanged: isLoading=$isLoading, isPlaybackStalled=$isPlaybackStalled, isCurrentPlayer=$isCurrentPlayer")
                    if (cachedIsLoading != isPlaybackStalled && isCurrentPlayer) {
                        cachedIsLoading = isPlaybackStalled
                        listeners.forEach { it.onIsLoadingChanged(isPlaybackStalled) }
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val genericTracks = tracks.toGenericTracks()
                    listeners.forEach { it.onTracksChanged(genericTracks) }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (player != currentPlayer) return
                    // Fires for both in-app seeks and MediaSession/notification seeks — both land on the
                    // raw ExoPlayer. seekTo() does no manual notification, so this is the single source.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        listeners.forEach { it.onSeeked(newPosition.positionMs) }
                    }
                }
            }

        player.addListener(listener)
        activePlayerListener = listener
    }

    /** Remove the active player listener from the current player. */
    private fun cleanupPlayerListenerInternal() {
        activePlayerListener?.let { listener ->
            currentPlayer?.removeListener(listener)
        }
        activePlayerListener = null
    }

    // ========== Internal: Player Cleanup ==========

    private fun cleanupPlayerInternal(player: ExoPlayer) {
        try {
            player.stop()
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupPlayerListenerInternal()

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    // ========== Internal: Track End ==========

    /**
     * Handle track end - mirrors GstreamerPlayerAdapter.handleTrackEndInternal()
     */
    private fun handleTrackEndInternal() {
        // While casting, track transitions are driven by the receiver queue.
        if (isCastActive) return
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

    // ========== Internal: Position Updates ==========

    /** Start position updates (periodic background task). */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        currentPlayer?.let { player ->
                            if (internalState == InternalState.PLAYING ||
                                internalState == InternalState.READY ||
                                internalState == InternalState.PAUSED
                            ) {
                                val pos = player.currentPosition
                                val dur = player.duration
                                val buf = player.bufferedPosition

                                if (pos >= 0) cachedPosition = pos
                                if (dur > 0) cachedDuration = dur
                                if (buf >= 0) cachedBufferedPosition = buf
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore query errors - don't log to avoid spam
                    }

                    delay(200) // Update every 200ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // ========== Internal: Precaching ==========

    /**
     * Trigger precaching - mirrors GstreamerPlayerAdapter.triggerPrecachingInternal()
     *
     * Creates ExoPlayer instances for upcoming tracks. Each player gets a MediaItem
     * and calls prepare(), which triggers URL resolution and buffering via MediaSourceFactory.
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty() || isCastActive) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    // What plays next, in the order it will play: with shuffle on, index + 1 is
                    // almost never the next track, so those players were built and thrown away.
                    val upcoming = upcomingIndices(maxPrecacheCount)
                    val indicesToPrecache = upcoming.filter { !precachedPlayers.containsKey(playlist.getOrNull(it)?.mediaId) }

                    // Players precached for tracks that are no longer next (a skip, a queue edit,
                    // shuffle), that failed, or whose URL has aged out are released here. Each holds
                    // a decoder and buffers; left alone they piled up for the whole session.
                    val wanted = upcoming.mapNotNull { playlist.getOrNull(it)?.mediaId }.toSet()
                    precachedPlayers.entries.removeIf { (videoId, cached) ->
                        val stale = (videoId !in wanted && videoId != currentMediaItem?.mediaId) || !cached.isUsable()
                        if (stale) cleanupPlayerInternal(cached.player)
                        stale
                    }

                    for (idx in indicesToPrecache) {
                        if (!isActive) break

                        val mediaItem = playlist.getOrNull(idx) ?: continue

                        try {
                            val precached = createExoPlayerInstance()
                            precached.setMediaItem(mediaItem.toMedia3MediaItem())
                            precached.prepare()
                            precachedPlayers.put(mediaItem.mediaId, PrecachedPlayer(precached, mediaItem))?.let {
                                cleanupPlayerInternal(it.player)
                            }
                            Logger.d(TAG, "Precached player for index $idx")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Precaching error for $idx: ${e.message}")
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

    // ========== Internal: Shuffle Management ==========
    // Mirrors GstreamerPlayerAdapter shuffle management exactly

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
        Logger.d(TAG, "Cleared shuffle order")
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) {
            return
        }

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

        Logger.d(
            TAG,
            "Inserted index $insertedOriginalIndex into shuffle at position $insertPos (after shuffle pos $afterShufflePos)",
        )
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        listeners.forEach { it.onTimelineChanged(list, reason) }
    }
}