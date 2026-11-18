package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.PodcastEpisodesPage
import com.maxrave.domain.data.model.podcast.PodcastShow
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface PodcastRepository {
    fun getPodcast(podcastId: String): Flow<Resource<PodcastShow>>

    suspend fun getMoreEpisodes(
        podcastId: String,
        podcastTitle: String?,
        continuation: String,
    ): PodcastEpisodesPage?

    suspend fun getEpisode(videoId: String): PodcastEpisode?

    fun savedPodcasts(): Flow<List<PodcastsEntity>>

    fun isPodcastSaved(podcastId: String): Flow<Boolean>

    suspend fun savePodcast(podcast: PodcastsEntity)

    suspend fun removePodcast(podcastId: String)

    fun observeEpisode(videoId: String): Flow<EpisodeEntity?>

    fun observeEpisodes(videoIds: List<String>): Flow<List<EpisodeEntity>>

    fun episodesForLater(): Flow<List<EpisodeEntity>>

    suspend fun ensureEpisodes(episodes: List<PodcastEpisode>)

    suspend fun setSavedForLater(
        episode: PodcastEpisode,
        saved: Boolean,
    )
}
