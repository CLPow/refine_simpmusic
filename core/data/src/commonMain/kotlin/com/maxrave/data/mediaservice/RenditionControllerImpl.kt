package com.maxrave.data.mediaservice

import com.maxrave.data.db.PodcastDao
import com.maxrave.domain.data.entities.RenditionPairEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.NowPlayingRenditions
import com.maxrave.domain.mediaservice.Rendition
import com.maxrave.domain.mediaservice.RenditionController
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Rendition"
private const val VIDEO_MODE_KEY = "video_mode"
private const val RECHECK_MISSING_MS = 14L * 24 * 3600 * 1000
private const val LOOKUP_TIMEOUT_MS = 10_000L
private const val BUILT_KEPT = 12

internal class RenditionControllerImpl(
    private val songRepository: SongRepository,
    private val podcastDao: PodcastDao,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) : RenditionController {
    private val _videoMode = MutableStateFlow(false)
    override val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    private val _current = MutableStateFlow<NowPlayingRenditions?>(null)
    override val current: StateFlow<NowPlayingRenditions?> = _current.asStateFlow()

    private val _reloadRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val reloadRequests: SharedFlow<String> = _reloadRequests

    private val foreground = MutableStateFlow(true)
    private val carConnected = MutableStateFlow(false)
    private val tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    private val pairs = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val built = MutableStateFlow<Map<String, Rendition>>(emptyMap())
    private val resolving = MutableStateFlow<Set<String>>(emptySet())
    private val nowPlayingId = MutableStateFlow<String?>(null)
    private val failedVideos = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            _videoMode.value = dataStoreManager.getString(VIDEO_MODE_KEY).first() == DataStoreManager.Values.TRUE
            refresh()
        }
    }

    private val videoActive: Boolean
        get() = _videoMode.value && foreground.value && !carConnected.value

    override fun setVideoMode(enabled: Boolean) {
        if (_videoMode.value == enabled) return
        _videoMode.value = enabled
        scope.launch {
            dataStoreManager.putString(VIDEO_MODE_KEY, if (enabled) DataStoreManager.Values.TRUE else DataStoreManager.Values.FALSE)
        }
        if (enabled) tracks.value[nowPlayingId.value]?.let { resolve(it) }
        refresh()
    }

    override fun setForeground(foreground: Boolean) {
        if (this.foreground.value == foreground) return
        this.foreground.value = foreground
        refresh()
    }

    override fun setCarConnected(connected: Boolean) {
        if (carConnected.value == connected) return
        carConnected.value = connected
        refresh()
    }

    override fun onNowPlaying(track: Track?) {
        nowPlayingId.value = track?.videoId
        if (track != null) {
            remember(track)
            resolve(track)
        }
        refresh()
    }

    override fun prefetch(track: Track) {
        remember(track)
        if (_videoMode.value) resolve(track)
    }

    override fun renditionFor(mediaId: String): Rendition {
        val video = videoIdOf(mediaId)
        return if (videoActive && video != null) {
            Rendition(audioId = video, videoId = video)
        } else {
            Rendition(audioId = songIdOf(mediaId) ?: mediaId, videoId = null)
        }
    }

    override fun markBuilt(
        mediaId: String,
        rendition: Rendition,
    ) {
        built.update { (it - mediaId + (mediaId to rendition)).entries.toList().takeLast(BUILT_KEPT).associate { e -> e.key to e.value } }
        refresh()
    }

    override fun reportVideoFailed(mediaId: String) {
        val video = (if (isVideoType(mediaId)) mediaId else pairs.value[mediaId]) ?: return
        Logger.w(TAG, "Video $video for $mediaId failed; playing audio")
        failedVideos.update { it + video }
        refresh()
    }

    private fun remember(track: Track) {
        tracks.update { if (it[track.videoId] == track) it else it + (track.videoId to track) }
    }

    private fun isVideoType(mediaId: String): Boolean {
        val type = tracks.value[mediaId]?.videoType
        return MusicVideoType.isKnown(type) && MusicVideoType.isVideoSong(type) && !MusicVideoType.isPodcast(type)
    }

    private fun videoIdOf(mediaId: String): String? =
        (if (isVideoType(mediaId)) mediaId else pairs.value[mediaId])?.takeUnless { it in failedVideos.value }

    private fun songIdOf(mediaId: String): String? = if (isVideoType(mediaId)) pairs.value[mediaId] else mediaId

    private fun resolve(track: Track) {
        val id = track.videoId
        if (pairs.value.containsKey(id) || id in resolving.value) return
        if (MusicVideoType.isPodcast(track.videoType)) {
            pairs.update { it + (id to null) }
            return
        }
        resolving.update { it + id }
        refresh()
        scope.launch(Dispatchers.IO) {
            try {
                val now = nowMs()
                val stored = runCatching { podcastDao.getRenditionPair(id) }.getOrNull()
                val counterpart =
                    if (stored != null && (stored.counterpartId != null || now - stored.checkedAt < RECHECK_MISSING_MS)) {
                        stored.counterpartId
                    } else {
                        val found =
                            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                                if (isVideoType(id)) songRepository.findSongVersion(track) else songRepository.findVideoVersion(track)
                            }
                        if (found != null) {
                            remember(found)
                            runCatching { podcastDao.upsertRenditionPair(RenditionPairEntity(found.videoId, id, now)) }
                            pairs.update { it + (found.videoId to id) }
                        }
                        runCatching { podcastDao.upsertRenditionPair(RenditionPairEntity(id, found?.videoId, now)) }
                        found?.videoId
                    }
                pairs.update { it + (id to counterpart) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Lookup failed for $id: ${e.message}")
                pairs.update { it + (id to null) }
            } finally {
                resolving.update { it - id }
                refresh()
            }
        }
    }

    private fun refresh() {
        val id = nowPlayingId.value
        if (id == null) {
            _current.value = null
            return
        }
        val builtNow = built.value[id]
        _current.value =
            NowPlayingRenditions(
                mediaId = id,
                songId = songIdOf(id),
                videoId = videoIdOf(id),
                resolving = id in resolving.value,
                playingVideo = builtNow?.isVideo == true,
            )
        if (builtNow != null && builtNow != renditionFor(id)) {
            Logger.d(TAG, "Reload $id: built $builtNow, wanted ${renditionFor(id)}")
            built.update { it - id }
            _reloadRequests.tryEmit(id)
        }
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
