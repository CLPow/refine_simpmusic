package com.maxrave.data.mediaservice

import com.maxrave.data.platform.hasValidatedInternet
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NetworkResume"

private const val POLL_MS = 3_000L

private const val MAX_WAIT_MS = 30 * 60 * 1000L

private val ONLINE_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)

class NetworkResumeWatcher(
    private val scope: CoroutineScope,
    private val resume: () -> Unit,
) {
    private var job: Job? = null
    private var onlineRetries = 0

    fun arm() {
        job?.cancel()
        job =
            scope.launch {
                var waited = 0L
                var seenOffline = false
                while (waited < MAX_WAIT_MS) {
                    delay(POLL_MS)
                    waited += POLL_MS
                    val online = withContext(Dispatchers.IO) { hasValidatedInternet() }
                    if (!online) {
                        seenOffline = true
                        continue
                    }
                    if (seenOffline) {
                        Logger.w(TAG, "Connection is back after ${waited / 1000}s; resuming")
                        resume()
                        return@launch
                    }
                    val retryDelay = ONLINE_RETRY_DELAYS_MS.getOrNull(onlineRetries) ?: return@launch
                    if (waited >= retryDelay) {
                        onlineRetries++
                        Logger.w(TAG, "Still online after a network error; retry $onlineRetries")
                        resume()
                        return@launch
                    }
                }
            }
    }

    fun disarm() {
        job?.cancel()
        job = null
        onlineRetries = 0
    }
}
