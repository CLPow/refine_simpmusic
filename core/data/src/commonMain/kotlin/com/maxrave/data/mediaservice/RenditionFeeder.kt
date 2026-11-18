package com.maxrave.data.mediaservice

import com.maxrave.domain.data.player.AppOnScreen
import com.maxrave.domain.mediaservice.RenditionController
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class RenditionFeeder(
    private val controller: RenditionController,
    private val player: MediaPlayerHandler,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            AppOnScreen.onScreen.collect { controller.setForeground(it) }
        }
        scope.launch {
            player.nowPlayingState
                .map { it.track ?: it.songEntity?.toTrack() }
                .distinctUntilChangedBy { it?.videoId }
                .collect { controller.onNowPlaying(it) }
        }
        scope.launch {
            combine(player.queueData, player.currentSongIndex) { queue, index ->
                val tracks = queue?.data?.listTracks.orEmpty()
                (1..PREFETCH_AHEAD).mapNotNull { tracks.getOrNull(index + it) }
            }.distinctUntilChanged()
                .collect { upcoming -> upcoming.forEach(controller::prefetch) }
        }
    }

    private companion object {
        const val PREFETCH_AHEAD = 2
    }
}
