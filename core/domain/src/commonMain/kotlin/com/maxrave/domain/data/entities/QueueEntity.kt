package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey(autoGenerate = false)
    val queueId: Long = 0,
    val listTrack: List<Track>,
    /**
     * Persisted [PlaylistType] name so album crossfade exclusions survive a restart.
     * Null means "unknown / pre-migration" and restores as [PlaylistType.PLAYLIST],
     * matching the previous hard-coded restore.
     */
    @ColumnInfo(defaultValue = "NULL")
    val playlistType: String? = null,
)