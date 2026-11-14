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

/** How often the connection is checked while playback waits for it. */
private const val POLL_MS = 3_000L

/** Waiting for the network stops after this long; the user presses Play then. */
private const val MAX_WAIT_MS = 30 * 60 * 1000L

/** Pauses before each automatic retry when the device was online the whole time. */
private val ONLINE_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)

/**
 * Resumes playback by itself after a pause caused by the connection.
 *
 * A network error used to pause the player for good: the connection came back and nothing
 * happened until the user picked a track again. While armed, this checks for a validated internet
 * connection and calls [resume] once it is there.
 *
 * Two cases, told apart by whether the device was ever seen offline while waiting:
 * - It went offline (a tunnel, a Wi-Fi handover): resume as soon as it is back.
 * - It never did (a timeout on a slow link, a server that refused): retry a few times with growing
 *   pauses, then stop. Without the limit a failing stream would be retried every few seconds for
 *   as long as the app runs.
 *
 * [disarm] is called when the user acts or audio really plays; that also clears the retry count.
 */
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
