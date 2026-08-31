package com.maxrave.spotify.model.response.spotify

/**
 * A Spotify track reduced to what matching against YouTube Music actually needs.
 *
 * Both sources produce this: the public embed page and the internal pathfinder API. Keeping one
 * type means the matcher does not care which door the tracks came through, and a second source can
 * be added without touching it.
 */
data class SpotifyTrackInfo(
    val title: String,
    /** The artist line, joined when a track has several. */
    val artist: String,
    val durationMs: Long?,
)

/**
 * One page of a playlist.
 *
 * @param total tracks in the whole playlist, when the source states it. The embed page does not,
 * which is why it cannot page and cannot tell a 100-track playlist from a truncated one.
 */
data class SpotifyPlaylistSlice(
    val name: String?,
    val coverArt: String?,
    val total: Int?,
    val tracks: List<SpotifyTrackInfo>,
)
