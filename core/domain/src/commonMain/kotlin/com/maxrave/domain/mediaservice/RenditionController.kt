package com.maxrave.domain.mediaservice

import com.maxrave.domain.data.model.browse.album.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class Rendition(
    val audioId: String,
    val videoId: String?,
) {
    val isVideo: Boolean get() = videoId != null
}

data class NowPlayingRenditions(
    val mediaId: String,
    val songId: String?,
    val videoId: String?,
    val resolving: Boolean,
    val playingVideo: Boolean,
) {
    val canSwitch: Boolean get() = songId != null && videoId != null
}

interface RenditionController {
    val videoMode: StateFlow<Boolean>

    val current: StateFlow<NowPlayingRenditions?>

    val reloadRequests: SharedFlow<String>

    fun setVideoMode(enabled: Boolean)

    fun setForeground(foreground: Boolean)

    fun setCarConnected(connected: Boolean)

    fun onNowPlaying(track: Track?)

    fun prefetch(track: Track)

    fun renditionFor(mediaId: String): Rendition

    fun markBuilt(
        mediaId: String,
        rendition: Rendition,
    )

    fun reportVideoFailed(mediaId: String)

    suspend fun fallBackToVideoAudio(mediaId: String): Boolean
}
