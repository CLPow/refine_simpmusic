package com.maxrave.domain.utils

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

/**
 * YouTube says what a track actually is in exactly one place —
 * `watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType` — and
 * every question about audio-vs-video is answered from that string here, so the comparison is
 * spelled once instead of at each call site.
 *
 * Lives in `core/domain` because that is the only module both `core/service/kotlinYtmusicScraper`
 * and `core/data` already depend on; putting it in either of those would invert an existing edge.
 *
 * `null` means YouTube did not say, which is not the same as "audio". It is deliberately never
 * folded into an answer: a response that simply omitted the config must not be recorded as a
 * definite type, because that invented label then outlives the response that lacked it.
 */
object MusicVideoType {
    /**
     * True only when YouTube named a type and it is not the audio one — the same call Metrolist's
     * `isVideoSong` makes, kept identical because the constants here share its lineage.
     *
     * Note this resolves an unknown to false, so it answers "known to be a video", not "not known to
     * be audio". ytmusicapi resolves the same unknown the other way (`.get(video_type or "",
     * "video")`); callers that care which way an unknown falls must branch on [isKnown] first
     * rather than assuming this one.
     *
     * Every predicate here runs its argument through [normalize] first, because these are handed
     * `song.videoType` straight from the database — where older builds stored invented labels like
     * `"Song"`, `"video"` and even a view count. Comparing those raw would call all of them videos.
     */
    fun isVideoSong(musicVideoType: String?): Boolean = normalize(musicVideoType)?.let { it != MUSIC_VIDEO_TYPE_ATV } == true

    /** True only when YouTube explicitly said this is the audio version. */
    fun isAudio(musicVideoType: String?): Boolean = normalize(musicVideoType) == MUSIC_VIDEO_TYPE_ATV

    /** Whether YouTube reported a type at all. */
    fun isKnown(musicVideoType: String?): Boolean = normalize(musicVideoType) != null

    /**
     * Keeps [musicVideoType] only if it is one of YouTube's own `MUSIC_VIDEO_TYPE_*` values,
     * otherwise null. Anything reaching the app from outside the API — an import file, or a row
     * written by an older build that stored an invented label — goes through here so the column
     * holds either a real type or nothing.
     */
    fun normalize(musicVideoType: String?): String? = musicVideoType?.takeIf { it.startsWith(PREFIX) }

    /** Podcast episodes carry their own variants, e.g. `MUSIC_VIDEO_TYPE_PODCAST_EPISODE`. */
    fun isPodcast(musicVideoType: String?): Boolean = normalize(musicVideoType)?.contains("PODCAST") == true

    /**
     * The songs-only policy: may this item be shown or played at all?
     *
     * Refine plays music, not videos. YouTube Music's own client splits its types the same way:
     * [AUDIO_TYPES] are recordings; `OMV` (an official music video, which normally has an `ATV`
     * counterpart to swap to), `UGC`, `SHOULDER`, `LIVE_STREAM` and podcast episodes are not.
     *
     * An unknown type is allowed through. Many surfaces (library rows written before the type was
     * parsed, local playlists, imports) carry no type at all; dropping them would empty the
     * user's own library. Playback is audio-only regardless, so a mislabelled video still only
     * ever plays as sound.
     */
    fun isPlayableSong(musicVideoType: String?): Boolean {
        val type = normalize(musicVideoType) ?: return true
        return type in AUDIO_TYPES
    }

    /** `OMV`: the official video of a recording, whose counterpart is that recording's audio. */
    fun isOfficialVideo(musicVideoType: String?): Boolean = normalize(musicVideoType) == MUSIC_VIDEO_TYPE_OMV

    /**
     * Picks the rendition to keep from a queue row that may ship both (`playlistPanelVideoWrapperRenderer`
     * carries the primary and its `counterpart`). The audio one wins; a row with no playable
     * rendition yields null and is dropped by the caller.
     */
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

    /** Every type YouTube Music treats as an audio recording rather than a video. */
    val AUDIO_TYPES =
        setOf(
            MUSIC_VIDEO_TYPE_ATV,
            "MUSIC_VIDEO_TYPE_GATV",
            "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC",
            "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK",
        )
}
