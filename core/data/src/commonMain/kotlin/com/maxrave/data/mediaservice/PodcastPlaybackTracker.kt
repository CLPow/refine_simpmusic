package com.maxrave.data.mediaservice

import com.maxrave.data.db.PodcastDao
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.PodcastPlayback
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PodcastPlaybackTracker(
    private val player: MediaPlayerHandler,
    private val podcastDao: PodcastDao,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            player.nowPlayingState
                .map { it.track ?: it.songEntity?.toTrack() }
                .distinctUntilChangedBy { it?.videoId }
                .collectLatest { track ->
                    val episode = track?.takeIf { MusicVideoType.isPodcast(it.videoType) }
                    applySpeed(episode != null)
                    if (episode != null) follow(episode)
                }
        }
        scope.launch {
            player.simpleMediaState.collect { state ->
                if (state !is SimpleMediaState.Ended) return@collect
                val track = player.nowPlayingState.value.track ?: return@collect
                if (!MusicVideoType.isPodcast(track.videoType)) return@collect
                val row = podcastDao.getEpisode(track.videoId) ?: return@collect
                podcastDao.updateEpisodeProgress(track.videoId, 0, row.durationMs, true, nowMs())
            }
        }
    }

    private suspend fun applySpeed(episode: Boolean) {
        val wanted = if (episode) PodcastPlayback.speedOf(dataStoreManager.getString(PodcastPlayback.SPEED_KEY).first()) else 1f
        if (dataStoreManager.playbackSpeed.first() != wanted) dataStoreManager.setPlaybackSpeed(wanted)
    }

    private suspend fun follow(track: Track) {
        val id = track.videoId
        podcastDao.insertEpisodeIfAbsent(
            EpisodeEntity(
                videoId = id,
                title = track.title,
                authorName = track.artists?.firstOrNull()?.name.orEmpty(),
                durationString = track.duration,
                thumbnail = track.thumbnails?.lastOrNull()?.url,
            ),
        )
        val durationMs = withTimeoutOrNull(DURATION_WAIT_MS) {
            while (player.getPlayerDuration() <= 0L) delay(250)
            player.getPlayerDuration()
        } ?: return
        val saved = podcastDao.getEpisode(id)
        if (saved != null && !saved.played && PodcastPlayback.shouldResume(saved.positionMs, durationMs) && player.getProgress() < RESUME_MIN_MS) {
            Logger.d(TAG, "Resuming episode $id at ${saved.positionMs}")
            player.onPlayerEvent(PlayerEvent.UpdateProgress(saved.positionMs * 100f / durationMs))
        }
        while (scope.isActive) {
            delay(SAVE_EVERY_MS)
            val position = player.getProgress()
            val duration = player.getPlayerDuration().takeIf { it > 0L } ?: durationMs
            if (position <= 0L) continue
            podcastDao.updateEpisodeProgress(id, position, duration, PodcastPlayback.isPlayed(position, duration), nowMs())
        }
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TAG = "PodcastPlayback"
        const val DURATION_WAIT_MS = 20_000L
        const val SAVE_EVERY_MS = 5_000L
        const val RESUME_MIN_MS = 3_000L
    }
}
