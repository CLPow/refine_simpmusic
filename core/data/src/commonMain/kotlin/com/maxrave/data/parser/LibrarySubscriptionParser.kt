package com.maxrave.data.parser

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse

/**
 * Parses the subscribed-artists library page — `browseId = "FEmusic_library_corpus_artists"`.
 *
 * Not to be confused with `FEmusic_library_corpus_track_artists`, which is the artists you happen
 * to have songs from. This one is the channel subscriptions themselves, which is what the Follow
 * flag mirrors.
 *
 * Rows arrive as a `musicShelfRenderer`, but YouTube serves the same library page as a
 * `gridRenderer` of two-row cards for some accounts and locales, so both shapes are read. A row
 * with no channel id is dropped rather than stored under a blank key — `channelId` is the primary
 * key of the `artist` table, so one blank row would collide with the next.
 */
internal fun BrowseResponse.toSubscribedArtists(): List<ArtistEntity> {
    val sections =
        contents
            ?.singleColumnBrowseResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            ?: return emptyList()

    return sections
        .flatMap { section ->
            val fromShelf =
                section.musicShelfRenderer
                    ?.contents
                    ?.mapNotNull { it.musicResponsiveListItemRenderer.toSubscribedArtist() }
                    .orEmpty()
            val fromGrid =
                section.gridRenderer
                    ?.items
                    ?.mapNotNull { item ->
                        val card = item.musicTwoRowItemRenderer ?: return@mapNotNull null
                        val channelId = card.navigationEndpoint.browseEndpoint?.browseId ?: return@mapNotNull null
                        val name = card.title.runs?.firstOrNull()?.text ?: return@mapNotNull null
                        artistEntity(
                            channelId = channelId,
                            name = name,
                            thumbnail = card.thumbnailRenderer.largestThumbnailUrl(),
                        )
                    }.orEmpty()
            fromShelf + fromGrid
        }
        // The same artist can appear in both a shelf and a grid on a page carrying each.
        .distinctBy { it.channelId }
}

/** Continuation token for the next page of the first response, or null when the list is complete. */
internal fun BrowseResponse.subscribedArtistsContinuation(): String? =
    contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        ?.firstNotNullOfOrNull { it.musicShelfRenderer?.continuations ?: it.gridRenderer?.continuations }
        ?.firstOrNull()
        ?.nextContinuationData
        ?.continuation

/**
 * Rows of a continuation response. These arrive bare in `continuationContents` rather than wrapped
 * in a tab, and in a different `Content` type from the first page — hence the separate walk.
 */
internal fun BrowseResponse.toSubscribedArtistsContinuationPage(): Pair<List<ArtistEntity>, String?> {
    val shelf = continuationContents?.musicShelfContinuation
    val artists =
        shelf
            ?.contents
            ?.mapNotNull { it.musicResponsiveListItemRenderer.toSubscribedArtist() }
            .orEmpty()
    val next =
        shelf
            ?.continuations
            ?.firstOrNull()
            ?.nextContinuationData
            ?.continuation
    return artists to next
}

/**
 * Takes the renderer itself rather than either wrapper `Content` type: the first page and the
 * continuation page carry the same row inside two unrelated classes.
 */
private fun MusicResponsiveListItemRenderer?.toSubscribedArtist(): ArtistEntity? {
    val row = this ?: return null
    val channelId = row.navigationEndpoint?.browseEndpoint?.browseId ?: return null
    val name =
        row.flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull()
            ?.text
            ?: return null
    return artistEntity(
        channelId = channelId,
        name = name,
        thumbnail = row.thumbnail.largestThumbnailUrl(),
    )
}

/**
 * Largest variant on offer. The library grid renders these at card size and the smallest URL
 * YouTube returns here is 60px, which is visibly soft on a desktop window.
 *
 * `croppedSquareThumbnailRenderer` is the artist-avatar shape and is what this page actually
 * sends; `musicThumbnailRenderer` shares its `@JsonNames` alias, so reading both costs nothing
 * and covers whichever the response used.
 */
private fun com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer?.largestThumbnailUrl(): String? {
    val renderer = this ?: return null
    return (renderer.musicThumbnailRenderer ?: renderer.croppedSquareThumbnailRenderer)
        ?.thumbnail
        ?.thumbnails
        ?.lastOrNull()
        ?.url
}

private fun artistEntity(
    channelId: String,
    name: String,
    thumbnail: String?,
) = ArtistEntity(
    channelId = channelId,
    name = name,
    thumbnails = thumbnail,
    followed = true,
)
