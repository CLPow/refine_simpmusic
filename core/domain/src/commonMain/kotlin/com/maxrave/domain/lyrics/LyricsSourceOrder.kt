package com.maxrave.domain.lyrics

import com.maxrave.domain.manager.DataStoreManager

object LyricsSourceOrder {
    const val SPOTIFY = "spotify_lyrics_source"

    const val YOUTUBE_MUSIC = "youtube_music_lyrics_source"

    val DEFAULT: List<String> =
        listOf(
            YOUTUBE_MUSIC,
            DataStoreManager.BETTER_LYRICS,
            DataStoreManager.LRCLIB,
            DataStoreManager.SIMPMUSIC,
            DataStoreManager.YOUTUBE,
        )

    fun fromStored(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return DEFAULT
        val requested =
            csv
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val known = DEFAULT.toSet()
        val head = requested.filter { it in known }
        val tail = DEFAULT.filterNot { it in head.toSet() }
        return head + tail
    }

    // Serialize [order] for DataStore; always one chain, never empty.
    fun toCsv(order: List<String>): String = fromStored(order.joinToString(",")).joinToString(",")
}
