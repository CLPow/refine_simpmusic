package com.maxrave.data.parser.search

import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.searchResult.SearchChip
import com.maxrave.domain.data.model.searchResult.SearchRow
import com.maxrave.domain.data.model.searchResult.SearchRowKind
import com.maxrave.domain.data.model.searchResult.SearchSummary
import com.maxrave.domain.data.model.searchResult.SearchTopButton
import com.maxrave.domain.data.model.searchResult.SearchTopResult
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.NavigationEndpoint
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.response.SearchResponse

/** Chips Refine has pages for. Profiles, episodes and podcasts have none, so they are left out. */
private val SUPPORTED_CHIPS = setOf("Songs", "Videos", "Albums", "Artists", "Community playlists", "Featured playlists")

/**
 * Reads YouTube Music's unfiltered search page as YouTube laid it out: the top result card, then
 * each shelf ("Listen again", "More results", or the older per-type shelves) with its rows in
 * YouTube's ranking. Rows Refine cannot open (profiles, episodes, podcasts) are skipped.
 */
internal fun parseSearchSummary(response: SearchResponse): SearchSummary {
    val sectionList =
        response.contents
            ?.tabbedSearchResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
    val chips =
        sectionList
            ?.header
            ?.chipCloudRenderer
            ?.chips
            ?.mapNotNull { chip ->
                val renderer = chip.chipCloudChipRenderer
                val id = renderer.uniqueId ?: return@mapNotNull null
                val label = renderer.text?.runs?.joinToString("") { it.text } ?: id
                if (id in SUPPORTED_CHIPS) SearchChip(id, label) else null
            }.orEmpty()

    var top: SearchTopResult? = null
    val sections = mutableListOf<Pair<String, MutableList<SearchRow>>>()
    for (section in sectionList?.contents.orEmpty()) {
        section.musicCardShelfRenderer?.let { card ->
            val title = card.title?.runs?.joinToString("") { it.text }.orEmpty()
            val row =
                rowFrom(
                    title = title,
                    secondLine = card.subtitle?.runs.orEmpty(),
                    thumbnail = card.thumbnail,
                    endpoint = card.onTap ?: card.title?.runs?.firstOrNull()?.navigationEndpoint,
                    videoId = card.onTap?.watchEndpoint?.videoId,
                    videoType = card.onTap?.watchEndpoint?.musicVideoType(),
                )
            if (row != null) {
                top =
                    SearchTopResult(
                        row = row,
                        buttons =
                            card.buttons.orEmpty().mapNotNull { button ->
                                val renderer = button.buttonRenderer ?: return@mapNotNull null
                                val label = renderer.text?.runs?.joinToString("") { it.text } ?: return@mapNotNull null
                                val watch =
                                    (renderer.command ?: renderer.navigationEndpoint)?.let { it.watchPlaylistEndpoint ?: it.watchEndpoint }
                                        ?: return@mapNotNull null
                                SearchTopButton(
                                    label = label,
                                    endpoint =
                                        YouTubeWatchEndpoint(
                                            videoId = watch.videoId,
                                            playlistId = watch.playlistId,
                                            params = watch.params,
                                        ),
                                )
                            },
                        rows = card.contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer?.toSearchRow() },
                    )
            }
        }
        section.musicShelfRenderer?.let { shelf ->
            val title = shelf.title?.runs?.joinToString("") { it.text }.orEmpty()
            val rows = shelf.contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer?.toSearchRow() }
            sections += title to rows.toMutableList()
        }
        section.itemSectionRenderer?.contents?.forEach { item ->
            item.musicShelfRenderer?.let { shelf ->
                // A titled, empty shelf ("More results") heads the rows that follow it.
                val title = shelf.title?.runs?.joinToString("") { it.text }.orEmpty()
                val rows = shelf.contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer?.toSearchRow() }
                sections += title to rows.toMutableList()
            }
            item.musicResponsiveListItemRenderer?.toSearchRow()?.let { row ->
                if (sections.isEmpty()) sections += "" to mutableListOf()
                sections.last().second += row
            }
        }
    }
    return SearchSummary(
        chips = chips,
        top = top,
        sections = sections.filter { it.second.isNotEmpty() },
    )
}

private fun WatchEndpoint.musicVideoType(): String? = watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType

