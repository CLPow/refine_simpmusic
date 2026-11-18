package com.maxrave.data.repository

import com.maxrave.data.db.PodcastDao
import com.maxrave.data.parser.parseEpisodePage
import com.maxrave.data.parser.parsePodcastContinuation
import com.maxrave.data.parser.parsePodcastShow
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.PodcastEpisodesPage
import com.maxrave.domain.data.model.podcast.PodcastShow
import com.maxrave.domain.data.model.podcast.toEntity
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class PodcastRepositoryImpl(
    private val podcastDao: PodcastDao,
    private val youTube: YouTube,
) : PodcastRepository {
    override fun getPodcast(podcastId: String): Flow<Resource<PodcastShow>> =
        flow {
            val result = youTube.customQuery(browseId = podcastId)
            val show = result.getOrNull()?.let { parsePodcastShow(it, podcastId) }
            if (show != null) {
                emit(Resource.Success(show))
            } else {
                Logger.w(TAG, "Podcast $podcastId failed: ${result.exceptionOrNull()?.message}")
                emit(Resource.Error<PodcastShow>(result.exceptionOrNull()?.message ?: "Podcast unavailable"))
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun getMoreEpisodes(
        podcastId: String,
        podcastTitle: String?,
        continuation: String,
    ): PodcastEpisodesPage? =
        withContext(Dispatchers.IO) {
            youTube
                .customQuery(browseId = null, continuation = continuation)
                .onFailure { Logger.w(TAG, "Podcast continuation failed: ${it.message}") }
                .getOrNull()
                ?.let { parsePodcastContinuation(it, podcastId, podcastTitle, null) }
        }

    override suspend fun getEpisode(videoId: String): PodcastEpisode? =
        withContext(Dispatchers.IO) {
            youTube
                .customQuery(browseId = "MPED$videoId")
                .onFailure { Logger.w(TAG, "Episode $videoId failed: ${it.message}") }
                .getOrNull()
                ?.let { parseEpisodePage(it, videoId) }
        }

    override fun savedPodcasts(): Flow<List<PodcastsEntity>> = podcastDao.savedPodcasts()

    override fun isPodcastSaved(podcastId: String): Flow<Boolean> = podcastDao.isPodcastSaved(podcastId)

    override suspend fun savePodcast(podcast: PodcastsEntity) = withContext(Dispatchers.IO) { podcastDao.upsertPodcast(podcast) }

    override suspend fun removePodcast(podcastId: String) = withContext(Dispatchers.IO) { podcastDao.deletePodcast(podcastId) }

    override fun observeEpisode(videoId: String): Flow<EpisodeEntity?> = podcastDao.observeEpisode(videoId)

    override fun observeEpisodes(videoIds: List<String>): Flow<List<EpisodeEntity>> = podcastDao.observeEpisodes(videoIds)

    override fun episodesForLater(): Flow<List<EpisodeEntity>> = podcastDao.episodesForLater()

    override suspend fun ensureEpisodes(episodes: List<PodcastEpisode>) =
        withContext(Dispatchers.IO) {
            episodes.distinctBy { it.videoId }.forEach { episode ->
                val entity = episode.toEntity()
                val existing = podcastDao.getEpisode(entity.videoId)
                if (existing == null) podcastDao.insertEpisodeIfAbsent(entity)
                podcastDao.updateEpisodeInfo(
                    videoId = entity.videoId,
                    podcastId = entity.podcastId,
                    podcastTitle = entity.podcastTitle,
                    title = entity.title,
                    authorName = episode.author?.name ?: existing?.authorName?.takeIf { it.isNotBlank() } ?: entity.authorName,
                    description = entity.description,
                    createdDay = entity.createdDay,
                    durationString = entity.durationString,
                    thumbnail = entity.thumbnail,
                )
            }
        }

    override suspend fun setSavedForLater(
        episode: PodcastEpisode,
        saved: Boolean,
    ) = withContext(Dispatchers.IO) {
        ensureEpisodes(listOf(episode))
        podcastDao.setSavedForLater(episode.videoId, if (saved) Clock.System.now().toEpochMilliseconds() else null)
    }

    private companion object {
        const val TAG = "PodcastRepository"
    }
}
