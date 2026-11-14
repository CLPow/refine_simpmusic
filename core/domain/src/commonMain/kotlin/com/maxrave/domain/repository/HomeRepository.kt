package com.maxrave.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.maxrave.domain.data.model.home.HomeChip
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    val homeChips: StateFlow<List<HomeChip>>

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