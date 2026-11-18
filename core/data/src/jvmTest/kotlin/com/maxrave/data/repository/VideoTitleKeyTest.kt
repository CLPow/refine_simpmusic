package com.maxrave.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoTitleKeyTest {
    private val artists = setOf("ed sheeran")

    @Test
    fun stripsArtistPrefixAndVideoLabel() {
        assertEquals("shape of you", "Ed Sheeran - Shape of You (Official Music Video)".videoTitleKey(artists))
    }

    @Test
    fun keepsTitlesWithoutArtistPrefix() {
        assertEquals("shape of you", "Shape of You [Official Video]".videoTitleKey(artists))
    }

    @Test
    fun ignoresDashWhenLeftSideIsNotTheArtist() {
        assertEquals("part one - part two", "Part One - Part Two".videoTitleKey(artists))
    }

    @Test
    fun matchesFeaturedArtistLists() {
        assertEquals("perfect", "Ed Sheeran & Beyonce - Perfect (Official Video)".videoTitleKey(artists))
    }
}
