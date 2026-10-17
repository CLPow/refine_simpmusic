package com.maxrave.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.maxrave.domain.data.model.home.HomeChip
import com.maxrave.data.parser.parseChart
import com.maxrave.data.parser.parseGenreObject
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.data.parser.parseMoodsMomentObject
import com.maxrave.data.parser.parseNewRelease
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.mood.MoodSection
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
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
    override fun getLocalShelves(
        onRepeatTitle: String,
        buriedTitle: String,
    ): Flow<List<HomeItem>> =
        flow {
            // getMostPlayedSongs already filters to totalPlayTime > 1 and orders by it, which is
            // exactly the candidate set both shelves want; taking it once keeps this to a single
            // query rather than one per shelf.
            val played = localDataSource.getMostPlayedSongs().first()
            emit(
                listOfNotNull(
                    LocalShelfBuilder.onRepeat(played, onRepeatTitle),
                    LocalShelfBuilder.buriedFavourites(played, buriedTitle),
                ),
            )
        }.catch { throwable ->
            // Local shelves are an addition, never a dependency. If this fails the home feed should
            // look exactly as it did before they existed.
            Logger.e("HomeRepository", "local shelves failed: ${throwable.message}")
            emit(emptyList())
        }.flowOn(Dispatchers.IO)

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
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> =
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
                        continueParam to list.toList()
                    }
            home
                .onSuccess { emit(Resource.Success(it)) }
                .onFailure { error ->
                    Logger.e("Repository", "Home failed: ${error.message}")
                    emit(Resource.Error<Pair<String?, List<HomeItem>>>(error.message ?: "Home unavailable"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getExploreData(
        viewString: String,
        songString: String,
    ): Flow<Resource<List<HomeItem>>> =
        flow {
            youTube
                .customQuery(browseId = "FEmusic_explore")
                .onSuccess { result ->
                    val sections =
                        result.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                    val shelves =
                        parseMixedContent(sections, viewString, songString)
                            .filter { it.title.isNotBlank() && it.contents.isNotEmpty() }
                    emit(Resource.Success(shelves))
                }.onFailure { error ->
                    emit(Resource.Error<List<HomeItem>>(error.message.toString()))
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

    override fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>> =
        flow {
            youTube
                .newRelease()
                .onSuccess { result ->
                    emit(Resource.Success<List<HomeItem>>(parseNewRelease(result, newReleaseString, musicVideoString)))
                }.onFailure { error ->
                    emit(Resource.Error<List<HomeItem>>(error.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getChartData(countryCode: String): Flow<Resource<Chart>> =
        flow {
            runCatching {
                youTube
                    .customQuery("FEmusic_charts", country = countryCode)
                    .onSuccess { result ->
                        val data =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                        val chart = parseChart(data)
                        if (chart != null) {
                            emit(Resource.Success<Chart>(chart))
                        } else {
                            emit(Resource.Error<Chart>("Error"))
                        }
                    }.onFailure { error ->
                        emit(Resource.Error<Chart>(error.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getMoodAndMomentsData(): Flow<Resource<Mood>> =
        flow {
            // Serve the cached copy first so the grid paints instantly; the network result is
            // emitted right after and overwrites it. The category list changes about as often as
            // YouTube ships a new mood, so a stale frame costs nothing while a spinner does.
            val cached =
                dataStoreManager.moodAndGenresCache
                    .first()
                    ?.let { runCatching { moodJson.decodeFromString<Mood>(it) }.getOrNull() }
            if (cached != null) {
                emit(Resource.Success<Mood>(cached))
            }
            runCatching {
                youTube
                    .moodAndGenres()
                    .onSuccess { result ->
                        // Every section is kept, in the order YouTube sent it, under its own
                        // title. Indexing result[0]/result[1] used to break the moment a
                        // signed-in account got an extra "For you" section in front.
                        val sections =
                            result.map { section ->
                                MoodSection(
                                    title = section.title,
                                    items =
                                        section.items.map { item ->
                                            MoodItem(
                                                title = item.title,
                                                params = item.endpoint.params ?: "",
                                                stripeColor = item.stripeColor,
                                            )
                                        },
                                )
                            }
                        val mood = Mood(sections)
                        emit(Resource.Success<Mood>(mood))
                        dataStoreManager.setMoodAndGenresCache(moodJson.encodeToString(mood))
                    }.onFailure { e ->
                        // Already showing the cached copy — surfacing an error over it would
                        // replace working content with an error state.
                        if (cached == null) {
                            emit(Resource.Error<Mood>(e.message.toString()))
                        }
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getMoodCategoryArtwork(params: String): Flow<String?> =
        flow {
            val cache = readMoodArtworkCache()
            val hit = cache[params]
            if (hit != null && !hit.isStale()) {
                emit(hit.url)
                return@flow
            }
            val resolved =
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).getOrNull()
                    ?.let { parseMoodsMomentObject(it) }
                    ?.items
                    ?.firstNotNullOfOrNull { section ->
                        section.contents
                            .firstNotNullOfOrNull { it.thumbnails?.lastOrNull()?.url }
                    }
            emit(resolved)
            if (resolved != null) {
                dataStoreManager.setMoodArtworkCache(
                    moodJson.encodeToString(
                        cache + (params to MoodArtwork(resolved, Clock.System.now().toEpochMilliseconds())),
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun readMoodArtworkCache(): Map<String, MoodArtwork> =
        dataStoreManager.moodArtworkCache
            .first()
            ?.let { runCatching { moodJson.decodeFromString<Map<String, MoodArtwork>>(it) }.getOrNull() }
            .orEmpty()

    override fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>> =
        flow {
            runCatching {
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).onSuccess { result ->
                        val data = parseMoodsMomentObject(result)
                        if (data != null) {
                            emit(Resource.Success<MoodsMomentObject>(data))
                        } else {
                            emit(Resource.Error<MoodsMomentObject>("Error"))
                        }
                    }.onFailure { e ->
                        emit(Resource.Error<MoodsMomentObject>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getGenreData(params: String): Flow<Resource<GenreObject>> =
        flow {
            runCatching {
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).onSuccess { result ->
                        val data = parseGenreObject(result)
                        if (data != null) {
                            emit(Resource.Success<GenreObject>(data))
                        } else {
                            emit(Resource.Error<GenreObject>("Error"))
                        }
                    }.onFailure { e ->
                        emit(Resource.Error<GenreObject>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)
}