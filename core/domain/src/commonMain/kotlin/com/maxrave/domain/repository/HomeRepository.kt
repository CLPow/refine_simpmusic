package com.maxrave.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.maxrave.domain.data.model.home.HomeChip
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    /** The filter chips YouTube sent with the last Home page, in its order; empty until one loads. */
    val homeChips: StateFlow<List<HomeChip>>

    /**
     * @return Triple of continueParams, HomeItem List and the URL of the page background image
     * YouTube sends with Home (null when it sends none)
     */
    fun getHomeData(
        params: String? = null,
        viewString: String,
        songString: String,
    ): Flow<Resource<Triple<String?, List<HomeItem>, String?>>>

    fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>>
}