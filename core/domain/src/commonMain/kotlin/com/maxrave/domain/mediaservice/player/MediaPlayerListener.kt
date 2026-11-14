package com.maxrave.domain.mediaservice.player

import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerError

/**
 * Listener interface for media player events
 */
interface MediaPlayerListener {
    fun onPlaybackStateChanged(playbackState: Int) {}

    fun onIsPlayingChanged(isPlaying: Boolean) {}

    /**
     * The current track's audio is really ready to play. [onIsPlayingChanged] is sent as soon as a
     * track starts loading, before a byte has arrived, so it cannot tell a track that plays from
     * one that is about to fail.
     */
    fun onPlaybackConfirmed() {}

    // Default no-op so non-emitting implementors (e.g. the JVM adapter) don't have to override it.
    fun onSeeked(positionMs: Long) {}

    fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {}

    fun onTimelineChanged(
        list: List<GenericMediaItem>, reason: String
    ) {}

    fun onTracksChanged(tracks: GenericTracks) {}

    fun onPlayerError(error: PlayerError) {}

    fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean, list: List<GenericMediaItem>) {}

    fun onRepeatModeChanged(repeatMode: Int) {}

    fun onIsLoadingChanged(isLoading: Boolean) {}

    fun onVolumeChanged(volume: Float) {}

    fun onCastStateChanged(castState: GenericCastState) {}
}