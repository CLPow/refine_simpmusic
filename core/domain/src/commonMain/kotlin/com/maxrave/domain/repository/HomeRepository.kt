package com.maxrave.domain.repository

import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    /**
     * Shelves built from the user's own listening, to sit above YouTube's feed.
     *
     * Separate from [getHomeData] rather than folded into it: these read the local database and
     * always succeed, so a slow or failed network call must not delay or discard them, and a home
     * refresh should not have to wait on disk either.
     *
     * @param onRepeatTitle localized title for the most-played shelf
     * @param buriedTitle localized title for the well-played-but-neglected shelf
     */
    fun getLocalShelves(
        onRepeatTitle: String,
        buriedTitle: String,
    ): Flow<List<HomeItem>>

    /**
     * @return Pair of continueParams and HomeItem List
     */
    fun getHomeData(
        params: String? = null,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>>

    fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>>

    /** YouTube Music's Explore page (FEmusic_explore): its shelves, songs-only filtered. */
    fun getExploreData(
        viewString: String,
        songString: String,
    ): Flow<Resource<List<HomeItem>>>

    fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>>

    fun getChartData(countryCode: String = "KR"): Flow<Resource<Chart>>

    fun getMoodAndMomentsData(): Flow<Resource<Mood>>

    /**
     * Cover art for one browse category, or null when it cannot be resolved.
     *
     * The category list has no artwork field, so this costs a full category browse the first time
     * and is cached on disk afterwards. Call it lazily — one category at a time, as its tile
     * actually becomes visible — never for the whole list at once.
     */
    fun getMoodCategoryArtwork(params: String): Flow<String?>

    fun getGenreData(params: String): Flow<Resource<GenreObject>>

    fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>>
}