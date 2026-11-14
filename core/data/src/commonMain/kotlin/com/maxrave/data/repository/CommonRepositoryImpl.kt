package com.maxrave.data.repository

import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.fileSystem
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.ExperimentalTime

internal class CommonRepositoryImpl(
    private val coroutineScope: CoroutineScope,
    private val database: MusicDatabase,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : CommonRepository {
    @OptIn(ExperimentalTime::class)
    override fun init(
        cookiePath: String,
        dataStoreManager: DataStoreManager,
    ) {
        youTube.cookiePath = cookiePath.toPath()
        coroutineScope.launch {
            // The app is English-only, so YouTube is always asked for English (hl); the region (gl)
            // still follows the Location setting.
            val localeJob =
                launch {
                    dataStoreManager.location.collectLatest { location ->
                        youTube.locale = YouTubeLocale(location, "en")
                    }
                }
            val ytCookieJob =
                launch {
                    dataStoreManager.cookie.distinctUntilChanged().collectLatest { cookie ->
                        if (cookie.isNotEmpty()) {
                            youTube.cookie = cookie
                            youTube.visitorData()?.let {
                                youTube.visitorData = it
                            }
                        } else {
                            youTube.cookie = null
                        }
                        Logger.d("YouTube", "New cookie")
                        localDataSource.getUsedGoogleAccount()?.netscapeCookie?.let {
                            writeTextToFile(it, cookiePath)
                            Logger.w("YouTube", "Wrote cookie to file")
                        }
                    }
                }
            val pageIdJob =
                launch {
                    dataStoreManager.pageId.distinctUntilChanged().collectLatest { pageId ->
                        youTube.pageId = pageId.ifEmpty { null }
                        Logger.d("YouTube", "New pageId")
                        localDataSource.getUsedGoogleAccount()?.netscapeCookie?.let {
                            writeTextToFile(it, cookiePath)
                            Logger.w("YouTube", "Wrote cookie to file")
                        }
                    }
                }
            val authUserJob =
                launch {
                    dataStoreManager.authUser.distinctUntilChanged().collectLatest { authUser ->
                        youTube.authUser = authUser
                        Logger.d("YouTube", "New authUser")
                    }
                }
            val dataSyncIdJob =
                launch {
                    dataStoreManager.dataSyncId.collectLatest { dataSyncId ->
                        youTube.dataSyncId = dataSyncId
                    }
                }
            val visitorDataJob =
                launch {
                    dataStoreManager.visitorData.collectLatest { visitorData ->
                        youTube.visitorData = visitorData
                    }
                }
            localeJob.join()
            ytCookieJob.join()
            pageIdJob.join()
            authUserJob.join()
            dataSyncIdJob.join()
            visitorDataJob.join()
        }
    }

    override fun getDatabasePath() =
        com.maxrave.data.db
            .getDatabasePath()

    // Recently data
    override fun getAllRecentData(): Flow<List<RecentlyType>> =
        flow {
            emit(localDataSource.getAllRecentData())
        }.flowOn(Dispatchers.IO)

    override suspend fun writeTextToFile(
        text: String,
        filePath: String,
    ): Boolean {
        try {
            fileSystem().sink(filePath.toPath()).buffer().use { sink ->
                sink.writeUtf8(text)
                sink.close()
                return true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Original from YTDLnis app
     */
    override suspend fun getCookiesFromInternalDatabase(
        url: String,
        packageName: String,
    ): CookieItem =
        withContext(Dispatchers.IO) {
            return@withContext getCookies(
                url,
                packageName,
            )
        }
}

expect fun getCookies(
    url: String,
    packageName: String,
): CookieItem
