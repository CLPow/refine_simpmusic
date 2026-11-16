package com.maxrave.media3.repository

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.common.Config
import com.maxrave.domain.repository.CacheRepository

@UnstableApi
internal class CacheRepositoryImpl(
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
) : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long =
        when (cacheName) {
            Config.PLAYER_CACHE -> playerCache.cacheSpace
            Config.DOWNLOAD_CACHE -> downloadCache.cacheSpace
            else -> 0L
        }

    override fun clearCache(cacheName: String) {
        when (cacheName) {
            Config.PLAYER_CACHE -> playerCache.keys.forEach { playerCache.removeResource(it) }
            Config.DOWNLOAD_CACHE -> downloadCache.keys.forEach { downloadCache.removeResource(it) }
        }
    }

    override suspend fun getAllCacheKeys(cacheName: String): List<String> =
        when (cacheName) {
            Config.PLAYER_CACHE -> playerCache.keys.toList()
            Config.DOWNLOAD_CACHE -> downloadCache.keys.toList()
            else -> emptyList()
        }
}
