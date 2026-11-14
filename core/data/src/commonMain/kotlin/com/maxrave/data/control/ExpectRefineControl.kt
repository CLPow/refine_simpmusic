package com.maxrave.data.control

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.RefineControlRepository
import kotlinx.coroutines.CoroutineScope

// [localPlayer] is this device's own player, never the routed one the UI talks to: commands from other devices must act here.
expect fun createRefineControlRepository(
    dataStoreManager: DataStoreManager,
    localPlayer: MediaPlayerHandler,
    coroutineScope: CoroutineScope,
): RefineControlRepository
