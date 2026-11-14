package com.maxrave.domain.data.model.searchResult

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint

enum class SearchRowKind { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST }

data class SearchRow(
    val kind: SearchRowKind,
    val content: Content,
    val subtitle: String,
)

data class SearchTopButton(
    val label: String,
    val endpoint: YouTubeWatchEndpoint,
)

data class SearchTopResult(
    val row: SearchRow,
    val buttons: List<SearchTopButton>,
    val rows: List<SearchRow>,
)

data class SearchChip(
    val id: String,
    val label: String,
)

data class SearchSummary(
    val chips: List<SearchChip>,
    val top: SearchTopResult?,
    val sections: List<Pair<String, List<SearchRow>>>,
)
