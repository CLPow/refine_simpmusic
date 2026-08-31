package com.maxrave.spotify.model.response.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slices of Spotify's Web API needed to read a user's library.
 *
 * Only the fields the import actually reads are declared; the client ignores unknown keys, so
 * Spotify adding to these payloads cannot break parsing.
 */
@Serializable
data class SpotifyPlaylistPage(
    val items: List<SpotifyPlaylistItem?> = emptyList(),
    val total: Int = 0,
    val next: String? = null,
)

@Serializable
data class SpotifyPlaylistItem(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val images: List<SpotifyImage>? = null,
    val tracks: SpotifyTrackRef? = null,
    val owner: SpotifyOwner? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
)

@Serializable
data class SpotifyOwner(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifyTrackRef(
    val total: Int = 0,
)

@Serializable
data class SpotifyImage(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** One page of `/v1/playlists/{id}/tracks` or `/v1/me/tracks` — both wrap the track the same way. */
@Serializable
data class SpotifyTrackPage(
    val items: List<SpotifyTrackEnvelope?> = emptyList(),
    val total: Int = 0,
    val next: String? = null,
)

@Serializable
data class SpotifyTrackEnvelope(
    /**
     * Null for an entry whose track has been removed from the catalogue, and for podcast episodes
     * in a mixed playlist. Both are skipped rather than treated as a failed match.
     */
    val track: SpotifyTrack? = null,
)

@Serializable
data class SpotifyTrack(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpotifyArtist>? = null,
    val album: SpotifyAlbum? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /**
     * A file the user added from their own machine. It exists nowhere but their Spotify client, so
     * there is nothing to search YouTube for.
     */
    @SerialName("is_local") val isLocal: Boolean = false,
)

@Serializable
data class SpotifyArtist(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class SpotifyAlbum(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpotifyImage>? = null,
)
