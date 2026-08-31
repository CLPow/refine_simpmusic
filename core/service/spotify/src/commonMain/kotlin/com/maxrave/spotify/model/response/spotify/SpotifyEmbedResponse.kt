package com.maxrave.spotify.model.response.spotify

import kotlinx.serialization.Serializable

/**
 * The `__NEXT_DATA__` blob embedded in a playlist's public embed page.
 *
 * Only the path down to the playlist entity is modelled; the client ignores unknown keys, so the
 * rest of the page state is dropped and Spotify adding to it cannot break parsing.
 */
@Serializable
data class SpotifyEmbedResponse(
    val props: Props? = null,
) {
    @Serializable
    data class Props(
        val pageProps: PageProps? = null,
    )

    @Serializable
    data class PageProps(
        val state: State? = null,
    )

    @Serializable
    data class State(
        val data: Data? = null,
    )

    @Serializable
    data class Data(
        val entity: Entity? = null,
    )

    @Serializable
    data class Entity(
        val name: String? = null,
        val title: String? = null,
        val type: String? = null,
        val coverArt: CoverArt? = null,
        val trackList: List<Track> = emptyList(),
    )

    @Serializable
    data class CoverArt(
        val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Source(
        val url: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    @Serializable
    data class Track(
        val uri: String? = null,
        val title: String? = null,
        /** The artist line, already joined by Spotify when a track has several. */
        val subtitle: String? = null,
        /** Milliseconds. */
        val duration: Long? = null,
        val isExplicit: Boolean = false,
    )
}
