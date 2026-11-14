package com.maxrave.data.db

import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Suppress("ktlint:standard:class-naming")
@DeleteTable(
    tableName = "format",
)
internal class AutoMigration7_8 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        super.onPostMigrate(connection)
        connection.execSQL("DROP TABLE IF EXISTS `format`")
    }
}

@Suppress("ktlint:standard:class-naming")
@DeleteColumn(
    tableName = "local_playlist",
    columnName = "synced_with_youtube_playlist",
)
internal class AutoMigration11_12 : AutoMigrationSpec
/**
 * 31 drops the tables of features that are gone: lyrics translation, the AutoEq cache, release
 * notifications, podcasts, local listening analytics, sync tombstones and the followed-artist
 * release cache. Nothing reads or writes them any more.
 */
@Suppress("ktlint:standard:class-naming")
@DeleteTable.Entries(
    DeleteTable(tableName = "translated_lyrics"),
    DeleteTable(tableName = "autoeq_entry"),
    DeleteTable(tableName = "autoeq_index_meta"),
    DeleteTable(tableName = "autoeq_curve"),
    DeleteTable(tableName = "notification"),
    DeleteTable(tableName = "podcast_table"),
    DeleteTable(tableName = "podcast_episode_table"),
    DeleteTable(tableName = "playback_event"),
    DeleteTable(tableName = "event_artist"),
    DeleteTable(tableName = "sync_tombstone"),
    DeleteTable(tableName = "followed_artist_single_and_album"),
)
internal class AutoMigration30_31 : AutoMigrationSpec
