package com.maxrave.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Pulls the signed-in Spotify account's library across to local playlists.
 *
 * Spotify and YouTube share no track identifiers, so every track has to be found by searching
 * YouTube Music for its title and artist. That search is the slow, lossy part of the job, and the
 * reason this reports progress rather than simply returning a result.
 */
interface SpotifyImportRepository {
    /**
     * Imports Liked Songs and every playlist on the account.
     *
     * Cancelling the collecting scope stops the import; playlists already written stay written.
     */
    fun importEverything(): Flow<SpotifyImportProgress>
}

sealed interface SpotifyImportProgress {
    /** Signing in to Spotify and enumerating the library, before any track work starts. */
    data object Preparing : SpotifyImportProgress

    /**
     * @param playlistName the playlist currently being matched
     * @param playlistIndex 1-based, for "playlist 3 of 12"
     * @param processed tracks looked at so far across the whole import
     * @param total tracks in the whole import, known once enumeration finishes
     */
    data class Matching(
        val playlistName: String,
        val playlistIndex: Int,
        val playlistCount: Int,
        val processed: Int,
        val total: Int,
        val matched: Int,
    ) : SpotifyImportProgress

    data class Success(
        val result: SpotifyImportResult,
    ) : SpotifyImportProgress

    data class Error(
        val message: String,
    ) : SpotifyImportProgress
}

/**
 * @param unmatched tracks with no acceptable YouTube result. Expected to be non-zero on any real
 * library — regional gaps, local files and obscure releases simply are not all on YouTube.
 */
data class SpotifyImportResult(
    val playlistsCreated: Int,
    val playlistsSkipped: Int,
    val tracksMatched: Int,
    val unmatched: Int,
)
