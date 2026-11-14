package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.io.getHomeFolderPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

actual fun createDataStoreInstance(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage =
            ReadsWaitForWritesStorage(
                FileStorage(PreferencesFileSerializer) {
                    File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb").absoluteFile
                },
            ),
    )

private class ReadsWaitForWritesStorage<T>(
    private val delegate: Storage<T>,
) : Storage<T> {
    override fun createConnection(): StorageConnection<T> {
        val connection = delegate.createConnection()
        val lock = Mutex()
        return object : StorageConnection<T> by connection {
            override suspend fun <R> readScope(block: suspend ReadScope<T>.(locked: Boolean) -> R): R =
                lock.withLock { connection.readScope(block) }

            override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) =
                lock.withLock { connection.writeScope(block) }
        }
    }
}
