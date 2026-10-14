package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

/**
 * @param code HTTP status YouTube returned, or 0 when the request never completed.
 * @param acceptedAt epoch millis of the attempt.
 */
data class PlaybackPingStatus(
    val name: String,
    val code: Int,
    val acceptedAt: Long,
) {
    /** YouTube answers these with 204, and any 2xx means it took the ping. */
    val accepted: Boolean get() = code in 200..299
}

/** The three tracking endpoints of one playback, and the client playback nonce they belong to. */
data class PlaybackTracking(
    val playbackUrl: String,
    val atrUrl: String,
    val watchtimeUrl: String,
    val cpn: String,
)

interface StreamRepository {
    /**
     * Milliseconds the account guard is holding back requests that carry the user's account, or 0
     * when it is idle.
     *
     * Lives here because this repository already holds the YouTube client the guard belongs to;
     * the app module has no business reaching into the scraper directly.
     */
    fun accountGuardCooldownMs(): Long

    /**
     * How the last playback-tracking ping to YouTube was answered, or null if none has been sent
     * this session.
     *
     * These pings are what make listening in this app count towards the account's history, and so
     * what feeds YouTube's recommendations. Enabling the setting and seeing no improvement is
     * indistinguishable from the pings being silently rejected, so the outcome is surfaced rather
     * than left to be inferred.
     */
    fun lastPlaybackPingStatus(): PlaybackPingStatus?

    suspend fun insertNewFormat(newFormat: NewFormatEntity)

    fun getNewFormat(videoId: String): Flow<NewFormatEntity?>

    suspend fun getFormatFlow(videoId: String): Flow<NewFormatEntity?>

    suspend fun updateFormat(videoId: String)

    /**
     * The playback-tracking URLs YouTube uses to count a listen towards the account: from the
     * stored format when it still carries valid ones, otherwise from a fresh authenticated player
     * request. Null when YouTube offered none.
     */
    suspend fun getPlaybackTracking(videoId: String): PlaybackTracking?

    fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean = false, // m3u8 or mp4 (both contain audio and video)
    ): Flow<String?>

    fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>>

    fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int>

    fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int>

    fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>>

    fun getFullMetadata(videoId: String): Flow<Resource<Track>>

    fun is403Url(url: String): Flow<Boolean>

    suspend fun invalidateFormat(videoId: String)

    /**
     * Which extractor and cipher decoder produced this video's stream URLs, for the info sheet.
     *
     * Null until the video has actually been extracted in this run — it is not persisted, because it
     * describes one extraction rather than the format row, which outlives it in the cache.
     */
    fun getExtractSource(videoId: String): String?
}