package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.concurrent.Volatile

object ExtractSource {
    private const val MAX_ENTRIES = 32

    // Replaced wholesale rather than mutated, so a reader never observes a half-written map.
    @Volatile
    private var sources: Map<String, String> = emptyMap()

    fun record(
        videoId: String,
        source: String,
    ) {
        if (videoId.isEmpty()) return
        val updated = LinkedHashMap<String, String>(sources)
        updated.remove(videoId)
        updated[videoId] = source
        while (updated.size > MAX_ENTRIES) {
            updated.remove(updated.keys.firstOrNull() ?: break)
        }
        sources = updated
    }

    fun of(videoId: String): String? = sources[videoId]
}
