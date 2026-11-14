package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.type.HomeContentType
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "local_playlist")
@Suppress("ktlint:standard:property-naming")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val thumbnail: String? = null,
    val inLibrary: LocalDateTime = now(),
    val downloadedAt: LocalDateTime? = now(),
    val downloadState: Int = DownloadState.STATE_NOT_DOWNLOADED,
    val youtubePlaylistId: String? = null,
    @ColumnInfo(name = "youtube_sync_state", defaultValue = "0")
    val syncState: Int = YouTubeSyncState.NotSynced,
    /**
     * The Spotify playlist this was synced from, or null for a playlist made here.
     *
     * Identity for re-syncing. Matching on the title instead would break the moment someone renames
     * a playlist on either side, and would silently merge two playlists that happen to share a name.
     */
    @ColumnInfo(name = "spotify_playlist_uri", index = true)
    val spotifyPlaylistUri: String? = null,
    /**
     * When this playlist last changed, for merging two devices.
     *
     * Distinct from [inLibrary], which is when it was added: a merge needs to know which side holds
     * the newer version, and creation time cannot answer that. Nullable so rows written before this
     * column existed are recognisable as "unknown", rather than being dated to the migration and
     * winning every comparison against a genuinely newer copy.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
    val tracks: List<String>? = null,
    // Only synced with YouTube playlist
    // val listSetVideoId: List<String>? = null,
) : PlaylistType,
    HomeContentType {
    override fun playlistType(): PlaylistType.Type = PlaylistType.Type.LOCAL

    object YouTubeSyncState {
        const val NotSynced = 0
        const val Syncing = 1
        const val Synced = 2
    }
}