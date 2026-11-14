package com.maxrave.data.di

import com.maxrave.common.Config
import com.maxrave.data.control.createRefineControlRepository
import com.maxrave.data.mediaservice.RoutingMediaPlayerHandler
import com.maxrave.data.mediaservice.createMediaServiceHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.RefineControlRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module

val mediaHandlerModule =
    module {
        single<MediaPlayerHandler>(named(Config.LOCAL_PLAYER), createdAtStart = true) {
            createMediaServiceHandler(
                dataStoreManager = get(),
                songRepository = get(),
                streamRepository = get(),
                localPlaylistRepository = get(),
                coroutineScope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            )
        }
        single<RefineControlRepository>(createdAtStart = true) {
            createRefineControlRepository(
                dataStoreManager = get(),
                localPlayer = get(named(Config.LOCAL_PLAYER)),
                coroutineScope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            )
        }
        single<MediaPlayerHandler>(createdAtStart = true) {
            RoutingMediaPlayerHandler(
                local = get(named(Config.LOCAL_PLAYER)),
                control = get(),
                scope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            )
        }
    }