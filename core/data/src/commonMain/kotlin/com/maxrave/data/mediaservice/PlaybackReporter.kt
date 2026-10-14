package com.maxrave.data.mediaservice

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
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
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackReporter"

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
 */
class PlaybackReporter(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

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
                if (dataStoreManager.sendBackToGoogle.firstOrNull() != TRUE) return@launch
                try {
                    report(videoId, playlistId, positionMs, durationMs, isPlaying)
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

    private suspend fun report(
        videoId: String,
        playlistId: String?,
        positionMs: () -> Long,
        durationMs: () -> Long,
        isPlaying: () -> Boolean,
    ) {
        val tracking: PlaybackTracking =
            streamRepository.getPlaybackTracking(videoId) ?: run {
                Logger.w(TAG, "No tracking URLs for $videoId; this listen cannot be reported")
                return
            }
        // Playback ping, first watch-time segment and the attestation ping, in YouTube's order.
        // Retried once: the very first request of a session sometimes loses the race with the
        // account guard's cooldown.
        var init = streamRepository.initPlayback(tracking.playbackUrl, tracking.atrUrl, tracking.watchtimeUrl, tracking.cpn, playlistId).firstOrNull()
        if (init == null || init.first !in 200..299) {
            delay(RETRY_DELAY_MS)
            init = streamRepository.initPlayback(tracking.playbackUrl, tracking.atrUrl, tracking.watchtimeUrl, tracking.cpn, playlistId).firstOrNull()
        }
        if (init == null || init.first !in 200..299) {
            Logger.w(TAG, "initPlayback for $videoId rejected (${init?.first})")
            return
        }
        Logger.d(TAG, "Reporting $videoId")
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

    private companion object {
        const val FIRST_SEGMENT_END = 5.54f
        const val OBSERVE_MS = 1_000L
        const val TICK_S = 20f
        const val RETRY_DELAY_MS = 3_000L
        const val END_MARGIN_S = 3f
        const val MIN_FINAL_SEGMENT_S = 2f
    }
}
