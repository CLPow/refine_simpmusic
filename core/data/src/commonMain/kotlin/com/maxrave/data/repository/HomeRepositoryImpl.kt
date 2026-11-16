package com.maxrave.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.maxrave.domain.data.model.home.HomeChip
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.maxrave.data.db.datasource.LocalDataSource
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import com.maxrave.data.io.fileDir
import com.maxrave.data.io.fileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile

@Serializable
private data class MoodArtwork(
    val url: String,
    val cachedAt: Long,
) {
    @OptIn(ExperimentalTime::class)
    fun isStale(): Boolean = Clock.System.now().toEpochMilliseconds() - cachedAt > MOOD_ARTWORK_TTL_MILLIS
}

private const val MOOD_ARTWORK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

@Serializable
private data class CachedHome(
    val items: List<HomeItem>,
    val background: String? = null,
)

@OptIn(ExperimentalTime::class)
internal class HomeRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val youTube: YouTube,
    private val localDataSource: LocalDataSource,
) : HomeRepository {
    private val _homeChips = MutableStateFlow<List<HomeChip>>(emptyList())
    override val homeChips: StateFlow<List<HomeChip>> = _homeChips
    private val moodJson =
        Json {
            ignoreUnknownKeys = true
        }

    override fun getHomeData(
        params: String?,
        viewString: String,
        songString: String,
    ): Flow<Resource<Triple<String?, List<HomeItem>, String?>>> =
        flow {
            val anonymous = youTube.accountGuard.isCoolingDown
            if (anonymous) Logger.w("Repository", "Account cooling down ${youTube.accountGuard.cooldownRemainingMs / 1000}s, loading Home signed out")
            val response =
                try {
                    if (!anonymous && youTube.cookie.isNullOrEmpty() && dataStoreManager.cookie.first().isNotEmpty()) {
                        withTimeoutOrNull(3_000) {
                            while (youTube.cookie.isNullOrEmpty()) delay(50)
                        }
                    }
                    youTube.customQuery(browseId = "FEmusic_home", params = params, withoutLogin = anonymous)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            lastHomeWasAnonymous = anonymous
            val home =
                response.mapCatching { result ->
                        val list: ArrayList<HomeItem> = arrayListOf()
                        val sectionList =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                        sectionList
                            ?.header
                            ?.chipCloudRenderer
                            ?.chips
                            ?.mapNotNull { chip ->
                                val renderer = chip.chipCloudChipRenderer
                                val title = renderer.text?.runs?.joinToString("") { it.text }
                                val chipParams = renderer.navigationEndpoint.browseEndpoint?.params
                                if (title.isNullOrBlank() || chipParams.isNullOrBlank()) null else HomeChip(title, chipParams)
                            }?.takeIf { it.isNotEmpty() }
                            ?.let { _homeChips.value = it }
                        var hasAccountShelf = false
                        if (result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()?.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.strapline
                                ?.runs
                                ?.firstOrNull()?.text != null
                        ) {
                            val accountName =
                                result.contents
                                    ?.singleColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()?.musicCarouselShelfRenderer
                                    ?.header
                                    ?.musicCarouselShelfBasicHeaderRenderer
                                    ?.strapline
                                    ?.runs
                                    ?.firstOrNull()?.text ?: ""
                            val accountThumbUrl =
                                result.contents
                                    ?.singleColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()?.musicCarouselShelfRenderer
                                    ?.header
                                    ?.musicCarouselShelfBasicHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.thumbnail
                                    ?.thumbnails
                                    ?.firstOrNull()?.url
                                    ?.replace("s88", "s352") ?: ""
                            if (!anonymous && accountName != "" && accountThumbUrl != "") {
                                dataStoreManager.putString("AccountName", accountName)
                                dataStoreManager.putString("AccountThumbUrl", accountThumbUrl)
                                hasAccountShelf = params == null
                            }
                        }
                        val continueParam =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.continuations
                                ?.firstOrNull()?.nextContinuationData
                                ?.continuation
                        val data =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                        list.addAll(
                            parseMixedContent(
                                data,
                                viewString,
                                songString,
                            ),
                        )
                        if (hasAccountShelf && list.isNotEmpty()) {
                            list[0] = list[0].copy(isSpeedDial = true)
                        }
                        Logger.d("Repository", "List size: ${list.size}")
                        val background = result.background?.musicThumbnailRenderer?.getThumbnailUrl()
                        Triple(continueParam, list.toList(), background)
                    }
            home
                .onSuccess {
                    if (params == null && !anonymous && it.second.isNotEmpty()) saveCachedHome(it.second, it.third)
                    emit(Resource.Success(it))
                }
                .onFailure { error ->
                    Logger.e("Repository", "Home failed: ${error.message}")
                    emit(Resource.Error<Triple<String?, List<HomeItem>, String?>>(error.message ?: "Home unavailable"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> = flow {
        youTube
            .customQuery(browseId = "", continuation = continueParam)
            .onSuccess { response ->
                val newContinueParam =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.firstOrNull()
                        ?.nextContinuationData
                        ?.continuation
                Logger.d("Repository", "continueParam: $continueParam")
                val dataContinue =
                    response.continuationContents?.sectionListContinuation?.contents
                val list =
                    parseMixedContent(
                        dataContinue,
                        viewString,
                        songString,
                    )
                emit(Resource.Success(newContinueParam to list))
            }.onFailure {
                emit(Resource.Error<Pair<String?, List<HomeItem>>>(it.message.toString()))
            }
    }.flowOn(Dispatchers.IO)

    @Volatile
    override var lastHomeWasAnonymous: Boolean = false
        private set

    private val homeCachePath get() = "${fileDir()}/home_cache.json".toPath()

    override suspend fun getCachedHome(): Pair<List<HomeItem>, String?>? =
        withContext(Dispatchers.IO) {
            try {
                val fs = fileSystem()
                if (!fs.exists(homeCachePath)) return@withContext null
                val cached = moodJson.decodeFromString<CachedHome>(fs.read(homeCachePath) { readUtf8() })
                cached.items.takeIf { it.isNotEmpty() }?.let { it to cached.background }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("Repository", "Home cache unreadable: ${e.message}")
                null
            }
        }

    private suspend fun saveCachedHome(
        items: List<HomeItem>,
        background: String?,
    ) {
        try {
            val fs = fileSystem()
            val tmp = "${fileDir()}/home_cache.json.tmp".toPath()
            fs.write(tmp) { writeUtf8(moodJson.encodeToString(CachedHome(items, background))) }
            fs.atomicMove(tmp, homeCachePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Repository", "Home cache not saved: ${e.message}")
        }
    }

    private suspend fun readMoodArtworkCache(): Map<String, MoodArtwork> =
        dataStoreManager.moodArtworkCache
            .first()
            ?.let { runCatching { moodJson.decodeFromString<Map<String, MoodArtwork>>(it) }.getOrNull() }
            .orEmpty()
}