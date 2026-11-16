package com.maxrave.media3.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class SongCacheEvictorTest {
    private fun song(
        key: String,
        bytes: Long = 4_000_000L,
        length: Long = 4_000_000L,
        touch: Long,
        plays: Int = 1,
    ) = CachedSong(key, bytes, length, touch, plays)

    @Test
    fun partialSongsGoFirstThenSinglePlaysThenReplayedByAge() {
        val order =
            evictionOrder(
                listOf(
                    song("favourite", touch = 10, plays = 5),
                    song("onceOld", touch = 20),
                    song("skipped", bytes = 300_000L, touch = 900),
                    song("onceNew", touch = 800),
                    song("unknownLength", length = -1L, touch = 950),
                    song("replayedNew", touch = 990, plays = 2),
                ),
            ).map { it.key }
        assertEquals(listOf("skipped", "unknownLength", "onceOld", "onceNew", "favourite", "replayedNew"), order)
    }
}