private fun MusicResponsiveListItemRenderer.toSearchRow(): SearchRow? {
    val columns = flexColumns.map { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
    val title = columns.getOrNull(0)?.joinToString("") { it.text }.orEmpty()
    return rowFrom(
        title = title,
        secondLine = columns.drop(1).flatMapIndexed { index, runs -> if (index == 0) runs else listOf(Run(" • ", null)) + runs },
        thumbnail = thumbnail,
        endpoint = navigationEndpoint ?: columns.getOrNull(0)?.firstOrNull()?.navigationEndpoint,
        videoId = videoId,
        videoType =
            musicVideoType
                ?: columns.getOrNull(0)?.firstOrNull()?.navigationEndpoint?.watchEndpoint?.musicVideoType()
                ?: overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.musicVideoType(),
        explicit = badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } == true,
    )
}

private fun rowFrom(
    title: String,
    secondLine: List<Run>,
    thumbnail: ThumbnailRenderer?,
    endpoint: NavigationEndpoint?,
    videoId: String?,
    videoType: String?,
    explicit: Boolean = false,
): SearchRow? {
    if (title.isBlank()) return null
    val pageType =
        endpoint
            ?.browseEndpoint
            ?.browseEndpointContextSupportedConfigs
            ?.browseEndpointContextMusicConfig
            ?.pageType
    val browseId = endpoint?.browseEndpoint?.browseId
    val kind =
        when {
            pageType == MUSIC_PAGE_TYPE_ARTIST -> SearchRowKind.ARTIST
            pageType == MUSIC_PAGE_TYPE_ALBUM -> SearchRowKind.ALBUM
            pageType == MUSIC_PAGE_TYPE_PLAYLIST -> SearchRowKind.PLAYLIST
            browseId != null -> return null
            videoId.isNullOrEmpty() -> return null
            videoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> return null
            MusicVideoType.isPlayableSong(videoType) -> SearchRowKind.SONG
            // Official videos, and the rest a "Videos" row can hold, play as audio.
            else -> SearchRowKind.VIDEO
        }
    val artists =
        secondLine
            .filter { run ->
                run.navigationEndpoint
                    ?.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType == MUSIC_PAGE_TYPE_ARTIST
            }.map { Artist(id = it.navigationEndpoint?.browseEndpoint?.browseId, name = it.text) }
            .ifEmpty {
                // Rows whose artist is not linked: the plain run after the type label ("Song •
                // YOASOBI"). A row with only a type and a length ("Song • 3:22") has none.
                val parts = secondLine.joinToString("") { it.text }.split(" • ")
                if (kind == SearchRowKind.SONG || kind == SearchRowKind.VIDEO) {
                    parts.getOrNull(1)?.takeIf { it.isNotBlank() && !it.contains(':') }
                        ?.let { listOf(Artist(id = null, name = it)) }
                        .orEmpty()
                } else {
                    emptyList()
                }
            }
    val album =
        secondLine
            .firstOrNull { run ->
                run.navigationEndpoint
                    ?.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType == MUSIC_PAGE_TYPE_ALBUM
            }?.let { run -> run.navigationEndpoint?.browseEndpoint?.browseId?.let { Album(id = it, name = run.text) } }
    val duration =
        secondLine
            .lastOrNull()
            ?.text
            ?.takeIf { it.matches(Regex("""\d+(:\d{2}){1,2}""")) }
            ?.split(":")
            ?.fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: 0) }
    val thumbnails =
        thumbnail
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.map { Thumbnail(height = it.height ?: 0, url = it.url, width = it.width ?: 0) }
            .orEmpty()
            .let { list ->
                // The list carries 60/120 px renditions; the largest is asked for at 544, as the
                // other result parsers do, so covers stay sharp.
                list.lastOrNull()?.let { best ->
                    listOf(best.copy(url = Regex("([wh])(60|120)").replace(best.url, "$1544")))
                }.orEmpty()
            }
    val content =
        Content(
            album = album,
            artists = artists,
            description = null,
            isExplicit = explicit,
            playlistId = null,
            browseId =
                when (kind) {
                    SearchRowKind.SONG, SearchRowKind.VIDEO -> null
                    else -> browseId
                },
            thumbnails = thumbnails,
            title = title,
            videoId = if (kind == SearchRowKind.SONG || kind == SearchRowKind.VIDEO) videoId else null,
            views = null,
            durationSeconds = duration,
            videoType = videoType,
        )
    return SearchRow(kind = kind, content = content, subtitle = secondLine.joinToString("") { it.text })
}
