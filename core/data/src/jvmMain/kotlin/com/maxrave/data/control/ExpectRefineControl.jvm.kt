package com.maxrave.data.control

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.refinecontrol.DEVICE_DESKTOP
import kotlinx.coroutines.CoroutineScope

actual fun createRefineControlRepository(
    dataStoreManager: DataStoreManager,
    localPlayer: MediaPlayerHandler,
    coroutineScope: CoroutineScope,
): RefineControlRepository =
    RefineControlRepositoryImpl(
        dataStore = dataStoreManager,
        local = localPlayer,
        scope = coroutineScope,
        deviceType = DEVICE_DESKTOP,
        defaultDeviceName =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
                ?: "Desktop",
    )
