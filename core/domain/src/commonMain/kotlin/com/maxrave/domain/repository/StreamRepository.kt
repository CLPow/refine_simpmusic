package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

data class PlaybackPingStatus(
    val name: String,
    val code: Int,
    val acceptedAt: Long,
) {
    val accepted: Boolean get() = code in 200..299

    val notSignedIn: Boolean get() = code == CODE_NOT_SIGNED_IN

    val signInExpired: Boolean get() = code == CODE_SIGN_IN_EXPIRED

    companion object {
        const val CODE_NOT_SIGNED_IN = -1
        const val CODE_SIGN_IN_EXPIRED = -2
        val NO_ACCOUNT_CODES = setOf(CODE_NOT_SIGNED_IN, CODE_SIGN_IN_EXPIRED)
    }
}

data class PlaybackTracking(
    val playbackUrl: String,
    val atrUrl: String,
    val watchtimeUrl: String,
    val cpn: String,
)

interface StreamRepository {
    fun lastPlaybackPingStatus(): PlaybackPingStatus?

    suspend fun insertNewFormat(newFormat: NewFormatEntity)

    fun getNewFormat(videoId: String): Flow<NewFormatEntity?>

    suspend fun getFormatFlow(videoId: String): Flow<NewFormatEntity?>

    suspend fun updateFormat(videoId: String)

    suspend fun getPlaybackTracking(videoId: String): PlaybackTracking?

    fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean = false,
        audioItag: Int? = null,
    ): Flow<String?>

    suspend fun wantedAudioItag(dataStoreManager: DataStoreManager): Int

    fun deliversQuality(
        storedItag: Int,
        wantedItag: Int,
    ): Boolean

    fun isLowerQuality(
        storedItag: Int,
        wantedItag: Int,
    ): Boolean

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

    fun getExtractSource(videoId: String): String?

    fun isPremiumOnly(videoId: String): Boolean
}