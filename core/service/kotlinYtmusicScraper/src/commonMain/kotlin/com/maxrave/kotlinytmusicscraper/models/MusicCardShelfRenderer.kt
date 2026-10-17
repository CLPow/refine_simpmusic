package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

/** The top result card of an unfiltered search. Every field is optional: YouTube drops some. */
@Serializable
data class MusicCardShelfRenderer(
    val title: Runs? = null,
    val subtitle: Runs? = null,
    val thumbnail: ThumbnailRenderer? = null,
    val header: Header? = null,
    val contents: List<Content>? = null,
    val buttons: List<Button>? = null,
    val onTap: NavigationEndpoint? = null,
    val subtitleBadges: List<Badges>? = null,
) {
    @Serializable
    data class Header(
        val musicCardShelfHeaderBasicRenderer: MusicCardShelfHeaderBasicRenderer? = null,
    ) {
        @Serializable
        data class MusicCardShelfHeaderBasicRenderer(
            val title: Runs? = null,
        )
    }

    @Serializable
    data class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    )
}
