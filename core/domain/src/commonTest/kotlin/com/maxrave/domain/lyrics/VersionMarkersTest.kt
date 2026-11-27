package com.maxrave.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionMarkersTest {
    @Test
    fun detectsInstrumentalAndLanguageVersions() {
        assertEquals(setOf(VersionMarkers.INSTRUMENTAL), VersionMarkers.of("Tip Toe (Instrumental)"))
        assertEquals(setOf(VersionMarkers.INSTRUMENTAL), VersionMarkers.of("Nonstop (Inst.)"))
        assertEquals(setOf(VersionMarkers.INSTRUMENTAL), VersionMarkers.of("光年之外 (伴奏)"))
        assertEquals(setOf(VersionMarkers.ENGLISH), VersionMarkers.of("Love Story (English Ver.)"))
        assertEquals(setOf(VersionMarkers.MANDARIN), VersionMarkers.of("Love Story (Chinese Version)"))
        assertEquals(setOf(VersionMarkers.MANDARIN), VersionMarkers.of("Love Story (中文版)"))
        assertEquals(setOf(VersionMarkers.JAPANESE), VersionMarkers.of("FIESTA (Japanese ver.)"))
    }

    @Test
    fun ordinaryTitlesCarryNoMarkers() {
        listOf("Mr. Feelgood", "Instinct", "Pink cheeks (Lyrics ver.)", "Liver", "BiiiG", "Dancing with my phone").forEach {
            assertEquals(emptySet(), VersionMarkers.of(it), it)
        }
    }

    @Test
    fun cjkTitlesNeedCjkLyrics() {
        assertFalse(VersionMarkers.lyricsFitTitle("光年之外", "Light years away from you"))
        assertTrue(VersionMarkers.lyricsFitTitle("光年之外", "缘分让我们相遇乱世以外"))
        assertTrue(VersionMarkers.lyricsFitTitle("First Love", "最後のキスは"))
    }
}
