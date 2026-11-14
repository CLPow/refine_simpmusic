package com.maxrave.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every case here is a shape a lyrics search actually returns. The failure being guarded against
 * shows no error and no spinner - it shows somebody else's words, confidently - so the guard has to
 * be checked directly rather than noticed in use.
 */
class TrackMatchTest {
    private fun candidate(
        title: String,
        artist: String = "Adele",
        duration: Int? = 285,
        name: String = title,
        synced: Boolean = true,
        instrumental: Boolean = false,
    ) = TrackMatch.Candidate(
        title = title,
        artist = artist,
        durationSeconds = duration,
        value = name,
        isSynced = synced,
        isInstrumental = instrumental,
    )

    @Test
    fun `picks the studio recording over a live one listed first`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You",
                artist = "Adele",
                durationSeconds = 285,
                candidates =
                    listOf(
                        candidate("Someone Like You (Live at the Brit Awards)", name = "live"),
                        candidate("Someone Like You", name = "studio"),
                    ),
            )
        assertEquals("studio", result)
    }

    @Test
    fun `rejects a live version when it is the only result`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You",
                artist = "Adele",
                durationSeconds = 285,
                candidates = listOf(candidate("Someone Like You - Live", name = "live")),
            )
        assertNull(result)
    }

    @Test
    fun `rejects covers, karaoke and remixes`() {
        listOf(
            "Someone Like You (Cover)",
            "Someone Like You - Karaoke Version",
            "Someone Like You (Piano Version)",
            "Someone Like You (Acoustic)",
            "Someone Like You - Nightcore Remix",
        ).forEach { title ->
            assertNull(
                TrackMatch.best("Someone Like You", "Adele", 285, listOf(candidate(title))),
                "should have rejected: $title",
            )
        }
    }

    @Test
    fun `accepts the studio words when the live version is what is playing`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You (Live)",
                artist = "Adele",
                durationSeconds = 285,
                candidates = listOf(candidate("Someone Like You", name = "studio")),
            )
        assertEquals("studio", result)
    }

    @Test
    fun `prefers the live recording when the live recording is what is playing`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You (Live)",
                artist = "Adele",
                durationSeconds = 285,
                candidates =
                    listOf(
                        candidate("Someone Like You", name = "studio"),
                        candidate("Someone Like You (Live)", name = "live"),
                    ),
            )
        assertEquals("live", result)
    }

    @Test
    fun `rejects a different song by the same artist`() {
        assertNull(
            TrackMatch.best("Someone Like You", "Adele", 285, listOf(candidate("Rolling in the Deep"))),
        )
    }

    @Test
    fun `rejects the same title by a different artist`() {
        assertNull(
            TrackMatch.best(
                "Someone Like You",
                "Adele",
                285,
                listOf(candidate("Someone Like You", artist = "Van Morrison")),
            ),
        )
    }

    @Test
    fun `rejects a recording whose length disagrees`() {
        assertNull(
            TrackMatch.best("Someone Like You", "Adele", 285, listOf(candidate("Someone Like You", duration = 320))),
        )
        assertEquals(
            "close",
            TrackMatch.best(
                "Someone Like You",
                "Adele",
                285,
                listOf(candidate("Someone Like You", duration = 288, name = "close")),
            ),
        )
    }

    @Test
    fun `takes the closest length among several honest matches`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You",
                artist = "Adele",
                durationSeconds = 285,
                candidates =
                    listOf(
                        candidate("Someone Like You", duration = 289, name = "far"),
                        candidate("Someone Like You", duration = 286, name = "near"),
                    ),
            )
        assertEquals("near", result)
    }

    @Test
    fun `prefers timed lyrics over plain text`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You",
                artist = "Adele",
                durationSeconds = 285,
                candidates =
                    listOf(
                        candidate("Someone Like You", duration = 285, name = "plain", synced = false),
                        candidate("Someone Like You", duration = 288, name = "timed"),
                    ),
            )
        assertEquals("timed", result)
    }

    @Test
    fun `never returns an instrumental for a song with words`() {
        assertNull(
            TrackMatch.best(
                "Someone Like You",
                "Adele",
                285,
                listOf(candidate("Someone Like You", instrumental = true)),
            ),
        )
    }

    @Test
    fun `matches across inconsistent featured-artist credits`() {
        val result =
            TrackMatch.best(
                title = "Stay (feat. Justin Bieber)",
                artist = "The Kid LAROI, Justin Bieber",
                durationSeconds = 141,
                candidates =
                    listOf(
                        TrackMatch.Candidate(
                            title = "Stay",
                            artist = "The Kid LAROI",
                            durationSeconds = 141,
                            value = "match",
                        ),
                    ),
            )
        assertEquals("match", result)
    }

    @Test
    fun `ignores upload noise in the title`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You (Official Video)",
                artist = "Adele",
                durationSeconds = 285,
                candidates = listOf(candidate("Someone Like You", name = "match")),
            )
        assertEquals("match", result)
    }

    @Test
    fun `treats a remaster as the same recording`() {
        val result =
            TrackMatch.best(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                durationSeconds = 354,
                candidates =
                    listOf(
                        candidate(
                            "Bohemian Rhapsody - Remastered 2011",
                            artist = "Queen",
                            duration = 354,
                            name = "match",
                        ),
                    ),
            )
        assertEquals("match", result)
    }

    @Test
    fun `accepts a candidate with no stated length rather than nothing`() {
        val result =
            TrackMatch.best(
                title = "Someone Like You",
                artist = "Adele",
                durationSeconds = 285,
                candidates = listOf(candidate("Someone Like You", duration = null, name = "unknown")),
            )
        assertEquals("unknown", result)
    }

    @Test
    fun `does not match a shorter title that merely appears inside the real one`() {
        assertNull(
            TrackMatch.best(
                "Someone Like You",
                "Adele",
                285,
                listOf(candidate("Someone")),
            ),
        )
    }

    @Test
    fun `keeps ordinary words that happen to look like upload noise`() {
        assertNull(
            TrackMatch.best(
                "Video Games",
                "Lana Del Rey",
                282,
                listOf(candidate("Games", artist = "Lana Del Rey", duration = 282)),
            ),
        )
        assertEquals(
            "match",
            TrackMatch.best(
                "Video Games (Official Video)",
                "Lana Del Rey",
                282,
                listOf(candidate("Video Games", artist = "Lana Del Rey", duration = 282, name = "match")),
            ),
        )
    }

    @Test
    fun `returns nothing when there is nothing to choose from`() {
        assertNull(TrackMatch.best("Someone Like You", "Adele", 285, emptyList()))
    }
}
