package com.maxrave.domain.data.entities.sync

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.LocalDateTime
import com.maxrave.domain.extension.now

/**
 * A record of something the user deleted.
 *
 * Merging two devices by taking the newest version of each record cannot express a deletion: the
 * deleted row is simply absent, which is indistinguishable from a row the other device has not seen
 * yet — so every sync would resurrect everything either side ever removed. A tombstone makes the
 * deletion itself a fact with a timestamp, which merges like any other.
 *
 * Rows are pruned once every device has certainly seen them; see [SyncTombstone.RETENTION_DAYS].
 */
@Entity(tableName = "sync_tombstone")
data class SyncTombstoneEntity(
    /**
     * `<type>:<id>` — for example `local_playlist:12` or `song_liked:dQw4w9WgXcQ`.
     *
     * A single natural key rather than a composite so the merge can index deletions by the same
     * string it uses to identify a live record.
     */
    @PrimaryKey
    val entityKey: String,
    val deletedAt: LocalDateTime = now(),
)

object SyncTombstone {
    const val TYPE_LOCAL_PLAYLIST = "local_playlist"
    const val TYPE_SONG_LIKED = "song_liked"
    const val TYPE_PLAYLIST_TRACK = "playlist_track"

    /**
     * How long a deletion is remembered.
     *
     * Long enough that a device left in a drawer for a month still learns about it, short enough
     * that the table does not grow without bound. A device offline longer than this and then
     * reconnected may resurrect the odd deleted item — the alternative is keeping every deletion
     * for ever.
     */
    const val RETENTION_DAYS = 90L

    fun key(
        type: String,
        id: String,
    ): String = "$type:$id"
}
