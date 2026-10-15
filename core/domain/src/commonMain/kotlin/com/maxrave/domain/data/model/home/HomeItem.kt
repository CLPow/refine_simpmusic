package com.maxrave.domain.data.model.home

import com.maxrave.domain.data.model.searchResult.songs.Thumbnail

data class HomeItem(
    val contents: List<Content?>,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: List<Thumbnail>? = null,
    val channelId: String? = null,
    /** True when YouTube sent the shelf as list rows (Quick picks), drawn as a grid of song rows. */
    val isListShelf: Boolean = false,
)