package com.maxrave.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.RenditionPairEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPodcast(podcast: PodcastsEntity)

    @Query("DELETE FROM podcast_table WHERE podcastId = :podcastId")
    suspend fun deletePodcast(podcastId: String)

    @Query("SELECT * FROM podcast_table ORDER BY savedAt DESC")
    fun savedPodcasts(): Flow<List<PodcastsEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM podcast_table WHERE podcastId = :podcastId)")
    fun isPodcastSaved(podcastId: String): Flow<Boolean>

    @Query("SELECT * FROM podcast_episode_table WHERE videoId = :videoId")
    suspend fun getEpisode(videoId: String): EpisodeEntity?

    @Query("SELECT * FROM podcast_episode_table WHERE videoId = :videoId")
    fun observeEpisode(videoId: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM podcast_episode_table WHERE videoId IN (:videoIds)")
    fun observeEpisodes(videoIds: List<String>): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM podcast_episode_table WHERE savedForLaterAt IS NOT NULL ORDER BY savedForLaterAt DESC")
    fun episodesForLater(): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodeIfAbsent(episode: EpisodeEntity)

    @Query(
        "UPDATE podcast_episode_table SET podcastId = COALESCE(:podcastId, podcastId), podcastTitle = COALESCE(:podcastTitle, podcastTitle), " +
            "title = :title, authorName = :authorName, description = COALESCE(:description, description), " +
            "createdDay = COALESCE(:createdDay, createdDay), durationString = COALESCE(:durationString, durationString), " +
            "thumbnail = COALESCE(:thumbnail, thumbnail) WHERE videoId = :videoId",
    )
    suspend fun updateEpisodeInfo(
        videoId: String,
        podcastId: String?,
        podcastTitle: String?,
        title: String,
        authorName: String,
        description: String?,
        createdDay: String?,
        durationString: String?,
        thumbnail: String?,
    )

    @Query(
        "UPDATE podcast_episode_table SET positionMs = :positionMs, durationMs = :durationMs, played = :played, " +
            "lastPlayedAt = :lastPlayedAt WHERE videoId = :videoId",
    )
    suspend fun updateEpisodeProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        played: Boolean,
        lastPlayedAt: Long,
    )

    @Query("UPDATE podcast_episode_table SET savedForLaterAt = :savedAt WHERE videoId = :videoId")
    suspend fun setSavedForLater(
        videoId: String,
        savedAt: Long?,
    )

    @Query("SELECT * FROM rendition_pair WHERE videoId = :videoId")
    suspend fun getRenditionPair(videoId: String): RenditionPairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRenditionPair(pair: RenditionPairEntity)
}
