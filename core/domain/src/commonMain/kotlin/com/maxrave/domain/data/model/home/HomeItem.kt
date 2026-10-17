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
    /**
     * True for the account's own shelf at the top of Home: the one headed by the account's name
     * and picture. YouTube Music's phone app draws it as Speed dial, a paged grid of tiles; the
     * web feed calls the same shelf "Listen again".
     */
    val isSpeedDial: Boolean = false,
)