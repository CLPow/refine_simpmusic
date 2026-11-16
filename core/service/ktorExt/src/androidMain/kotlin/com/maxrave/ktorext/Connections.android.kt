package com.maxrave.ktorext

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.ConnectionPool
import java.util.concurrent.Executors
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

private val resetExecutor =
    Executors.newSingleThreadExecutor { task -> Thread(task, "api-connection-reset").apply { isDaemon = true } }

actual fun resetApiConnections() {
    resetExecutor.execute { runCatching { apiConnectionPool.evictAll() } }
}
