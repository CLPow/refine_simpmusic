package com.maxrave.media3.cache

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata

const val DEFAULT_SONG_CACHE_BYTES = 1024L * 1024 * 1024

private const val REPLAY_GAP_MS = 10 * 60_000L

class CachedSong(
    val key: String,
    val bytes: Long,
    val contentLength: Long,
    val lastTouch: Long,
    val plays: Int,
) {
    val isPartial: Boolean get() = contentLength <= 0L || bytes < contentLength
}

const val ACTIVE_WINDOW_MS = 30 * 60_000L

fun evictionOrder(
    songs: List<CachedSong>,
    now: Long,
): List<CachedSong> =
    songs.sortedWith(
        compareBy<CachedSong>(
            {
                when {
                    now - it.lastTouch < ACTIVE_WINDOW_MS -> 3
                    it.isPartial -> 0
                    it.plays <= 1 -> 1
                    else -> 2
                }
            },
            { it.lastTouch },
        ),
    )

@OptIn(UnstableApi::class)
class SongCacheEvictor(
    private val maxBytes: Long,
) : CacheEvictor {
    private class Entry(
        var bytes: Long = 0L,
        var spans: Int = 0,
        var lastTouch: Long = 0L,
        var plays: Int = 1,
    )

    private val entries = HashMap<String, Entry>()
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length, key)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        val entry = entries.getOrPut(span.key) { Entry() }
        entry.bytes += span.length
        entry.spans++
        entry.lastTouch = maxOf(entry.lastTouch, span.lastTouchTimestamp)
        currentSize += span.length
        evict(cache, 0L, span.key)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        val entry = entries[span.key] ?: return
        entry.bytes -= span.length
        entry.spans--
        currentSize -= span.length
        if (entry.spans <= 0) entries.remove(span.key)
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        val entry = entries[newSpan.key] ?: return
        if (newSpan.lastTouchTimestamp - entry.lastTouch > REPLAY_GAP_MS) entry.plays++
        entry.lastTouch = maxOf(entry.lastTouch, newSpan.lastTouchTimestamp)
    }

    private fun evict(
        cache: Cache,
        required: Long,
        protectedKey: String,
    ) {
        if (currentSize + required <= maxBytes) return
        val songs =
            entries
                .filter { it.key != protectedKey }
                .map { (key, entry) ->
                    CachedSong(
                        key = key,
                        bytes = entry.bytes,
                        contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key)),
                        lastTouch = entry.lastTouch,
                        plays = entry.plays,
                    )
                }
        for (song in evictionOrder(songs, System.currentTimeMillis())) {
            if (currentSize + required <= maxBytes) return
            for (span in cache.getCachedSpans(song.key)) {
                runCatching { cache.removeSpan(span) }
            }
        }
    }
}
