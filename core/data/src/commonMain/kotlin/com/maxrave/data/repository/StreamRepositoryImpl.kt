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

/**
 * Rough ordering of the audio itags by quality, so a fallback can look *downwards* from what was
 * asked for.
 *
 * Bitrate cannot answer this on its own: the requested itag is usually missing from the response --
 * that being the whole reason a fallback is running -- so there is no bitrate of its own to compare
 * against. An itag absent from this table ranks highest, which stops an unfamiliar high-quality
 * format from being mistaken for the cheap option.
 */
private fun Int.tierRank(): Int =
    when (this) {
        ITAG.AUDIO_OPUS_LOW -> 0
        ITAG.AUDIO_OPUS_MEDIUM -> 1
        ITAG.AUDIO_AAC_HIGH -> 2
        ITAG.AUDIO_OPUS_HIGH -> 3
        else -> Int.MAX_VALUE
    }

internal class StreamRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : StreamRepository {
    override fun accountGuardCooldownMs(): Long = youTube.accountGuard.cooldownRemainingMs

    /**
     * Last playback-tracking outcome. Written from the playback path, read from Settings; a plain
     * volatile field because it is one immutable value and the reader only ever wants the latest.
     */
    @Volatile
    private var lastPingStatus: PlaybackPingStatus? = null

    override fun lastPlaybackPingStatus(): PlaybackPingStatus? = lastPingStatus

    @OptIn(ExperimentalTime::class)
    private fun recordPing(
        name: String,
        code: Int,
    ) {
        lastPingStatus = PlaybackPingStatus(name, code, Clock.System.now().toEpochMilliseconds())
        if (code !in 200..299) {
            Logger.w("PlaybackPing", "$name rejected with $code -- listening will not reach the account")
        } else {
            Logger.d("PlaybackPing", "$name accepted ($code)")
        }
    }

    override suspend fun insertNewFormat(newFormat: NewFormatEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertNewFormat(newFormat)
        }

    // IO, never Main: the stream resolver blocks ExoPlayer's loader thread on this, and a read
    // dispatched to Main tied every track start to whatever the UI thread was doing.
    override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flow { emit(localDataSource.getNewFormat(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun getFormatFlow(videoId: String) = localDataSource.getNewFormatAsFlow(videoId)

    override fun getExtractSource(videoId: String): String? = youTube.getExtractSource(videoId)

    override suspend fun getPlaybackTracking(videoId: String): PlaybackTracking? =
        withContext(Dispatchers.IO) {
            val stored = localDataSource.getNewFormat(videoId)
            if (stored != null && stored.expiredTime.isAfter(now())) {
                val playback = stored.playbackTrackingVideostatsPlaybackUrl
                val atr = stored.playbackTrackingAtrUrl
                val watchtime = stored.playbackTrackingVideostatsWatchtimeUrl
                val cpn = stored.cpn
                if (playback != null && atr != null && watchtime != null && cpn != null) {
                    return@withContext PlaybackTracking(playback, atr, watchtime, cpn)
                }
            }
            // Downloaded and fully-cached tracks never ran a player request, so they carry no
            // tracking URLs and used to be silently skipped — the listen never reached the account.
            val triple = youTube.player(videoId).getOrNull() ?: return@withContext null
            val cpn = triple.first ?: return@withContext null
            val response = triple.second
            val tracking = response.playbackTracking
            val playback = tracking?.videostatsPlaybackUrl?.baseUrl?.toMusicHost()
            val atr = tracking?.atrUrl?.baseUrl?.toMusicHost()
            val watchtime = tracking?.videostatsWatchtimeUrl?.baseUrl?.toMusicHost()
            if (playback == null || atr == null || watchtime == null) return@withContext null
            stored?.let {
                localDataSource.updateNewFormat(
                    it.copy(
                        expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                        playbackTrackingVideostatsPlaybackUrl = playback,
                        playbackTrackingAtrUrl = atr,
                        playbackTrackingVideostatsWatchtimeUrl = watchtime,
                        cpn = cpn,
                    ),
                )
            }
            PlaybackTracking(playback, atr, watchtime, cpn)
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
                                expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
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
    ): Flow<String?> =
        flow {
            val itag =
                if (isDownloading) {
                    QUALITY.itagOf(dataStoreManager.downloadQuality.first())
                } else {
                    QUALITY.itagOf(dataStoreManager.quality.first())
                }
            val videoItag =
                if (!muxed) {
                    VIDEO_QUALITY.itags.getOrNull(
                        VIDEO_QUALITY.items.indexOf(
                            if (isDownloading) {
                                dataStoreManager.videoDownloadQuality.first()
                            } else {
                                dataStoreManager.videoQuality.first()
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
                    // Ordered best-first, so every fallback below lands on the best remaining
                    // stream rather than on whichever happened to be first. YouTube returns
                    // adaptiveFormats ascending, so `find { isAudio }` used to pick itag 249 --
                    // roughly 50 kbps -- any time the requested itag was missing.
                    val playableAudio =
                        formatList
                            .filter { it.isAudio && it.url.isNullOrEmpty().not() }
                            .sortedByDescending { it.bitrate }
                    val audioFormat =
                        // What the user asked for.
                        playableAudio.find { it.itag == itag }
                            // Its other 256 kbps rendition: a high-quality account is given one
                            // family or the other, so the twin is a peer, not a downgrade.
                            ?: audioTwinItag?.let { twin -> playableAudio.find { it.itag == twin } }
                            // Otherwise the closest tier at or below what was asked for. Taking the
                            // highest available would invert a deliberate choice: someone who picked
                            // "Low" to save data, or set it as their download quality, would be
                            // handed the largest stream on offer instead of the nearest smaller one.
                            ?: playableAudio.firstOrNull { it.itag.tierRank() <= itag.tierRank() }
                            // Nothing at or below exists, so the smallest thing above it is the
                            // least-worst answer -- still better than no audio at all.
                            ?: playableAudio.lastOrNull()
                    // What YouTube actually offered, not merely what was picked. Only the selected
                    // format is persisted, so without this there is no way to tell "the account is
                    // not entitled to 774" from "774 was offered and the old first-match fallback
                    // threw it away" -- a distinction worth settling with evidence.
                    Logger.i(
                        "AudioFormats",
                        "offered for $videoId: " +
                            playableAudio.joinToString { "${it.itag}/${it.bitrate / 1000}k" } +
                            " | requested $itag | chose ${audioFormat?.itag}",
                    )
                    var format =
                        if (isVideo) {
                            videoFormat
                        } else {
                            audioFormat
                        }
                    if (format == null) {
                        // Last resort. Restricted to muxed streams when audio was wanted: an
                        // adaptive video format carries no audio at all, so falling back to one
                        // for audio playback produces silence rather than a lower quality.
                        format =
                            if (isVideo) {
                                formatList.lastOrNull { it.url.isNullOrEmpty().not() }
                            } else {
                                // Any progressive stream will do, identified by carrying audio
                                // channels rather than by itag: pinning this to 18 alone meant a
                                // response offering some other muxed format (22, 43) and no adaptive
                                // audio yielded no playable format at all, so the track went silent
                                // where it used to play.
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
                    // No third-party lookups before playback: the stream URL goes straight to the player.
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
                            expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
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