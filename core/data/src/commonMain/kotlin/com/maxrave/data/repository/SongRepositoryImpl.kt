package com.maxrave.data.repository

import com.maxrave.domain.data.model.queue.RadioChip
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toYouTubeComment
import com.maxrave.data.mapping.toYouTubeCommentsPage
import com.maxrave.data.mapping.toSongItemForDownload
import com.maxrave.data.mapping.toWatchEndpoint
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.data.mapping.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.VideoItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.response.LikeStatus
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.pages.PlaylistPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.koin.mp.KoinPlatform.getKoin

private const val TAG = "SongRepositoryImpl"
private const val COMMENTS_TAG = "Comments"

internal class SongRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : SongRepository {
    override fun getAllSongs(limit: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getAllSongs(limit))
        }.flowOn(Dispatchers.IO)

    override suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    ) = withContext(Dispatchers.IO) { localDataSource.setInLibrary(videoId, inLibrary) }

    override fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>> =
        flow {
            val songs = localDataSource.getSongByListVideoIdFull(listVideoId)
            val byId = songs.associateBy { it.videoId }
            emit(listVideoId.mapNotNull { byId[it] })
        }.flowOn(Dispatchers.IO)

    override fun getDownloadedSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getDownloadingSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getPreparingSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getPreparingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getLikedSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun clearHistoryAndOrphanedSongs(): Int =
        withContext(Dispatchers.IO) {
            persistLiveQueueBeforeSweep()
            val artists = localDataSource.deleteUnfollowedArtists()
            val albums = localDataSource.deleteUnreferencedAlbums()
            val playlists = localDataSource.deleteUnreferencedPlaylists()
            val orphans = localDataSource.getOrphanedSongIds()
            val removed = localDataSource.deleteSongsAndRelatedData(orphans)
            val satellites = localDataSource.deleteStaleSongSatellites()
            Logger.d(
                TAG,
                "Clear history: removed $removed of ${orphans.size} candidate songs, " +
                    "$artists artists, $albums albums, $playlists playlists, $satellites stale rows",
            )
            runCatching {
                localDataSource.checkpoint()
                localDataSource.vacuum()
            }.onFailure { Logger.e(TAG, "VACUUM after clearing history failed: ${it.message}") }
            removed
        }

    // Deliberately ignores that setting: this is not "remember my queue for next launch", it is "do not delete what is on screen".
    private suspend fun persistLiveQueueBeforeSweep() {
        val liveQueue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        if (liveQueue.isEmpty()) return
        Logger.d(TAG, "Clear history: pinning ${liveQueue.size} queued tracks before the sweep")
        localDataSource.recoverQueue(
            QueueEntity(
                listTrack = liveQueue,
                playlistType = mediaPlayerHandler.queueData.value?.data?.playlistType?.name,
            ),
        )
    }

    override fun getCanvasSong(max: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getCanvasSong(max))
        }.flowOn(Dispatchers.IO)

    override fun getSongById(id: String): Flow<SongEntity?> =
        flow {
            emit(localDataSource.getSong(id))
        }.flowOn(Dispatchers.IO)

    override fun getSongAsFlow(id: String) = localDataSource.getSongAsFlow(id)

    override fun insertSong(songEntity: SongEntity): Flow<Long> = flow<Long> { emit(localDataSource.insertSong(songEntity)) }.flowOn(Dispatchers.IO)

    override fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateThumbnailsSongEntity(thumbnail, videoId)) }.flowOn(Dispatchers.IO)

    override fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateVideoTypeSongEntity(videoType, videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun updateListenCount(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.updateListenCount(videoId)
        }

    override suspend fun resetTotalPlayTime(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.resetTotalPlayTime(videoId)
        }

    private val downloadHandler: DownloadHandler by lazy { getKoin().get() }

    private val mediaPlayerHandler: MediaPlayerHandler by lazy {
        getKoin().get(org.koin.core.qualifier.named(com.maxrave.common.Config.LOCAL_PLAYER))
    }

    override suspend fun updateLikeStatus(
        videoId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateLiked(likeStatus, videoId)
        if (likeStatus == 1 && dataStoreManager.autoDownloadLikedSongs.first() == TRUE) {
            val song = localDataSource.getSong(videoId)
            if (song != null && song.downloadState != DownloadState.STATE_DOWNLOADED) {
                Logger.d(TAG, "Auto-downloading liked song: $videoId")
                downloadHandler.downloadTrack(videoId, song.title, song.thumbnails.orEmpty())
            }
        }
    }

    override fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateSongInLibrary(inLibrary, videoId)) }

    override suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDurationSeconds(
            durationSeconds,
            videoId,
        )
    }

    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = localDataSource.getMostPlayedSongs()

    override suspend fun updateDownloadState(
        videoId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDownloadState(
            downloadState,
            videoId,
        )
    }

    override suspend fun getRecentSong(
        limit: Int,
        offset: Int,
    ) = localDataSource.getRecentSongs(limit, offset)

    override suspend fun insertSongInfo(songInfo: SongInfoEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertSongInfo(songInfo)
        }

    override suspend fun recoverQueue(
        temp: List<Track>,
        playlistType: String?,
    ) {
        val queueEntity =
            QueueEntity(
                listTrack = temp,
                playlistType = playlistType,
            )
        withContext(Dispatchers.IO) { localDataSource.recoverQueue(queueEntity) }
    }

    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> =
        flow {
            emit(localDataSource.getQueue())
        }.flowOn(Dispatchers.IO)

    private fun List<SongItem>.songsOnly(): List<SongItem> = filter { MusicVideoType.isPlayableSong(it.musicVideoType) }

    private fun List<SongItem>.songsOrOfficialVideos(): List<SongItem> =
        songsOnly().ifEmpty { filter { MusicVideoType.isOfficialVideo(it.musicVideoType) } }

    private val songVersions = mutableMapOf<String, Track?>()
    private val songVersionsLock = Mutex()

    override suspend fun getRadioChips(
        videoId: String,
        playlistId: String,
    ): List<RadioChip> =
        withContext(Dispatchers.IO) {
            youTube
                .radioChips(videoId, playlistId)
                .onFailure { Logger.w(TAG, "getRadioChips $playlistId: ${it.message}") }
                .getOrNull()
                .orEmpty()
                .map { RadioChip(it.title, it.playlistId, it.params, it.selected) }
        }

    override suspend fun findSongVersion(track: Track): Track? =
        withContext(Dispatchers.IO) {
            if (!MusicVideoType.isOfficialVideo(track.videoType)) return@withContext null
            songVersionsLock.withLock {
                if (songVersions.containsKey(track.videoId)) return@withContext songVersions[track.videoId]
            }
            val title = track.title.songTitleKey()
            val artistIds = track.artists.orEmpty().mapNotNull { it.id?.takeIf(String::isNotBlank) }.toSet()
            val artistNames = track.artists.orEmpty().map { it.name.trim().lowercase() }.filter(String::isNotEmpty).toSet()
            if (title.isEmpty() || (artistIds.isEmpty() && artistNames.isEmpty())) return@withContext null
            val query = (listOf(track.title.songTitleQuery()) + track.artists.orEmpty().map { it.name }).joinToString(" ")
            val result = youTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull() ?: return@withContext null
            val song =
                result.items
                    .filterIsInstance<SongItem>()
                    .take(5)
                    .firstOrNull { item ->
                        (item.musicVideoType == null || MusicVideoType.isAudio(item.musicVideoType)) &&
                            item.title.songTitleKey() == title &&
                            item.artists.any { artist ->
                                (artist.id != null && artist.id in artistIds) || artist.name.trim().lowercase() in artistNames
                            }
                    }?.toTrack()
                    ?.let { found ->
                        found.copy(
                            thumbnails =
                                found.thumbnails?.map {
                                    it.copy(url = Regex("=w\\d+-h\\d+").replace(it.url, "=w544-h544"), width = 544, height = 544)
                                },
                        )
                    }
            Logger.d(TAG, "findSongVersion ${track.videoId} (${track.title}) -> ${song?.videoId}")
            songVersionsLock.withLock { songVersions[track.videoId] = song }
            song
        }

    private val videoVersions = mutableMapOf<String, Track?>()

    override suspend fun findVideoVersion(track: Track): Track? =
        withContext(Dispatchers.IO) {
            if (MusicVideoType.isVideoSong(track.videoType)) return@withContext null
            songVersionsLock.withLock {
                if (videoVersions.containsKey(track.videoId)) return@withContext videoVersions[track.videoId]
            }
            val title = track.title.songTitleKey()
            val artistIds = track.artists.orEmpty().mapNotNull { it.id?.takeIf(String::isNotBlank) }.toSet()
            val artistNames = track.artists.orEmpty().map { it.name.trim().lowercase() }.filter(String::isNotEmpty).toSet()
            if (title.isEmpty() || (artistIds.isEmpty() && artistNames.isEmpty())) return@withContext null
            val query = (listOf(track.title.songTitleQuery()) + track.artists.orEmpty().map { it.name }).joinToString(" ")
            val result = youTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull() ?: return@withContext null
            val video =
                result.items
                    .take(6)
                    .mapNotNull { item ->
                        when (item) {
                            is VideoItem -> VideoCandidate(item.musicVideoType, item.title, item.artists.map { it.id to it.name }, item.toTrack())
                            is SongItem -> VideoCandidate(item.musicVideoType, item.title, item.artists.map { it.id to it.name }, item.toTrack())
                            else -> null
                        }
                    }.firstOrNull { candidate ->
                        MusicVideoType.isOfficialVideo(candidate.type) &&
                            candidate.title.videoTitleKey(artistNames) == title &&
                            candidate.artists.any { (id, name) -> (id != null && id in artistIds) || name.trim().lowercase() in artistNames }
                    }?.track
            Logger.d(TAG, "findVideoVersion ${track.videoId} (${track.title}) -> ${video?.videoId}")
            songVersionsLock.withLock { videoVersions[track.videoId] = video }
            video
        }

    private class VideoCandidate(
        val type: String?,
        val title: String,
        val artists: List<Pair<String?, String>>,
        val track: Track,
    )

    private suspend fun List<SongItem>.dropShortWhenConfigured(): List<SongItem> {
        val minSeconds = dataStoreManager.skipShortTrackSeconds.first()
        if (minSeconds <= 0) return this
        return filter { song ->
            val d = song.duration
            d == null || d <= 0 || d >= minSeconds
        }
    }

    override fun getContinueTrack(
        playlistId: String,
        continuation: String,
        fromPlaylist: Boolean,
    ): Flow<Pair<ArrayList<Track>?, String?>> =
        flow {
            Logger.d(TAG, "getContinueTrack -> playlistId: $playlistId, continuation: $continuation")
            if (!fromPlaylist) {
                val result =
                    youTube.next(
                        if (playlistId.startsWith("RRDAMVM")) {
                            WatchEndpoint(videoId = playlistId.removePrefix("RRDAMVM"))
                        } else {
                            WatchEndpoint(playlistId = playlistId)
                        },
                        continuation = continuation,
                    )
                val next = result.getOrNull()
                if (next == null) {
                    Logger.e(TAG, "getContinueTrack -> ${result.exceptionOrNull()?.message}")
                    emit(Pair(null, null))
                } else {
                    val data = next.items.songsOrOfficialVideos().dropShortWhenConfigured()
                    emit(Pair(data.toListTrack(), next.continuation))
                }
            } else {
                val result =
                    youTube.customQuery(
                        browseId = null,
                        continuation = continuation,
                        setLogin = true,
                    )
                val values = result.getOrNull()
                if (values == null) {
                    Logger.e(TAG, "getContinueTrack -> ${result.exceptionOrNull()?.message}")
                    emit(Pair(null, null))
                } else {
                    val dataMore: List<SongItem> =
                        values.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                            ?.mapNotNull {
                                NextPage.fromMusicResponsiveListItemRenderer(
                                    it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                )
                            } ?: emptyList()
                    emit(Pair<ArrayList<Track>?, String?>(dataMore.songsOnly().toListTrack(), values.getPlaylistContinuation()))
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSongInfo(videoId: String): Flow<SongInfoEntity?> =
        flow {
            val id = videoId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            val songInfo = youTube.getSongInfo(id).getOrNull()
            if (songInfo == null) {
                emit(getSongInfoEntity(videoId).lastOrNull())
            } else {
                val song =
                    SongInfoEntity(
                        videoId = songInfo.videoId,
                        author = songInfo.author,
                        authorId = songInfo.authorId,
                        authorThumbnail = songInfo.authorThumbnail,
                        description = songInfo.description,
                        uploadDate = songInfo.uploadDate,
                        subscribers = songInfo.subscribers,
                        viewCount = songInfo.viewCount,
                        like = songInfo.like,
                        dislike = songInfo.dislike,
                    )
                emit(song)
                insertSongInfo(song)
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun getLikeStatus(videoId: String): Flow<Boolean> =
        flow {
            emit(youTube.getLikedInfo(videoId).getOrNull() == LikeStatus.LIKE)
        }.flowOn(Dispatchers.IO)

    override suspend fun dislikeOnYouTube(mediaId: String?): Flow<Int> =
        flow {
            if (mediaId != null) {
                emit(youTube.dislike(mediaId).getOrElse { Logger.e(TAG, "dislike: ${it.message}"); 0 })
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (mediaId != null) {
                emit(youTube.addToLiked(mediaId).getOrElse { Logger.e(TAG, "addToLiked: ${it.message}"); 0 })
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (mediaId != null) {
                emit(youTube.removeFromLiked(mediaId).getOrElse { Logger.e(TAG, "removeFromLiked: ${it.message}"); 0 })
            }
        }.flowOn(Dispatchers.IO)

    override fun downloadToFile(
        track: Track,
        path: String,
        videoId: String,
        isVideo: Boolean,
    ): Flow<DownloadProgress> =
        youTube
            .download(
                track.toSongItemForDownload(),
                path,
                videoId,
                isVideo,
            ).map {
                DownloadProgress(
                    audioDownloadProgress = it.audioDownloadProgress,
                    videoDownloadProgress = it.videoDownloadProgress,
                    downloadSpeed = it.downloadSpeed,
                    errorMessage = it.errorMessage,
                    isMerging = it.isMerging,
                    isError = it.isError,
                    isDone = it.isDone,
                )
            }

    override fun getYouTubeHistory(): Flow<Resource<List<Pair<String, List<Track>>>>> =
        flow {
            youTube
                .customQuery(browseId = "FEmusic_history")
                .onSuccess { response ->
                    val sections =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            .orEmpty()
                    val groups =
                        sections.mapNotNull { section ->
                            val shelf = section.musicShelfRenderer ?: return@mapNotNull null
                            val title = shelf.title?.runs?.joinToString("") { it.text }.orEmpty()
                            val tracks =
                                shelf.contents.orEmpty().mapNotNull { row ->
                                    row.musicResponsiveListItemRenderer?.let { PlaylistPage.fromMusicResponsiveListItemRenderer(it)?.toTrack() }
                                }
                            if (tracks.isEmpty()) null else title to tracks
                        }
                    emit(Resource.Success(groups))
                }.onFailure { error ->
                    emit(Resource.Error<List<Pair<String, List<Track>>>>(error.message ?: "History unavailable"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getRelatedPage(videoId: String): Flow<Resource<List<HomeItem>>> =
        flow {
            val endpoint =
                youTube
                    .next(WatchEndpoint(videoId = videoId))
                    .getOrNull()
                    ?.relatedEndpoint
            if (endpoint == null) {
                emit(Resource.Error<List<HomeItem>>("No related page"))
                return@flow
            }
            youTube
                .customQuery(browseId = endpoint.browseId, params = endpoint.params)
                .onSuccess { response ->
                    val sections = response.contents?.sectionListRenderer?.contents
                    emit(Resource.Success(parseMixedContent(sections, "", "Song")))
                }.onFailure { e ->
                    emit(Resource.Error<List<HomeItem>>(e.message ?: "Related unavailable"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            val result = youTube.next(WatchEndpoint(videoId = videoId))
            val next = result.getOrNull()
            if (next == null) {
                emit(Resource.Error<Pair<List<Track>, String?>>(result.exceptionOrNull()?.message.toString()))
            } else {
                val data =
                    next.items
                        .filter { it.id != videoId }
                        .distinct()
                        .songsOrOfficialVideos()
                        .dropShortWhenConfigured()
                emit(Resource.Success<Pair<List<Track>, String?>>(Pair(data.toListTrack().toList(), next.continuation)))
            }
        }.flowOn(Dispatchers.IO)

    override fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>> =
        flow {
            youTube
                .comments(videoId)
                .onSuccess {
                    Logger.i(
                        COMMENTS_TAG,
                        if (it == null) "Comments for $videoId: turned off" else "Comments for $videoId: ${it.header?.countText}, ${it.comments.size} on the first page",
                    )
                    emit(Resource.Success<YouTubeCommentsPage?>(it?.toYouTubeCommentsPage()))
                }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comments for $videoId failed", it)
                    emit(Resource.Error<YouTubeCommentsPage?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>> =
        flow {
            youTube
                .commentsPage(token)
                .onSuccess { emit(Resource.Success(it.toYouTubeCommentsPage())) }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comments page failed", it)
                    emit(Resource.Error<YouTubeCommentsPage>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun postComment(
        createCommentParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>> =
        flow {
            youTube
                .createComment(createCommentParams, text)
                .onSuccess {
                    Logger.i(COMMENTS_TAG, "Comment posted, returned=${it != null}")
                    emit(Resource.Success<YouTubeComment?>(it?.toYouTubeComment()))
                }.onFailure {
                    Logger.e(COMMENTS_TAG, "Posting a comment failed", it)
                    emit(Resource.Error<YouTubeComment?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun postCommentReply(
        createReplyParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>> =
        flow {
            youTube
                .createCommentReply(createReplyParams, text)
                .onSuccess {
                    Logger.i(COMMENTS_TAG, "Reply posted, returned=${it != null}")
                    emit(Resource.Success<YouTubeComment?>(it?.toYouTubeComment()))
                }.onFailure {
                    Logger.e(COMMENTS_TAG, "Posting a reply failed", it)
                    emit(Resource.Error<YouTubeComment?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun performCommentAction(action: String): Flow<Resource<Unit>> =
        flow {
            youTube
                .performCommentAction(action)
                .onSuccess { emit(Resource.Success(Unit)) }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comment action failed", it)
                    emit(Resource.Error<Unit>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            val result = youTube.next(endpoint.toWatchEndpoint())
            val next = result.getOrNull()
            if (next == null) {
                emit(Resource.Error(result.exceptionOrNull()?.message ?: "Error"))
            } else {
                emit(Resource.Success<Pair<List<Track>, String?>>(Pair(next.items.songsOrOfficialVideos().toListTrack(), next.continuation)))
            }
        }.flowOn(Dispatchers.IO)

    private fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> =
        flow { emit(localDataSource.getSongInfo(videoId)) }.flowOn(Dispatchers.IO)
}

// The quotes must open after a space and close before one, so the apostrophes in "Don't" and "let's" are never read as quotes.
private val QUOTED_TITLE = Regex("""(?:^|\s)['\u2018\u201C"](.+?)['\u2019\u201D"](?=\s|$)""")

private val VIDEO_LABEL =
    Regex("""\s*[(\[][^)\]]*\b(official|video|mv|m/v|lyrics?|visuali[sz]er|performance|audio|teaser)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

private fun String.songTitleQuery(): String = (QUOTED_TITLE.find(this)?.groupValues?.get(1) ?: this).replace(VIDEO_LABEL, "").trim()

private fun String.songTitleKey(): String = songTitleQuery().lowercase().replace(Regex("\\s+"), " ")

internal fun String.videoTitleKey(artistNames: Set<String>): String {
    val key = songTitleKey()
    val dash = key.indexOf(" - ")
    if (dash <= 0) return key
    val leftArtists = key.substring(0, dash).split(Regex("\\s*(?:,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*")).map(String::trim)
    return if (leftArtists.any { it in artistNames }) key.substring(dash + 3).trim() else key
}
