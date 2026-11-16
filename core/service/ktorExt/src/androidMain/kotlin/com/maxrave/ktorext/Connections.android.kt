package com.maxrave.ktorext

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

private val apiConnectionPool = ConnectionPool(5, 2, TimeUnit.MINUTES)

actual fun HttpClientEngineConfig.tuneApiConnections() {
    (this as? OkHttpConfig)?.config {
        connectionPool(apiConnectionPool)
        pingInterval(20, TimeUnit.SECONDS)
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)
        writeTimeout(15, TimeUnit.SECONDS)
    }
}

actual fun resetApiConnections() {
    apiConnectionPool.evictAll()
}
