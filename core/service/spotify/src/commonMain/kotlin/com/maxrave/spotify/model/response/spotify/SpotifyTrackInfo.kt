package com.maxrave.spotify.model.response.spotify

/**
 * Liked Songs, addressed as the pseudo-playlist the web player treats it as. It has no id of
 * its own and no share link, so this URI is the only handle on it.
 */
const val LIKED_SONGS_URI = "spotify:collection:tracks"

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

/**
 * One entry in the user's Spotify library.
 *
 * @param uri the Spotify URI, which is also the stable identity a synced local playlist is matched
 * back to. Names change; this does not.
 * @param isLikedSongs Liked Songs is not a real playlist — it has no share link and cannot be made
 * public — so it is read through a pseudo-URI and flagged here rather than being special-cased by
 * name, which would break in any language but English.
 */
data class SpotifyLibraryItem(
    val uri: String,
    val name: String,
    val coverArt: String?,
    val isLikedSongs: Boolean = false,
)
