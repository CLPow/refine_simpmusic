package com.maxrave.data.control

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.NoRefineControlRepository
import com.maxrave.domain.repository.RefineControlRepository
import kotlinx.coroutines.CoroutineScope

actual fun createRefineControlRepository(
    dataStoreManager: DataStoreManager,
    localPlayer: MediaPlayerHandler,
    coroutineScope: CoroutineScope,
): RefineControlRepository = NoRefineControlRepository()
