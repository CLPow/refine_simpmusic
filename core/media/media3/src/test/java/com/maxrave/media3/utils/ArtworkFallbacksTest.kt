package com.maxrave.media3.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkFallbacksTest {
    @Test
    fun ytimgFallsBackToSizesThatAlwaysExist() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            ),
            artworkFallbacks("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"),
        )
    }

    @Test
    fun hqdefaultIsNotRepeated() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            ),
            artworkFallbacks("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
        )
    }

    @Test
    fun googleusercontentSizeIsReplaced() {
        assertEquals(
            listOf(
                "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
                "https://lh3.googleusercontent.com/abc=w226-h226-l90-rj",
            ),
            artworkFallbacks("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"),
        )
    }

    @Test
    fun blankHasNoCandidates() {
        assertEquals(emptyList<String>(), artworkFallbacks(""))
        assertEquals(emptyList<String>(), artworkFallbacks("null"))
    }
}
