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

/**
 * One resolved category cover plus when it was fetched.
 *
 * Kept with a timestamp because the cover is whatever playlist YouTube happened to rank first in
 * that category — it drifts, so a cached copy should expire rather than stick forever.
 */
@Serializable
private data class MoodArtwork(
    val url: String,
    val cachedAt: Long,
) {
    @OptIn(ExperimentalTime::class)
    fun isStale(): Boolean = Clock.System.now().toEpochMilliseconds() - cachedAt > MOOD_ARTWORK_TTL_MILLIS
}

private const val MOOD_ARTWORK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

@OptIn(ExperimentalTime::class)
internal class HomeRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val youTube: YouTube,
    private val localDataSource: LocalDataSource,
) : HomeRepository {
    private val _homeChips = MutableStateFlow<List<HomeChip>>(emptyList())
    override val homeChips: StateFlow<List<HomeChip>> = _homeChips
    /**
     * Same posture as the Room converters: a cache written by an older build must not crash the
     * app after the model gains a field, so unknown keys are dropped rather than rejected.
     */
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
            // Nothing here may throw past the flow: an exception used to be swallowed by a
            // runCatching around it, so the flow ended without emitting and Home sat on its spinner
            // until the timeout said "Couldn't load your recommendations". Signed-in feeds with an
            // empty `continuations` list did exactly that through `get(0)`.
            val response =
                try {
                    // The client is handed the signed-in cookie by its own collector, a moment after
                    // start. Asking before it has it loads the signed-out feed, so wait for it -- only
                    // as long as it actually takes, where the caller used to sleep a fixed second.
                    if (youTube.cookie.isNullOrEmpty() && dataStoreManager.cookie.first().isNotEmpty()) {
                        withTimeoutOrNull(3_000) {
                            while (youTube.cookie.isNullOrEmpty()) delay(50)
                        }
                    }
                    youTube.customQuery(browseId = "FEmusic_home", params = params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
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
                            if (accountName != "" && accountThumbUrl != "") {
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
                        // The artwork YouTube draws behind the top of Home (port of maxrave-dev/core adac4c90).
                        val background = result.background?.musicThumbnailRenderer?.getThumbnailUrl()
                        Triple(continueParam, list.toList(), background)
                    }
            home
                .onSuccess { emit(Resource.Success(it)) }
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

    private suspend fun readMoodArtworkCache(): Map<String, MoodArtwork> =
        dataStoreManager.moodArtworkCache
            .first()
            ?.let { runCatching { moodJson.decodeFromString<Map<String, MoodArtwork>>(it) }.getOrNull() }
            .orEmpty()
}