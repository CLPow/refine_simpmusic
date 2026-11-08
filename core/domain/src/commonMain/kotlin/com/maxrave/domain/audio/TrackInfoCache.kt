package com.maxrave.domain.audio

import kotlin.concurrent.Volatile

/** What YouTube told us about one track's stream: its loudness and which format it is. */
data class TrackInfo(
    val loudnessDb: Float?,
    val itag: Int? = null,
    val bitrateKbps: Int? = null,
)

/**
 * Per-track stream info for the engine, shared by the Android and desktop players.
 *
 * Copy-on-write: the player thread publishes (a handful of times per track), the audio threads read
 * on every buffer without locking. Eviction is by insertion order and a re-published track moves to
 * the newest end; tracks are published for the current *and* the next item on every transition, so
 * a track that is playing or about to play is always among the newest few and is never dropped.
 */
class TrackInfoCache(
    private val capacity: Int = 32,
) {
    @Volatile
    private var snapshot: Map<String, TrackInfo> = emptyMap()

    fun put(
        mediaId: String,
        info: TrackInfo,
    ) {
        val next = LinkedHashMap(snapshot)
        next.remove(mediaId)
        next[mediaId] = info
        while (next.size > capacity) next.remove(next.keys.first())
        snapshot = next
    }

    operator fun get(mediaId: String?): TrackInfo? = mediaId?.let { snapshot[it] }
}
