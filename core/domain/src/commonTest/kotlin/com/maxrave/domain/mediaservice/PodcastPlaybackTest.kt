package com.maxrave.domain.mediaservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodcastPlaybackTest {
    @Test
    fun resumesOnlyBetweenFiveSecondsAndNinetyFivePercent() {
        assertFalse(PodcastPlayback.shouldResume(positionMs = 3_000, durationMs = 600_000))
        assertTrue(PodcastPlayback.shouldResume(positionMs = 120_000, durationMs = 600_000))
        assertFalse(PodcastPlayback.shouldResume(positionMs = 580_000, durationMs = 600_000))
        assertTrue(PodcastPlayback.shouldResume(positionMs = 120_000, durationMs = 0))
    }

    @Test
    fun playedAtNinetyFivePercent() {
        assertTrue(PodcastPlayback.isPlayed(positionMs = 570_000, durationMs = 600_000))
        assertFalse(PodcastPlayback.isPlayed(positionMs = 569_000, durationMs = 600_000))
        assertFalse(PodcastPlayback.isPlayed(positionMs = 10_000, durationMs = 0))
    }

    @Test
    fun unknownSpeedFallsBackToNormal() {
        assertEquals(1.5f, PodcastPlayback.speedOf("1.5"))
        assertEquals(1f, PodcastPlayback.speedOf("3.0"))
        assertEquals(1f, PodcastPlayback.speedOf(null))
    }
}
