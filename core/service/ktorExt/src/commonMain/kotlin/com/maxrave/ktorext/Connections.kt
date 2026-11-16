package com.maxrave.ktorext

import io.ktor.client.engine.HttpClientEngineConfig

expect fun HttpClientEngineConfig.tuneApiConnections()

expect fun resetApiConnections()
