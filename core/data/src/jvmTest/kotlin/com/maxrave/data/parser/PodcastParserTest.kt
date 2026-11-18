package com.maxrave.data.parser

import com.maxrave.domain.data.model.podcast.parseEpisodeDurationSeconds
import com.maxrave.domain.data.model.podcast.toTrack
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PodcastParserTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun fixture(): BrowseResponse {
        val text =
            requireNotNull(javaClass.classLoader.getResourceAsStream("podcast_show.json")) { "missing fixture" }
                .bufferedReader()
                .use { it.readText() }
        return json.decodeFromString(BrowseResponse.serializer(), text)
    }

    @Test
    fun parsesShowHeaderAndEpisodes() {
        val show = assertNotNull(parsePodcastShow(fixture(), "MPSPPLPNW_gerXa4Pc8S2qoUQc5e8Ir97RLuVW"))
        assertEquals("Huberman Lab", show.title)
        assertEquals("Andrew Huberman", show.author?.name)
        assertEquals("UC2D2CMWXMOVWx7giW1n3LIg", show.author?.id)
        assertEquals("The Huberman Lab podcast.", show.description)
        assertEquals("https://i.ytimg.com/show.jpg", show.thumbnails.last().url)
        assertEquals("CONT_TOKEN", show.continuation)
        assertEquals(listOf("7mEK5wZHpto", "1rn_Clc-MM0"), show.episodes.map { it.videoId })

        val first = show.episodes.first()
        assertEquals("Mushrooms for the Mind & Immune System | Paul Stamets", first.title)
        assertEquals("1d ago", first.createdDay)
        assertEquals("2 hr 28 min", first.durationString)
        assertEquals("MPSPPLPNW_gerXa4Pc8S2qoUQc5e8Ir97RLuVW", first.podcastId)
        assertEquals("Huberman Lab", first.podcastTitle)
        assertEquals("38 min", show.episodes[1].durationString)
    }

    @Test
    fun episodeTrackIsMarkedAsPodcast() {
        val track = parsePodcastShow(fixture(), "MPSP1")!!.episodes.first().toTrack()
        assertEquals("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", track.videoType)
        assertEquals("Podcast", track.resultType)
        assertEquals(8880, track.durationSeconds)
    }

    @Test
    fun parsesEpisodeDurations() {
        assertEquals(8880, "2 hr 28 min".parseEpisodeDurationSeconds())
        assertEquals(2280, "38 min".parseEpisodeDurationSeconds())
        assertEquals(3723, "1:02:03".parseEpisodeDurationSeconds())
        assertEquals(null, "Played".parseEpisodeDurationSeconds())
    }
}
