package com.maxrave.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Recreates a Spotify playlist locally from its shareable link.
 *
 * Spotify and YouTube share no track identifiers, so every track has to be found by searching
 * YouTube Music for its title and artist. That search is the slow, lossy part of the job, and the
 * reason this reports progress rather than simply returning a result.
 *
 * A link rather than the signed-in account, because Spotify refuses this app's web-player token on
 * its public Web API. The embed page behind a share link needs no credential at all.
 */
interface SpotifyImportRepository {
    /**
     * @param link a Spotify playlist URL or URI, in any of the forms the apps hand out
     *
     * Cancelling the collecting scope stops the import before anything is written.
     */
    fun importFromLink(link: String): Flow<SpotifyImportProgress>

    /**
     * Syncs every playlist in the signed-in Spotify library, Liked Songs included.
     *
     * Re-runnable by design. A playlist already synced is updated in place rather than duplicated,
     * and a track already present is skipped without being searched again, so syncing an unchanged
     * library costs no YouTube requests at all.
     */
    fun syncEverything(): Flow<SpotifyImportProgress>
}

sealed interface SpotifyImportProgress {
    /** Reading the playlist, before any track work starts. */
    data object Preparing : SpotifyImportProgress

    /**
     * @param processed tracks looked at so far
     * @param matched tracks found on YouTube Music so far
     */
    data class Matching(
        val playlistName: String,
        val processed: Int,
        val total: Int,
        val matched: Int,
        /** 1-based position in a multi-playlist sync; null for a single link import. */
        val playlistIndex: Int? = null,
        val playlistCount: Int? = null,
    ) : SpotifyImportProgress

    data class Success(
        val result: SpotifyImportResult,
    ) : SpotifyImportProgress

    data class Error(
        val message: String,
    ) : SpotifyImportProgress
}

/**
 * @param unmatched tracks with no acceptable YouTube result. Expected to be non-zero on a real
 * playlist -- regional gaps, local files and obscure releases simply are not all on YouTube.
 * @param possiblyTruncated the embed page returns at most 100 tracks and states no total, so a
 * playlist that comes back with exactly 100 may well be longer. Reported so the count is never
 * silently short.
 */
data class SpotifyImportResult(
    val playlistName: String,
    val tracksMatched: Int,
    val unmatched: Int,
    val possiblyTruncated: Boolean,
    /** Playlists created for the first time by this run. */
    val playlistsCreated: Int = 0,
    /** Playlists that already existed and were topped up in place. */
    val playlistsUpdated: Int = 0,
    /** Tracks skipped because the local playlist already had them; no search was spent on these. */
    val alreadyPresent: Int = 0,
)
