package com.maxrave.domain.lyrics

import com.maxrave.domain.manager.DataStoreManager

/**
 * The order lyrics sources are consulted in, shared by every caller that needs lyrics.
 *
 * There are two consumers with very different shapes — the player UI, which wants translation,
 * romanization and contribution alongside the words, and the Android Auto overlay, which wants
 * nothing but timed lines. They agree on one thing, which source to ask next, and that agreement
 * has to be kept in one place: a track that finds lyrics on the phone and none in the car, purely
 * because two lists drifted apart, is a bug nobody would think to look for.
 */
object LyricsSourceOrder {
    /**
     * Key for Spotify within the chain.
     *
     * Spotify is not one of the four providers a user can pick in Settings — it has never been in
     * that list — so it has no [DataStoreManager] constant. It still needs a name here to be
     * ordered and marked as tried like the rest.
     */
    const val SPOTIFY = "spotify_lyrics_source"

    /**
     * SimpMusic first because it is the app's own database and carries timing contributed for these
     * exact videos; Spotify next because its coverage is the best of the remainder when a session
     * exists; LRCLIB then BetterLyrics as broad community sources; YouTube captions last, since a
     * caption track is often auto-generated or a translation rather than lyrics.
     */
    val DEFAULT: List<String> =
        listOf(
            DataStoreManager.SIMPMUSIC,
            SPOTIFY,
            DataStoreManager.LRCLIB,
            DataStoreManager.BETTER_LYRICS,
            DataStoreManager.YOUTUBE,
        )

    /**
     * Parse a user-ordered CSV (e.g. `lrclib,simpmusic,...`) into a usable chain.
     *
     * Unknown tokens are dropped; sources missing from the stored list are appended in [DEFAULT]
     * order so a partial preference still tries everything once.
     */
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

    /** Serialize [order] for DataStore; always one chain, never empty. */
    fun toCsv(order: List<String>): String = fromStored(order.joinToString(",")).joinToString(",")
}
