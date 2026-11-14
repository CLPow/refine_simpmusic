package com.maxrave.domain.repository

import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow

interface CommonRepository {
    fun init(cookiePath: String, dataStoreManager: DataStoreManager)

    fun getDatabasePath(): String?

    // Recently data
    fun getAllRecentData(): Flow<List<RecentlyType>>

    suspend fun writeTextToFile(text: String, filePath: String): Boolean

    suspend fun getCookiesFromInternalDatabase(url: String, packageName: String): CookieItem
}