package com.maxrave.data.repository

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CounterpartMatchTest {
    private fun track(
        title: String,
        artists: List<Artist>,
        seconds: Int?,
    ) = Track(
        album = null,
        artists = artists,
        duration = null,
        durationSeconds = seconds,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = null,
        title = title,
        videoId = "id",
        videoType = null,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )

    private fun candidate(
        id: String,
        title: String,
        artist: Pair<String?, String>,
        seconds: Int?,
    ) = CounterpartCandidate(title, listOf(artist), seconds, id)

    @Test
    fun vevoVideoWithNativeArtistPrefixFindsTheSong() {
        val video = track("宇多田ヒカル - First Love", listOf(Artist("UCvevo", "HikaruUtadaVEVO")), 258)
        val target = assertNotNull(CounterpartTarget.of(video))
        val picked =
            target.pick(
                listOf(
                    candidate("cover", "First Love", "UCshania" to "Shania Yan", 257),
                    candidate("song", "First Love", "UCutada" to "Hikaru Utada", 257),
                ),
            )
        assertEquals("song", picked?.item)
        assertEquals("宇多田ヒカル - First Love Hikaru Utada", target.query)
    }

    @Test
    fun songFindsVideoWhoseTitleCarriesTheArtistInAnotherScript() {
        val song = track("First Love", listOf(Artist("UCutada", "Hikaru Utada")), 257)
        val picked =
            assertNotNull(CounterpartTarget.of(song)).pick(
                listOf(candidate("omv", "宇多田ヒカル - First Love", "UCutada" to "宇多田ヒカル", 258)),
            )
        assertEquals("omv", picked?.item)
    }

    @Test
    fun sameArtistAndLengthPairsTranslatedTitles() {
        val video = track("如果可以", listOf(Artist("UCwei", "韦礼安")), 275)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(
                    candidate("other", "A Little Longer", "UCwei" to "WeiBird", 247),
                    candidate("song", "Red Scarf (\"Till We Meet Again\" Movie Theme Song)", "UCwei" to "WeiBird", 274),
                ),
            )
        assertEquals("song", picked?.item)
    }

    @Test
    fun differentLengthAndTitleDoNotPair() {
        val video = track("如果可以", listOf(Artist("UCwei", "韦礼安")), 275)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(candidate("other", "A Little Longer", "UCwei" to "WeiBird", 247)),
            )
        assertNull(picked)
    }

    @Test
    fun otherArtistsNeverPairEvenWithSameTitleAndLength() {
        val video = track("First Love", listOf(Artist("UCutada", "Hikaru Utada")), 257)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(candidate("cover", "First Love", "UCshania" to "Shania Yan", 257)),
            )
        assertNull(picked)
    }

    @Test
    fun artistKeysIgnoreVevoTopicAndSpacing() {
        assertEquals("hikaruutada", "HikaruUtadaVEVO".artistKey())
        assertEquals("hikaruutada", "Hikaru Utada - Topic".artistKey())
        assertEquals("Hikaru Utada", "HikaruUtadaVEVO".artistQueryName())
        assertEquals("WeiBird", "WeiBird - Topic".artistQueryName())
    }

    @Test
    fun instrumentalOfTheSameLengthNeverPairs() {
        val video = track("HYBS - Tip Toe (Official Video)", listOf(Artist("UChybs", "HYBS")), 226)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(
                    candidate("inst", "Tip Toe (Instrumental)", "UChybs" to "HYBS", 225),
                    candidate("eng", "Tip Toe (English Ver.)", "UChybs" to "HYBS", 226),
                ),
            )
        assertNull(picked)
    }

    @Test
    fun lengthFallbackTakesTheClosestRecording() {
        val video = track("如果可以", listOf(Artist("UCwei", "韦礼安")), 275)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(
                    candidate("near", "A Little Longer", "UCwei" to "WeiBird", 273),
                    candidate("song", "Red Scarf", "UCwei" to "WeiBird", 275),
                ),
            )
        assertEquals("song", picked?.item)
    }

    @Test
    fun sameTitleTakesTheClosestLengthSoAnUnlabelledInstrumentalLoses() {
        val video = track("HYBS - Tip Toe (Official Video)", listOf(Artist("UChybs", "HYBS")), 226)
        val picked =
            assertNotNull(CounterpartTarget.of(video)).pick(
                listOf(
                    candidate("inst", "Tip Toe", "UChybs" to "HYBS", 198),
                    candidate("song", "Tip Toe", "UChybs" to "HYBS", 225),
                ),
            )
        assertEquals("song", picked?.item)
    }
}
