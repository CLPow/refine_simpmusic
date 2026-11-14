package com.maxrave.domain.mediaservice.player

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters

/**
 * Abstract interface for media player implementations
 */
interface MediaPlayerInterface {
    // Playback control
    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    )

    fun seekBack()

    fun seekForward()

    fun seekToNext()

    fun seekToPrevious()

    /**
     * Always advances to the previous media item, regardless of the current playback
     * position. This is the version used by UI affordances that should NOT exhibit
     * the "tap once to restart, tap again to go back" behaviour of [seekToPrevious]
     * (e.g. swiping the artwork pager). Implementations must skip the 3-second
     * "seek to start" threshold and go straight to the previous track.
     */
    fun seekToPreviousMediaItem()

    /**
     * A skip the listener asked for (in-app buttons, notification, headset, Android Auto): moves
     * like [seekToNext] and then plays, even if playback was paused, as YouTube Music does.
     * Skips the app makes by itself (track end, errors, explicit filter, SponsorBlock) use
     * [seekToNext] and keep the current play/pause intent.
     */
    fun skipToNext() = seekToNext()

    /** [seekToPrevious] that then plays; see [skipToNext]. */
    fun skipToPrevious() = seekToPrevious()

    /** [seekToPreviousMediaItem] that then plays; see [skipToNext]. */
    fun skipToPreviousMediaItem() = seekToPreviousMediaItem()

    fun prepare()

    // Media item management
    fun setMediaItem(mediaItem: GenericMediaItem)

    fun addMediaItem(mediaItem: GenericMediaItem)

    fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    )

    fun removeMediaItem(index: Int)

    fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    )

    /**
     * Moves an item between two positions of the PLAY order, which is what the Up next list shows
     * and what the user drags. With shuffle off that is the playlist order, so this is
     * [moveMediaItem]. With shuffle on only the shuffle order changes: moving playlist indices
     * there moved other songs than the ones dragged and dealt a new random order.
     */
    fun moveMediaItemInPlayOrder(
        fromPosition: Int,
        toPosition: Int,
    ) = moveMediaItem(fromPosition, toPosition)

    fun clearMediaItems()

    fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    )

    fun getMediaItemAt(index: Int): GenericMediaItem?

    fun getCurrentMediaTimeLine(): List<GenericMediaItem>

    fun getUnshuffledIndex(shuffledIndex: Int): Int

    // Playback state properties
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val bufferedPosition: Long
    val bufferedPercentage: Int
    val currentMediaItem: GenericMediaItem?
    val currentMediaItemIndex: Int
    val mediaItemCount: Int
    val contentPosition: Long
    val playbackState: Int

    // Navigation
    fun hasNextMediaItem(): Boolean

    fun hasPreviousMediaItem(): Boolean

    // Playback modes
    var shuffleModeEnabled: Boolean
    var repeatMode: Int
    var playWhenReady: Boolean
    var playbackParameters: GenericPlaybackParameters

    // Audio settings
    var volume: Float

    /**
     * Attenuation applied on top of [volume] while the sleep timer fades playback out,
     * in `0f..1f` — `1f` meaning no attenuation.
     *
     * Deliberately separate from [volume]: that one is the *user's* level, and it is
     * reported back through [MediaPlayerListener.onVolumeChanged], so ramping it would
     * drag the volume slider down in the UI and — if the process died mid-fade — leave
     * the user with a silent app. Same reasoning as the ducking factor applied on audio
     * focus loss: every owner keeps its own gain, and they are only multiplied together
     * at the point the value reaches a player.
     */
    var sleepFadeFactor: Float

    var skipSilenceEnabled: Boolean

    // Listener management
    fun addListener(listener: MediaPlayerListener)

    fun removeListener(listener: MediaPlayerListener)

    // Release resources
    fun release()
}