package com.maxrave.data.parser

import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.PodcastEpisodesPage
import com.maxrave.domain.data.model.podcast.PodcastShow
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.pages.SearchResult

private fun List<Run>?.textOrNull(): String? = this?.joinToString("") { it.text }?.trim()?.takeIf { it.isNotEmpty() }

private fun List<Run>?.meaningful(): List<Run> = this.orEmpty().filter { it.text.trim().isNotEmpty() && it.text.trim() != "•" }

internal fun parsePodcastShow(
    response: BrowseResponse,
    podcastId: String,
): PodcastShow? {
    val twoColumn = response.contents?.twoColumnBrowseResultsRenderer ?: return null
    val header =
        twoColumn.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            ?.firstNotNullOfOrNull { it.musicResponsiveHeaderRenderer }
            ?: return null
    val title = header.title?.runs.textOrNull() ?: return null
    val authorRun = header.straplineTextOne?.runs?.firstOrNull()
    val author = authorRun?.let { Artist(id = it.navigationEndpoint?.browseEndpoint?.browseId, name = it.text) }
    val thumbnails =
        (
            header.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails
                ?: response.background?.musicThumbnailRenderer?.thumbnail?.thumbnails
        )?.toListThumbnail().orEmpty()
    val authorThumbnail =
        header.straplineThumbnail
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.lastOrNull()
            ?.url
    val description =
        header.description
            ?.musicDescriptionShelfRenderer
            ?.description
            ?.runs
            .textOrNull()
    val shelf =
        twoColumn.secondaryContents
            ?.sectionListRenderer
            ?.contents
            ?.firstNotNullOfOrNull { it.musicShelfRenderer }
    val episodes =
        shelf
            ?.contents
            .orEmpty()
            .mapNotNull { it.musicMultiRowListItemRenderer?.toPodcastEpisode(podcastId, title, author) }
    return PodcastShow(
        id = podcastId,
        title = title,
        author = author,
        authorThumbnail = authorThumbnail,
        thumbnails = thumbnails,
        description = description,
        episodes = episodes,
        continuation =
            shelf
                ?.continuations
                ?.firstOrNull()
                ?.nextContinuationData
                ?.continuation,
    )
}

internal fun parsePodcastContinuation(
    response: BrowseResponse,
    podcastId: String,
    podcastTitle: String?,
    author: Artist?,
): PodcastEpisodesPage? {
    val shelf = response.continuationContents?.musicShelfContinuation ?: return null
    return PodcastEpisodesPage(
        episodes = shelf.contents.mapNotNull { it.musicMultiRowListItemRenderer?.toPodcastEpisode(podcastId, podcastTitle, author) },
        continuation =
            shelf.continuations
                ?.firstOrNull()
                ?.nextContinuationData
                ?.continuation,
    )
}

internal fun MusicShelfRenderer.Content.MusicMultiRowListItemRenderer.toPodcastEpisode(
    podcastId: String?,
    podcastTitle: String?,
    author: Artist?,
): PodcastEpisode? {
    val videoId = onTap?.watchEndpoint?.videoId ?: return null
    val title = title?.runs.textOrNull() ?: return null
    return PodcastEpisode(
        videoId = videoId,
        title = title,
        podcastId = podcastId,
        podcastTitle = podcastTitle,
        author = author,
        description = description?.runs.textOrNull(),
        createdDay = subtitle?.runs.meaningful().lastOrNull()?.text?.trim(),
        durationString =
            playbackProgress
                ?.musicPlaybackProgressRenderer
                ?.durationText
                ?.runs
                .meaningful()
                .lastOrNull()
                ?.text
                ?.trim(),
        thumbnails =
            thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                .orEmpty(),
    )
}

internal fun parseEpisodePage(
    response: BrowseResponse,
    videoId: String,
): PodcastEpisode? {
    val twoColumn = response.contents?.twoColumnBrowseResultsRenderer ?: return null
    val header =
        twoColumn.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            ?.firstNotNullOfOrNull { it.musicResponsiveHeaderRenderer }
            ?: return null
    val title = header.title?.runs.textOrNull() ?: return null
    val showRun =
        header.straplineTextOne?.runs?.firstOrNull { it.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPSP") == true }
            ?: header.straplineTextOne?.runs?.firstOrNull()
    val description =
        twoColumn.secondaryContents
            ?.sectionListRenderer
            ?.contents
            ?.firstNotNullOfOrNull { it.musicDescriptionShelfRenderer }
            ?.description
            ?.runs
            .textOrNull()
    return PodcastEpisode(
        videoId = videoId,
        title = title,
        podcastId = showRun?.navigationEndpoint?.browseEndpoint?.browseId,
        podcastTitle = showRun?.text,
        author = null,
        description = description,
        createdDay = header.subtitle?.runs.meaningful().lastOrNull()?.text?.trim(),
        durationString = null,
        thumbnails =
            header.thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                .orEmpty(),
    )
}

internal fun parseEpisodeSearch(result: SearchResult): List<PodcastEpisode> =
    result.items.filterIsInstance<SongItem>().map { item ->
        val show =
            item.album?.let { Artist(id = it.id, name = it.name) }
                ?: item.artists.firstOrNull { it.id?.startsWith("MPSP") == true }?.let { Artist(id = it.id, name = it.name) }
        val date = item.artists.firstOrNull { it.id == null && it.name != show?.name }?.name
        PodcastEpisode(
            videoId = item.id,
            title = item.title,
            podcastId = show?.id,
            podcastTitle = show?.name,
            author = null,
            description = null,
            createdDay = date,
            durationString = null,
            thumbnails =
                item.thumbnails
                    ?.thumbnails
                    ?.toListThumbnail()
                    .orEmpty(),
        )
    }
