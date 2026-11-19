package com.maxrave.data.repository

import com.maxrave.common.ITAG
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toSponsorSkipSegments
import com.maxrave.data.mapping.toTrack
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.extension.isAfter
import com.maxrave.domain.extension.isBefore
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.data.platform.isNetworkMetered
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaybackPingStatus
import com.maxrave.domain.repository.PlaybackTracking
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.random.Random

private fun Int.tierRank(): Int =
    when (this) {
        139 -> 0
        249 -> 1
        ITAG.AUDIO_OPUS_LOW -> 2
        140 -> 3
        ITAG.AUDIO_OPUS_MEDIUM -> 4
        ITAG.AUDIO_AAC_HIGH -> 5
        ITAG.AUDIO_OPUS_HIGH -> 6
        else -> Int.MAX_VALUE
    }

private val EXPIRE_PARAM = Regex("[?&]expire=(\\d+)")

private const val UNKNOWN_EXPIRY_SECONDS = 60 * 60L

@OptIn(ExperimentalTime::class)
internal fun secondsUntilExpiry(
    url: String?,
    expiresInSeconds: Long?,
): Long {
    val fromUrl =
        url
            ?.let { EXPIRE_PARAM.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.let { it - Clock.System.now().epochSeconds }
    return (fromUrl ?: expiresInSeconds ?: UNKNOWN_EXPIRY_SECONDS).coerceAtLeast(0L)
}

internal class StreamRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : StreamRepository {
    @Volatile
    private var lastPingStatus: PlaybackPingStatus? = null

    override fun lastPlaybackPingStatus(): PlaybackPingStatus? = lastPingStatus

    @OptIn(ExperimentalTime::class)
    private fun recordPing(
        name: String,
        code: Int,
    ) {
        lastPingStatus = PlaybackPingStatus(name, code, Clock.System.now().toEpochMilliseconds())
        if (code in PlaybackPingStatus.NO_ACCOUNT_CODES) {
            Logger.w("PlaybackPing", "$name not sent: no signed-in account ($code)")
        } else if (code !in 200..299) {
            Logger.w("PlaybackPing", "$name rejected with $code -- listening will not reach the account")
        } else {
            Logger.d("PlaybackPing", "$name accepted ($code)")
        }
    }

    override suspend fun insertNewFormat(newFormat: NewFormatEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertNewFormat(newFormat)
        }

    override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flow { emit(localDataSource.getNewFormat(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun getFormatFlow(videoId: String) = localDataSource.getNewFormatAsFlow(videoId)

    override fun getExtractSource(videoId: String): String? = youTube.getExtractSource(videoId)

    override fun isPremiumOnly(videoId: String): Boolean = youTube.isPremiumOnly(videoId)

    override suspend fun getPlaybackTracking(videoId: String): PlaybackTracking? =
        withContext(Dispatchers.IO) {
            val stored = localDataSource.getNewFormat(videoId)
            if (stored != null && stored.expiredTime.isAfter(now())) {
                val playback = stored.playbackTrackingVideostatsPlaybackUrl
                val atr = stored.playbackTrackingAtrUrl
                val watchtime = stored.playbackTrackingVideostatsWatchtimeUrl
                if (playback != null && atr != null && watchtime != null) {
                    return@withContext PlaybackTracking(playback, atr, watchtime, newCpn())
                }
            }
            val triple = youTube.player(videoId).getOrNull() ?: return@withContext null
            val cpn = triple.first ?: return@withContext null
            val response = triple.second
            val tracking = response.playbackTracking
            val playback = tracking?.videostatsPlaybackUrl?.baseUrl?.toMusicHost()
            val atr = tracking?.atrUrl?.baseUrl?.toMusicHost()
            val watchtime = tracking?.videostatsWatchtimeUrl?.baseUrl?.toMusicHost()
            if (playback == null || atr == null || watchtime == null) return@withContext null
            stored?.let {
                val urlExpired = it.expiredTime.isBefore(now())
                localDataSource.updateNewFormat(
                    it.copy(
                        expiredTime = if (urlExpired) now().plusSeconds(secondsUntilExpiry(null, response.streamingData?.expiresInSeconds?.toLong())) else it.expiredTime,
                        audioUrl = if (urlExpired) null else it.audioUrl,
                        videoUrl = if (urlExpired) null else it.videoUrl,
                        playbackTrackingVideostatsPlaybackUrl = playback,
                        playbackTrackingAtrUrl = atr,
                        playbackTrackingVideostatsWatchtimeUrl = watchtime,
                        cpn = cpn,
                    ),
                )
            }
            PlaybackTracking(playback, atr, watchtime, cpn)
        }

    private fun newCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun String.toMusicHost() = replace("https://s.youtube.com", "https://music.youtube.com")

    override suspend fun updateFormat(videoId: String) {
        localDataSource.getNewFormat(videoId)?.let { oldFormat ->
            Logger.w("Stream", "oldFormatExpired: ${oldFormat.expiredTime}")
            Logger.w("Stream", "now: ${now()}")
            Logger.w("Stream", "isExpired: ${oldFormat.expiredTime.isBefore(now())}")
            if (oldFormat.expiredTime.isBefore(now())) {
                youTube
                    .player(videoId)
                    .onSuccess { triple ->
                        val response = triple.second
                        localDataSource.updateNewFormat(
                            oldFormat.copy(
                                expiredTime = now().plusSeconds(secondsUntilExpiry(null, response.streamingData?.expiresInSeconds?.toLong())),
                                audioUrl = null,
                                videoUrl = null,
                                playbackTrackingVideostatsPlaybackUrl =
                                    response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingAtrUrl =
                                    response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingVideostatsWatchtimeUrl =
                                    response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                cpn = triple.first,
                            ),
                        )
                        Logger.w("UpdateFormat", "Updated format for $videoId")
                    }.onFailure { throwable ->
                        Logger.e("UpdateFormat", "Error: ${throwable.message}")
                    }
            }
        }
    }

    override fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean,
        audioItag: Int?,
    ): Flow<String?> =
        flow {
            val itag =
                when {
                    audioItag != null -> audioItag
                    isDownloading -> QUALITY.itagOf(dataStoreManager.downloadQuality.first())
                    else -> wantedAudioItag(dataStoreManager)
                }
            val videoItag =
                if (!muxed) {
                    VIDEO_QUALITY.itags.getOrNull(
                        VIDEO_QUALITY.items.indexOf(
                            when {
                                isDownloading -> dataStoreManager.videoDownloadQuality.first()
                                isNetworkMetered() -> dataStoreManager.videoQualityMobile.first()
                                else -> dataStoreManager.videoQuality.first()
                            },
                        ),
                    )
                        ?: ITAG.VIDEO_360P
                } else {
                    ITAG.MUXED_360P
                }
            youTube
                .player(
                    videoId,
                    noLogIn = muxed,
                    preferredAudioLanguage = dataStoreManager.preferredAudioLanguage.first(),
                )
                .onSuccess { data ->
                    val response = data.second
                    if (data.third == MediaType.Song) {
                        Logger.w(
                            "Stream",
                            "response: is SONG",
                        )
                    } else {
                        Logger.w("Stream", "response: is VIDEO")
                    }
                    Logger.w(
                        "Stream",
                        response.streamingData
                            ?.formats
                            ?.map { it.itag }
                            .toString() + " " +
                            response.streamingData
                                ?.adaptiveFormats
                                ?.map { it.itag }
                                .toString(),
                    )
                    val formatList = mutableListOf<PlayerResponse.StreamingData.Format>()
                    formatList.addAll(
                        response.streamingData?.formats?.filter { it.url.isNullOrEmpty().not() } ?: emptyList(),
                    )
                    formatList.addAll(
                        response.streamingData?.adaptiveFormats?.filter { it.url.isNullOrEmpty().not() }
                            ?: emptyList(),
                    )
                    Logger.w("Stream", "Get stream for video $isVideo")
                    val videoFormat =
                        formatList.find { it.itag == videoItag }
                            ?: formatList.find { it.itag == ITAG.VIDEO_720P }
                            ?: formatList.find { it.itag == ITAG.VIDEO_360P }
                            ?: formatList.find { !it.isAudio && it.url.isNullOrEmpty().not() }
                    val audioTwinItag = ITAG.highQualityTwinOf(itag)
                    val playableAudio =
                        formatList
                            .filter { it.isAudio && it.url.isNullOrEmpty().not() }
                            .sortedByDescending { it.bitrate }
                    val audioFormat =
                        playableAudio.find { it.itag == itag }
                            ?: audioTwinItag?.let { twin -> playableAudio.find { it.itag == twin } }
                            ?: playableAudio.firstOrNull { it.itag.tierRank() <= itag.tierRank() }
                            ?: playableAudio.lastOrNull()
                    Logger.i(
                        "AudioFormats",
                        "offered for $videoId: " +
                            playableAudio.joinToString { "${it.itag}/${it.bitrate / 1000}k" } +
                            " | requested $itag | chose ${audioFormat?.itag}",
                    )
                    if (audioItag == null && audioFormat != null) noteDelivered(itag, audioFormat.itag)
                    var format =
                        if (isVideo) {
                            videoFormat
                        } else {
                            audioFormat
                        }
                    if (format == null) {
                        format =
                            if (isVideo) {
                                formatList.lastOrNull { it.url.isNullOrEmpty().not() }
                            } else {
                                formatList.lastOrNull {
                                    it.audioChannels != null && it.url.isNullOrEmpty().not()
                                }
                            }
                    }
                    if (muxed) {
                        format = formatList
                            .filter {
                                val url = it.url
                                url != null && youTube.isManifestUrl(url)
                            }.maxByOrNull { it.width ?: 0 } ?: formatList.find { it.itag == videoItag }
                    }
                    Logger.w("Stream", "Selected hls ${response.streamingData?.hlsManifestUrl}")
                    Logger.w("Stream", "format: $format")
                    Logger.d("Stream", "expireInSeconds ${response.streamingData?.expiresInSeconds}")
                    Logger.w("Stream", "expired at ${now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L)}")
                    insertNewFormat(
                        NewFormatEntity(
                            videoId = if (VIDEO_QUALITY.itags.contains(format?.itag)) "${MERGING_DATA_TYPE.VIDEO}$videoId" else videoId,
                            itag = format?.itag ?: itag,
                            mimeType =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(1) ?: format?.mimeType ?: "",
                            codecs =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(2) ?: format?.mimeType ?: "",
                            bitrate = format?.bitrate,
                            sampleRate = format?.audioSampleRate,
                            contentLength = format?.contentLength,
                            loudnessDb =
                                response.playerConfig
                                    ?.audioConfig
                                    ?.loudnessDb
                                    ?.toFloat(),
                            lengthSeconds = response.videoDetails?.lengthSeconds?.toInt(),
                            playbackTrackingVideostatsPlaybackUrl =
                                response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingAtrUrl =
                                response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingVideostatsWatchtimeUrl =
                                response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            cpn = data.first,
                            expiredTime = now().plusSeconds(secondsUntilExpiry(format?.url, response.streamingData?.expiresInSeconds?.toLong())),
                            audioUrl = if (muxed) response.streamingData?.hlsManifestUrl else format?.url,
                            videoUrl = if (muxed) response.streamingData?.hlsManifestUrl else videoFormat?.url,
                        ),
                    )
                    if (data.first != null) {
                        emit(
                            if (muxed) {
                                response.streamingData?.hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    if (youTube.isManifestUrl(url)) {
                                        url.plus("&cpn=${data.first}")
                                    } else {
                                        url.plus("&cpn=${data.first}&range=0-${format.contentLength ?: 10000000}")
                                    }
                                }
                            },
                        )
                    } else {
                        emit(
                            if (muxed) {
                                response.streamingData?.hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    if (youTube.isManifestUrl(url)) {
                                        url
                                    } else {
                                        url.plus("&range=0-${format.contentLength ?: 10000000}")
                                    }
                                }
                            },
                        )
                    }
                }.onFailure {
                    it.printStackTrace()
                    Logger.e("Stream", "Error: ${it.message}")
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>> =
        flow {
            if (youTube.cookie.isNullOrEmpty()) {
                recordPing("initPlayback", PlaybackPingStatus.CODE_NOT_SIGNED_IN)
                emit(Pair(PlaybackPingStatus.CODE_NOT_SIGNED_IN, 0f))
                return@flow
            }
            if (youTube.signInExpired.value) {
                recordPing("initPlayback", PlaybackPingStatus.CODE_SIGN_IN_EXPIRED)
                emit(Pair(PlaybackPingStatus.CODE_SIGN_IN_EXPIRED, 0f))
                return@flow
            }
            youTube
                .initPlayback(playback, atr, watchTime, cpn, playlistId)
                .onSuccess { response ->
                    recordPing("initPlayback", response.first)
                    emit(response)
                }.onFailure {
                    Logger.e("InitPlayback", "Error: ${it.message}")
                    recordPing("initPlayback", 0)
                    emit(Pair(0, 0f))
                }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            val code = youTube.updateWatchTimeFull(watchTime, cpn, playlistId).getOrDefault(0)
            recordPing("watchtimeFull", code)
            emit(code)
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            val code = youTube.updateWatchTime(playbackTrackingVideostatsWatchtimeUrl, watchTimeList, cpn, playlistId).getOrDefault(0)
            recordPing("watchtime", code)
            emit(code)
        }.flowOn(Dispatchers.IO)

    override fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>> =
        flow {
            youTube
                .getSkipSegments(videoId)
                .onSuccess { data ->
                    emit(Resource.Success(data.map { it.toSponsorSkipSegments() }))
                }.onFailure {
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getFullMetadata(videoId: String): Flow<Resource<Track>> =
        flow {
            Logger.w("getFullMetadata", "videoId: $videoId")
            youTube
                .getFullMetadata(videoId)
                .onSuccess {
                    emit(Resource.Success(it.toTrack()))
                }.onFailure {
                    Logger.e("getFullMetadata", "Error: ${it.message}")
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun is403Url(url: String) = flow { emit(youTube.is403Url(url)) }.flowOn(Dispatchers.IO)

    @kotlin.concurrent.Volatile
    private var bestTierDelivered: Map<Int, Int> = emptyMap()

    private fun noteDelivered(
        requestedItag: Int,
        chosenItag: Int,
    ) {
        val tier = chosenItag.tierRank()
        if (tier == Int.MAX_VALUE) return
        val known = bestTierDelivered[requestedItag]
        if (known == null || tier > known) bestTierDelivered = bestTierDelivered + (requestedItag to tier)
    }

    private fun deliveredTier(wantedItag: Int): Int = minOf(wantedItag.tierRank(), bestTierDelivered[wantedItag] ?: wantedItag.tierRank())

    override fun deliversQuality(
        storedItag: Int,
        wantedItag: Int,
    ): Boolean =
        storedItag == wantedItag ||
            storedItag == ITAG.highQualityTwinOf(wantedItag) ||
            storedItag.tierRank() in deliveredTier(wantedItag)..wantedItag.tierRank()

    override fun isLowerQuality(
        storedItag: Int,
        wantedItag: Int,
    ): Boolean =
        storedItag != wantedItag &&
            storedItag != ITAG.highQualityTwinOf(wantedItag) &&
            storedItag.tierRank() < deliveredTier(wantedItag)

    override suspend fun wantedAudioItag(dataStoreManager: DataStoreManager): Int =
        QUALITY.itagOf(if (isNetworkMetered()) dataStoreManager.qualityMobile.first() else dataStoreManager.quality.first())

    override suspend fun invalidateFormat(videoId: String) {
        withContext(Dispatchers.IO) {
            localDataSource.getNewFormat(videoId)?.let { format ->
                Logger.d("Stream", "Invalidating cached format for $videoId")
                localDataSource.updateNewFormat(
                    format.copy(expiredTime = now().plusSeconds(-1)),
                )
            }
        }
    }
}