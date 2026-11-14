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
        139 -> 0 // AAC ~48 kbps
        249 -> 1 // Opus ~50 kbps
        ITAG.AUDIO_OPUS_LOW -> 2
        // AAC 128 kbps: the step below Opus Medium, so a response without 251 lands here and not
        // on the ~70 kbps stream.
        140 -> 3
        ITAG.AUDIO_OPUS_MEDIUM -> 4
        ITAG.AUDIO_AAC_HIGH -> 5
        ITAG.AUDIO_OPUS_HIGH -> 6
        else -> Int.MAX_VALUE
    }

private val EXPIRE_PARAM = Regex("[?&]expire=(\\d+)")

/** Used when neither the URL nor the response says how long a stream URL lives. */
private const val UNKNOWN_EXPIRY_SECONDS = 60 * 60L

/**
 * Seconds until a stream URL stops working.
 *
 * The URL carries its own deadline (`expire=<epoch seconds>`), which is the one googlevideo
 * enforces; the response's `expiresInSeconds` belongs to the WEB_REMIX answer, while the URL may
 * come from another extractor. With neither, a short life is assumed: storing 0 made the row
 * expired from the start, so every 5 MiB chunk resolved the stream again.
 */
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
                if (playback != null && atr != null && watchtime != null) {
                    // A fresh nonce per listen: reusing the stored one made a replay look like
                    // more of the previous play, not a play of its own.
                    return@withContext PlaybackTracking(playback, atr, watchtime, newCpn())
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
                // The stored stream URL is not replaced here, so an expired one must not inherit
                // the new expiry: it is dropped, and the next play resolves a fresh one. Extending
                // the expiry over a dead URL sent playback to it without the usual check.
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

    /** A client playback nonce, as YouTube's web player makes one per playback. */
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
                                // This row had expired, so its stream URL is dead; only the
                                // tracking URLs are renewed. See getPlaybackTracking.
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
                    // Only a request by quality setting says what that setting delivers; one pinned
                    // to a cached copy's format says nothing.
                    if (audioItag == null && audioFormat != null) noteDelivered(itag, audioFormat.itag)
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
            // Signed out, the ping would go out without an account and land in no history at all,
            // yet YouTube still answers 204, which made it look accepted.
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

    /**
     * Best tier each requested itag has actually been answered with since the app started. An
     * account that is never offered the requested format (a free account asking for 256 kbps)
     * settles one tier down, and that is then what "the wanted quality" means for it; without this
     * its cached copies would look too low for ever and be fetched again on every play.
     */
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
        // YouTube Music's separate "Audio quality on Wi-Fi" / "on mobile network".
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