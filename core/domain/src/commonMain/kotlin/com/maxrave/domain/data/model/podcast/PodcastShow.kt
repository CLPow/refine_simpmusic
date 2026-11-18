package com.maxrave.domain.data.model.podcast

import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.mediaservice.PodcastPlayback

data class PodcastEpisode(
    val videoId: String,
    val title: String,
    val podcastId: String?,
    val podcastTitle: String?,
    val author: Artist?,
    val description: String?,
    val createdDay: String?,
    val durationString: String?,
    val thumbnails: List<Thumbnail>,
)

data class PodcastShow(
    val id: String,
    val title: String,
    val author: Artist?,
    val authorThumbnail: String?,
    val thumbnails: List<Thumbnail>,
    val description: String?,
    val episodes: List<PodcastEpisode>,
    val continuation: String?,
)

data class PodcastEpisodesPage(
    val episodes: List<PodcastEpisode>,
    val continuation: String?,
)

fun String.parseEpisodeDurationSeconds(): Int? {
    val text = trim()
    if (text.isEmpty()) return null
    if (text.contains(':')) {
        val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, part -> acc * 60 + part }
    }
    var total = 0
    var matched = false
    Regex("""(\d+)\s*(hr|hour|hours|h|min|mins|minute|minutes|m|sec|secs|second|seconds|s)\b""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .forEach { match ->
            matched = true
            val value = match.groupValues[1].toInt()
            total +=
                when (match.groupValues[2].lowercase()) {
                    "hr", "hour", "hours", "h" -> value * 3600
                    "min", "mins", "minute", "minutes", "m" -> value * 60
                    else -> value
                }
        }
    return if (matched) total else null
}

fun PodcastEpisode.toTrack(): Track =
    Track(
        album = null,
        artists = listOfNotNull(author ?: podcastTitle?.let { Artist(id = podcastId, name = it) }),
        duration = durationString,
        durationSeconds = durationString?.parseEpisodeDurationSeconds(),
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = thumbnails,
        title = title,
        videoId = videoId,
        videoType = PodcastPlayback.EPISODE_VIDEO_TYPE,
        category = "Podcast",
        feedbackTokens = null,
        resultType = "Podcast",
        year = createdDay,
    )

fun PodcastEpisode.toEntity(): EpisodeEntity =
    EpisodeEntity(
        videoId = videoId,
        podcastId = podcastId,
        podcastTitle = podcastTitle,
        title = title,
        authorName = author?.name ?: podcastTitle.orEmpty(),
        description = description,
        createdDay = createdDay,
        durationString = durationString,
        thumbnail = thumbnails.lastOrNull()?.url,
    )

fun EpisodeEntity.toPodcastEpisode(): PodcastEpisode =
    PodcastEpisode(
        videoId = videoId,
        title = title,
        podcastId = podcastId,
        podcastTitle = podcastTitle,
        author = authorName.takeIf { it.isNotBlank() }?.let { Artist(id = null, name = it) },
        description = description,
        createdDay = createdDay,
        durationString = durationString,
        thumbnails = thumbnail?.let { listOf(Thumbnail(height = 0, url = it, width = 0)) }.orEmpty(),
    )
