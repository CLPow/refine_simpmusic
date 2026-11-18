package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "podcast_table")
data class PodcastsEntity(
    @PrimaryKey val podcastId: String,
    val title: String,
    val authorId: String? = null,
    val authorName: String,
    val description: String? = null,
    val thumbnail: String? = null,
    val savedAt: Long,
)

@Entity(tableName = "podcast_episode_table", indices = [Index("podcastId")])
data class EpisodeEntity(
    @PrimaryKey val videoId: String,
    val podcastId: String? = null,
    val podcastTitle: String? = null,
    val title: String,
    val authorName: String,
    val description: String? = null,
    val createdDay: String? = null,
    val durationString: String? = null,
    val thumbnail: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val played: Boolean = false,
    val savedForLaterAt: Long? = null,
    val lastPlayedAt: Long? = null,
) {
    val progress: Float
        get() = if (played) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Entity(tableName = "rendition_pair")
data class RenditionPairEntity(
    @PrimaryKey val videoId: String,
    val counterpartId: String?,
    val checkedAt: Long,
)
