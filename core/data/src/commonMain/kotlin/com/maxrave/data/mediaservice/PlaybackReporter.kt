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

/** DataStore key for listens made while offline, sent once YouTube can be reached again. */
private const val KEY_PENDING_LISTENS = "pending_listens_v1"

/**
 * Reports listening back to YouTube so it counts towards the account's history — which is what
 * YouTube's recommendations learn from. One instance per player handler, shared by Android and
 * Desktop.
 *
 * Replaces the old per-handler pipeline, which listened silently into nothing in three ways:
 * - it read tracking URLs only from the cached stream format, and downloaded or fully-cached
 *   tracks never have one, so they were skipped without a trace;
 * - watch-time pings fired only when a 500 ms progress tick happened to land inside a 1.2 s
 *   window, so a missed tick stopped reporting for the rest of the song;
 * - the job handle was assigned inside a nested launch, so a quick skip could leave the previous
 *   track's reporter running against the new one.
 *
 * The watch-time protocol is YouTube's own: segment marks in seconds, sent as `st`/`et` pairs
 * (see `YouTube.updateWatchTime`), with an initial 0 → 5.54 s segment from [StreamRepository.initPlayback].
 * Every listen gets its own playback nonce (see [StreamRepository.getPlaybackTracking]), so a
 * replay counts as a play of its own rather than more of the last one.
 *
 * A listen made offline (a download, no network) is measured anyway and kept in DataStore, then
 * sent the next time a report gets through.
 */
class PlaybackReporter(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val pendingMutex = Mutex()

    /**
     * Starts reporting [videoId]. Any previous track's reporting stops first. [positionMs] and
     * [durationMs] are read on [scope]'s dispatcher (Main on Android, where the player lives);
     * [isPlaying] gates the ticker so a paused song is not reported as listened to.
     */
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
            // Usually offline (a download). Measure the listen and send it later.
            Logger.w(TAG, "No tracking URLs for $videoId; keeping the listen to send later")
            measureOffline(videoId, playlistId, positionMs, durationMs, isPlaying)
            return
        }
        // Playback ping, first watch-time segment and the attestation ping, in YouTube's order.
        // Retried once: the very first request of a session sometimes loses the race with the
        // account guard's cooldown. Not when there is no account to report to.
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
        // Observed every second, reported every TICK. By the time this job is cancelled for a
        // skip the player already holds the next track, so the final segment must use the last
        // position seen while this one was still playing, not a fresh read.
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
                // "Pause watch history" switched on mid-song stops reporting straight away.
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
            // A skip mid-song still reports how far it got. The job is cancelled at that point,
            // so the request runs non-cancellably, once.
            if (lastSeen > marks.last() + MIN_FINAL_SEGMENT_S) {
                withContext(NonCancellable) {
                    marks.add(lastSeen)
                    streamRepository.updateWatchTime(tracking.watchtimeUrl, ArrayList(marks.takeLast(3)), tracking.cpn, playlistId).firstOrNull()
                }
            }
        }
    }

    /** Follows an unreportable listen to its end, then stores how far it got. */
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

    /** Sends the listens kept while offline. Runs on the reporting job, after a ping got through. */
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

        /**
         * Only YouTube's own list ids go out as `list=`. Queues built in the app (local playlists,
         * Library lists) carry ids YouTube has never seen, which made the ping refer to nothing.
         */
        val YOUTUBE_LIST_PREFIXES = listOf("PL", "OLAK", "RD", "LM", "UU", "OL", "MPRE")

        fun youTubePlaylistId(id: String?): String? {
            val bare = id?.removePrefix("VL") ?: return null
            return bare.takeIf { b -> YOUTUBE_LIST_PREFIXES.any { b.startsWith(it) } }
        }
    }
}
