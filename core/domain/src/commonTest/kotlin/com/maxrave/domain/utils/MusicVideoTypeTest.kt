package com.maxrave.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicVideoTypeTest {
    @Test
    fun audioTypesArePlayable() {
        MusicVideoType.AUDIO_TYPES.forEach { assertTrue(MusicVideoType.isPlayableSong(it), it) }
    }

    @Test
    fun videoTypesAreNotPlayable() {
        listOf(
            "MUSIC_VIDEO_TYPE_OMV",
            "MUSIC_VIDEO_TYPE_UGC",
            "MUSIC_VIDEO_TYPE_SHOULDER",
            "MUSIC_VIDEO_TYPE_LIVE_STREAM",
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
        ).forEach { assertFalse(MusicVideoType.isPlayableSong(it), it) }
    }

    @Test
    fun unknownAndLegacyLabelsAreKept() {
        // Older builds wrote invented labels into song.videoType; those normalize to unknown.
        listOf(null, "", "Song", "video", "432K views").forEach {
            assertTrue(MusicVideoType.isPlayableSong(it), it.toString())
        }
    }

    @Test
    fun preferSongPicksTheAudioRendition() {
        val omv = "v1" to "MUSIC_VIDEO_TYPE_OMV"
        val atv = "a1" to "MUSIC_VIDEO_TYPE_ATV"
        val ugc = "u1" to "MUSIC_VIDEO_TYPE_UGC"
        val type: (Pair<String, String?>) -> String? = { it.second }

        assertEquals(atv, MusicVideoType.preferSong(omv, atv, type))
        assertEquals(atv, MusicVideoType.preferSong(atv, omv, type))
        assertEquals(atv, MusicVideoType.preferSong(null, atv, type))
        assertNull(MusicVideoType.preferSong(ugc, null, type))
        assertNull(MusicVideoType.preferSong(omv, ugc, type))
    }
}
