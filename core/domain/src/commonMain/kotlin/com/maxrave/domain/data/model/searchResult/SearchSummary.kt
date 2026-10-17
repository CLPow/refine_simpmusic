package com.maxrave.domain.data.model.searchResult

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint

/** What a search row opens: songs and videos play, the rest open their page. */
enum class SearchRowKind { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST }

/**
 * One row of YouTube Music's search page. [subtitle] is YouTube's own second line ("Song •
 * YOASOBI • 3:06", "Single • YOASOBI • 2023"), shown as sent; [content] carries the ids.
 */
data class SearchRow(
    val kind: SearchRowKind,
    val content: Content,
    val subtitle: String,
)

/** A button on the top result card: Shuffle and Mix for an artist, Play for an album. */
data class SearchTopButton(
    val label: String,
    val endpoint: YouTubeWatchEndpoint,
)

/** The top result card with the rows YouTube puts under it. */
data class SearchTopResult(
    val row: SearchRow,
    val buttons: List<SearchTopButton>,
    val rows: List<SearchRow>,
)

/** A filter chip, by YouTube's stable id ("Songs", "Videos", ...) and its shown label. */
data class SearchChip(
    val id: String,
    val label: String,
)

/** The unfiltered search page, in YouTube's order. */
data class SearchSummary(
    val chips: List<SearchChip>,
    val top: SearchTopResult?,
    val sections: List<Pair<String, List<SearchRow>>>,
)
