package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val TIMED =
    """
    {"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{
      "lyricsData":{
        "timedLyricsData":[
          {"lyricLine":"Neon and beyond, that's what we on and beyond, yup","cueRange":{"startTimeMilliseconds":"120","endTimeMilliseconds":"3840","metadata":{"id":"0"}}},
          {"lyricLine":"♪","cueRange":{"startTimeMilliseconds":"3840","endTimeMilliseconds":"7560","metadata":{"id":"1"}}},
          {"lyricLine":"Neon and beyond","cueRange":{"startTimeMilliseconds":"7560","endTimeMilliseconds":"9000","metadata":{"id":"2"}}}
        ],
        "sourceMessage":"Source: Musixmatch",
        "trackingParams":"x"
      },
      "context":{"staticDeviceEnvDataContext":{"clientName":"ANDROID_MUSIC"}}
    }}}}}}}}
    """.trimIndent()

private val NO_TIMING = """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{}}]}}}"""

class YouTubeLyricsTest {
    @Test
    fun readsTimedLinesAndSource() {
        val lyrics = YouTubeLyrics.fromAndroidBrowse(Json.parseToJsonElement(TIMED))!!
        assertEquals("Source: Musixmatch", lyrics.source)
        assertEquals(3, lyrics.lines!!.size)
        assertEquals(YouTubeLyrics.Line("Neon and beyond, that's what we on and beyond, yup", 120, 3840), lyrics.lines!!.first())
        assertEquals(7560L, lyrics.lines!![2].startMs)
        assertNull(lyrics.plain)
    }

    @Test
    fun noTimedModelMeansNull() {
        assertNull(YouTubeLyrics.fromAndroidBrowse(Json.parseToJsonElement(NO_TIMING)))
    }
}
