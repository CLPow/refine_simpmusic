package com.maxrave.domain.utils

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

object MusicVideoType {
    fun isVideoSong(musicVideoType: String?): Boolean = normalize(musicVideoType)?.let { it != MUSIC_VIDEO_TYPE_ATV } == true

    fun isAudio(musicVideoType: String?): Boolean = normalize(musicVideoType) == MUSIC_VIDEO_TYPE_ATV

    fun isKnown(musicVideoType: String?): Boolean = normalize(musicVideoType) != null

    fun normalize(musicVideoType: String?): String? = musicVideoType?.takeIf { it.startsWith(PREFIX) }

    fun isPodcast(musicVideoType: String?): Boolean = normalize(musicVideoType)?.contains("PODCAST") == true

    fun isPlayableSong(musicVideoType: String?): Boolean {
        val type = normalize(musicVideoType) ?: return true
        return type in AUDIO_TYPES
    }

    fun isOfficialVideo(musicVideoType: String?): Boolean = normalize(musicVideoType) == MUSIC_VIDEO_TYPE_OMV

    fun <T> preferSong(
        primary: T?,
        counterpart: T?,
        typeOf: (T) -> String?,
    ): T? =
        when {
            primary != null && isPlayableSong(typeOf(primary)) -> primary
            counterpart != null && isPlayableSong(typeOf(counterpart)) -> counterpart
            else -> null
        }

    private const val PREFIX = "MUSIC_VIDEO_TYPE_"
    private const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"

    val AUDIO_TYPES =
        setOf(
            MUSIC_VIDEO_TYPE_ATV,
            "MUSIC_VIDEO_TYPE_GATV",
            "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC",
            "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK",
        )
}
