package com.maxrave.data.mediaservice

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.PlaybackPingStatus
import com.maxrave.domain.repository.PlaybackTracking
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackReporter"

private const val KEY_PENDING_LISTENS = "pending_listens_v1"

class PlaybackReporter(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val pendingMutex = Mutex()

    fun start(
        videoId: String,
        playlistId: String?,
        positionMs: () -> Long,
        durationMs: () -> Long,
        isPlaying: () -> Boolean,
    ) {
        job?.cancel()
        job =
            scope.launch {
                if (!enabled()) return@launch
                try {
                    report(videoId, youTubePlaylistId(playlistId), positionMs, durationMs, isPlaying)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Reporting $videoId failed: ${e.message}")
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun enabled() = dataStoreManager.sendBackToGoogle.firstOrNull() == TRUE

    private suspend fun report(
        videoId: String,
        playlistId: String?,
        positionMs: () -> Long,
        durationMs: () -> Long,
        isPlaying: () -> Boolean,
    ) {
        val tracking: PlaybackTracking? = streamRepository.getPlaybackTracking(videoId)
        if (tracking == null) {
            Logger.w(TAG, "No tracking URLs for $videoId; keeping the listen to send later")
            measureOffline(videoId, playlistId, positionMs, durationMs, isPlaying)
            return
        }
        var init = streamRepository.initPlayback(tracking.playbackUrl, tracking.atrUrl, tracking.watchtimeUrl, tracking.cpn, playlistId).firstOrNull()
        if (init != null && init.first in PlaybackPingStatus.NO_ACCOUNT_CODES) {
            Logger.w(TAG, "Not reporting $videoId: no signed-in account (${init.first})")
            return
        }
        if (init == null || init.first !in 200..299) {
            delay(RETRY_DELAY_MS)
            init = streamRepository.initPlayback(tracking.playbackUrl, tracking.atrUrl, tracking.watchtimeUrl, tracking.cpn, playlistId).firstOrNull()
        }
        if (init == null || init.first !in 200..299) {
            Logger.w(TAG, "initPlayback for $videoId rejected (${init?.first})")
            if (init == null || init.first == 0) {
                // Never reached YouTube: keep it for later, like an offline listen.
                measureOffline(videoId, playlistId, positionMs, durationMs, isPlaying)
            }
            return
        }
        Logger.d(TAG, "Reporting $videoId")
        flushPending()
        val marks = arrayListOf(0f, FIRST_SEGMENT_END, init.second)
        var lastSeen = marks.last()
        try {
            while (scope.isActive) {
                delay(OBSERVE_MS)
                if (!isPlaying()) continue
                val position = positionMs() / 1000f
                if (position <= lastSeen) {
                    lastSeen = maxOf(position, 0f)
                    continue
                }
                lastSeen = position
                val duration = durationMs() / 1000f
                val finished = duration > 0 && position >= duration - END_MARGIN_S
                if (position - marks.last() < TICK_S && !finished) continue
                if (!enabled()) {
                    lastSeen = marks.last()
                    return
                }
                marks.add(position)
                streamRepository.updateWatchTime(tracking.watchtimeUrl, ArrayList(marks.takeLast(3)), tracking.cpn, playlistId).firstOrNull()
                if (finished) {
                    streamRepository.updateWatchTimeFull(tracking.watchtimeUrl, tracking.cpn, playlistId).firstOrNull()
                    Logger.d(TAG, "Finished $videoId")
                    return
                }
            }
        } finally {
            if (lastSeen > marks.last() + MIN_FINAL_SEGMENT_S) {
                withContext(NonCancellable) {
                    marks.add(lastSeen)
                    streamRepository.updateWatchTime(tracking.watchtimeUrl, ArrayList(marks.takeLast(3)), tracking.cpn, playlistId).firstOrNull()
                }
            }
        }
    }

    private suspend fun measureOffline(
        videoId: String,
        playlistId: String?,
        positionMs: () -> Long,
        durationMs: () -> Long,
        isPlaying: () -> Boolean,
    ) {
        var furthest = 0f
        var finished = false
        try {
            while (scope.isActive && !finished) {
                delay(OBSERVE_MS)
                if (!isPlaying()) continue
                val position = positionMs() / 1000f
                furthest = maxOf(furthest, position)
                val duration = durationMs() / 1000f
                finished = duration > 0 && position >= duration - END_MARGIN_S
            }
        } finally {
            if (furthest >= MIN_OFFLINE_LISTEN_S) {
                withContext(NonCancellable) {
                    addPending(PendingListen(videoId, playlistId, furthest, finished))
                }
            }
        }
    }

    private data class PendingListen(
        val videoId: String,
        val playlistId: String?,
        val seconds: Float,
        val finished: Boolean,
    ) {
        fun encode() = listOf(videoId, playlistId.orEmpty(), seconds.toString(), if (finished) "1" else "0").joinToString("|")

        companion object {
            fun decode(line: String): PendingListen? {
                val parts = line.split("|")
                if (parts.size != 4 || parts[0].isBlank()) return null
                return PendingListen(parts[0], parts[1].ifBlank { null }, parts[2].toFloatOrNull() ?: return null, parts[3] == "1")
            }
        }
    }

    private suspend fun readPending(): List<PendingListen> =
        dataStoreManager.getString(KEY_PENDING_LISTENS).firstOrNull().orEmpty()
            .lines()
            .mapNotNull { PendingListen.decode(it) }

    private suspend fun writePending(list: List<PendingListen>) {
        dataStoreManager.putString(KEY_PENDING_LISTENS, list.joinToString("\n") { it.encode() })
    }

    private suspend fun addPending(listen: PendingListen) =
        pendingMutex.withLock {
            writePending((readPending() + listen).takeLast(MAX_PENDING))
            Logger.d(TAG, "Kept offline listen of ${listen.videoId} (${listen.seconds.toInt()} s)")
        }

    private suspend fun flushPending() =
        pendingMutex.withLock {
            val pending = readPending()
            if (pending.isEmpty()) return@withLock
            val left = mutableListOf<PendingListen>()
            for (listen in pending) {
                val sent =
                    runCatching {
                        val tracking = streamRepository.getPlaybackTracking(listen.videoId) ?: return@runCatching false
                        val init =
                            streamRepository
                                .initPlayback(tracking.playbackUrl, tracking.atrUrl, tracking.watchtimeUrl, tracking.cpn, listen.playlistId)
                                .firstOrNull()
                        if (init == null || init.first !in 200..299) return@runCatching false
                        if (listen.seconds > init.second) {
                            val marks = arrayListOf(FIRST_SEGMENT_END, init.second, listen.seconds)
                            streamRepository.updateWatchTime(tracking.watchtimeUrl, marks, tracking.cpn, listen.playlistId).firstOrNull()
                        }
                        if (listen.finished) {
                            streamRepository.updateWatchTimeFull(tracking.watchtimeUrl, tracking.cpn, listen.playlistId).firstOrNull()
                        }
                        true
                    }.getOrDefault(false)
                if (!sent) left += listen
            }
            writePending(left)
            Logger.d(TAG, "Sent ${pending.size - left.size} offline listen(s), ${left.size} left")
        }

    private companion object {
        const val FIRST_SEGMENT_END = 5.54f
        const val OBSERVE_MS = 1_000L
        const val TICK_S = 20f
        const val RETRY_DELAY_MS = 3_000L
        const val END_MARGIN_S = 3f
        const val MIN_FINAL_SEGMENT_S = 2f
        const val MIN_OFFLINE_LISTEN_S = 30f
        const val MAX_PENDING = 100

        val YOUTUBE_LIST_PREFIXES = listOf("PL", "OLAK", "RD", "LM", "UU", "OL", "MPRE")

        fun youTubePlaylistId(id: String?): String? {
            val bare = id?.removePrefix("VL") ?: return null
            return bare.takeIf { b -> YOUTUBE_LIST_PREFIXES.any { b.startsWith(it) } }
        }
    }
}
