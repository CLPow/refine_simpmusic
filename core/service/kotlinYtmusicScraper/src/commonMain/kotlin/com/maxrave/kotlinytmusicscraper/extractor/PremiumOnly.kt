package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.concurrent.Volatile

object PremiumOnly {
    private const val MAX_ENTRIES = 256

    @Volatile
    private var ids: Set<String> = emptySet()

    fun noteFailure(
        videoId: String,
        reason: String?,
    ) {
        if (videoId.isEmpty() || reason == null || !reason.contains("Premium", ignoreCase = true)) return
        if (videoId in ids) return
        ids = (LinkedHashSet(ids) + videoId).toList().takeLast(MAX_ENTRIES).toSet()
    }

    fun contains(videoId: String): Boolean = videoId in ids
}
