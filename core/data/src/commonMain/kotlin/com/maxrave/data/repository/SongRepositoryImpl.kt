package com.maxrave.data.repository

import com.maxrave.data.parser.parseMixedContent
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toSongItemForDownload
import com.maxrave.data.mapping.toWatchEndpoint
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.data.mapping.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.koin.mp.KoinPlatform.getKoin

private const val TAG = "SongRepositoryImpl"

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
            // SQLite's `WHERE videoId IN (:list)` does NOT preserve the input list order
            // (it returns rows in table/rowid order). Album tracks, local playlist items,
            // and queue snapshots all rely on caller-defined order, so we restore it here.
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

    override fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>) =
        localDataSource.getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId)

    override fun getLikedSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun downloadAllLikedSongs(): Int =
        withContext(Dispatchers.IO) {
            val pending =
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                }.filter { it.downloadState != DownloadState.STATE_DOWNLOADED }
            Logger.d(TAG, "Auto-download: queueing ${pending.size} liked songs")
            pending.forEach { song ->
                downloadHandler.downloadTrack(song.videoId, song.title, song.thumbnails.orEmpty())
            }
            pending.size
        }

    /**
     * The order below is the whole feature.
     *
     * A song is orphaned only once nothing points at it, so every cached container has to go first —
     * sweeping songs on their own found zero to delete, because eleven thousand of them were held
     * alive by playlists nobody prunes. Artists lead so the two tables keyed on `channelId` can be
     * measured against what is still followed; podcasts, albums and playlists follow; songs last,
     * then the satellite rows that were already orphaned before any of this ran.
     */
    override suspend fun clearHistoryAndOrphanedSongs(): Int =
        withContext(Dispatchers.IO) {
            persistLiveQueueBeforeSweep()
            localDataSource.deleteAllPlaybackEvents()
            val artists = localDataSource.deleteUnfollowedArtists()
            val notifications = localDataSource.deleteNotificationsOfUnfollowedArtists()
            val artistReleases = localDataSource.deleteFollowedArtistReleasesOfUnfollowedArtists()
            val podcasts = localDataSource.deleteUnfavoritedPodcasts()
            val albums = localDataSource.deleteUnreferencedAlbums()
            val playlists = localDataSource.deleteUnreferencedPlaylists()
            val orphans = localDataSource.getOrphanedSongIds()
            val removed = localDataSource.deleteSongsAndRelatedData(orphans)
            val satellites = localDataSource.deleteStaleSongSatellites()
            Logger.d(
                TAG,
                "Clear history: removed $removed of ${orphans.size} candidate songs, " +
                    "$artists artists, $notifications notifications, $artistReleases artist releases, " +
                    "$podcasts podcasts, $albums albums, $playlists playlists, $satellites stale rows",
            )
            // Last, and outside every statement above: SQLite refuses VACUUM inside a transaction.
            // Without it the file stays fragmented and less space comes back than the user expects.
            // The checkpoint first folds the WAL — which every delete above just filled — back into
            // the main file, so VACUUM actually has those pages to reclaim.
            //
            // Failure here is not failure of the operation: every delete has already committed, so
            // letting it throw would show an error toast for work that actually succeeded. It only
            // costs some disk space, and the next run reclaims it.
            runCatching {
                localDataSource.checkpoint()
                localDataSource.vacuum()
            }.onFailure { Logger.e(TAG, "VACUUM after clearing history failed: ${it.message}") }
            removed
        }

    /**
     * Write whatever is playing right now into the `queue` table, so the sweep cannot delete it.
     *
     * The sweep spares songs found in `queue.listTrack`, but that table is only written on pause,
     * on track change, and on exit — and only when the user has "save recent song and queue" on.
     * Two ways that loses the current session: with the setting off the table is empty, and even
     * with it on, the endless queue keeps appending tracks that no save has caught up with yet.
     * Either way the song playing through the speakers looks orphaned and goes.
     *
     * Deliberately ignores that setting: this is not "remember my queue for next launch", it is
     * "do not delete what is on screen". Writing nothing when the queue is empty matters too —
     * overwriting a previously saved queue with an empty one would strip the protection instead of
     * adding it.
     */
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

    /**
     * Resolved lazily rather than taken in the constructor: the download handler is implemented in
     * the platform media modules, and pulling it in at construction time would make this repository
     * depend on them being built first.
     */
    private val downloadHandler: DownloadHandler by lazy { getKoin().get() }

    /**
     * Lazy for the same reason as [downloadHandler], and here it also breaks a cycle: the handler
     * already depends on this repository (it calls [recoverQueue] whenever it saves the queue).
     */
    private val mediaPlayerHandler: MediaPlayerHandler by lazy { getKoin().get() }

    /**
     * Note the side effect: liking a song can start a download, when the user has asked for that.
     *
     * Deliberately one-way. It fires only on the way *in* (`likeStatus == 1`) and only for songs
     * not already offline, so re-liking something you already have does not queue it twice, and
     * unliking never removes a file — the user downloaded it, only they should undo that.
     */
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
//        if (dataStoreManager.combineLocalAndYouTubeLiked.first() == TRUE) {
//            if (likeStatus == 1) {
//                addToYouTubeLiked(videoId).collect { result ->
//                    Logger.d(TAG, "updateLikeStatus -> addToYouTubeLiked: $result")
//                }
//            } else {
//                removeFromYouTubeLiked(videoId).collect { result ->
//                    Logger.d(TAG, "updateLikeStatus -> removeFromYouTubeLiked: $result")
//                }
//            }
//        }
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

    override suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> =
        flow { emit(localDataSource.getSongInfo(videoId)) }.flowOn(Dispatchers.IO)

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

    override suspend fun removeQueue() {
        withContext(Dispatchers.IO) { localDataSource.deleteQueue() }
    }

    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> =
        flow {
            emit(localDataSource.getQueue())
        }.flowOn(Dispatchers.IO)

    /**
     * Refine plays songs only, everywhere: every queue source passes through here.
     *
     * Queue rows that ship both renditions were already resolved to their audio side by
     * `PlaylistPanelRenderer.Content.songTrack`; what reaches this point with a video type has no
     * audio counterpart (measured: almost always `MUSIC_VIDEO_TYPE_UGC` fan remixes), so it is
     * dropped. Unknown types are kept — see [MusicVideoType.isPlayableSong].
     */
    private fun List<SongItem>.songsOnly(): List<SongItem> = filter { MusicVideoType.isPlayableSong(it.musicVideoType) }

    /**
     * [songsOnly] for a radio, except that a radio with no songs left keeps its official videos.
     *
     * The radio of an official video is itself almost all official videos (measured on A2O's
     * "NEON" video, KGK2KZOFAPE: 48 of 50), so filtering it to songs left nothing and playback
     * stopped after the one track. Videos played as audio are better than silence.
     */
    private fun List<SongItem>.songsOrOfficialVideos(): List<SongItem> =
        songsOnly().ifEmpty { filter { MusicVideoType.isOfficialVideo(it.musicVideoType) } }

    // Official video id -> its song, or null when YouTube had none. For this run of the app.
    private val songVersions = mutableMapOf<String, Track?>()
    private val songVersionsLock = Mutex()

    override suspend fun findSongVersion(track: Track): Track? =
        withContext(Dispatchers.IO) {
            if (!MusicVideoType.isOfficialVideo(track.videoType)) return@withContext null
            songVersionsLock.withLock {
                if (songVersions.containsKey(track.videoId)) return@withContext songVersions[track.videoId]
            }
            // A tile or queue row for a video carries no link to its song (checked on Home, the
            // radio and the player response), so ask the Songs search and accept only an exact
            // match: the same title and at least one of the same artists. Anything less plays
            // the video as before.
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

    /**
     * Drop entries shorter than the user's skip-short threshold. Unknown duration is kept:
     * a missing length is not a reason to hide a track.
     */
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
                // `RRDAMVM…` is YouTube's other spelling for the radio of a single video.
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

    override suspend fun getYouTubeRating(videoId: String): Flow<Int> =
        flow {
            emit(
                when (youTube.getLikedInfo(videoId).getOrNull()) {
                    LikeStatus.LIKE -> 1
                    LikeStatus.DISLIKE -> -1
                    else -> 0
                },
            )
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
                // Always a radio, though not directly: `next(videoId)` alone answers with just two
                // rows — the song itself and an `automixPreviewVideoRenderer` pointing at its
                // `RDAMVM…` radio. `YouTube.next` follows that pointer and splices the radio in, so
                // everything past the first row is radio content that extends the queue.
                val data =
                    next.items
                        .filter { it.id != videoId }
                        .distinct()
                        .songsOrOfficialVideos()
                        .dropShortWhenConfigured()
                emit(Resource.Success<Pair<List<Track>, String?>>(Pair(data.toListTrack().toList(), next.continuation)))
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
}

// What a video title says inside quotes is the song: A2O MAY 'BAD HABITS TONITE' Official Video.
// The quotes must open after a space and close before one, so the apostrophes in "Don't" and
// "let's" are never read as quotes.
private val QUOTED_TITLE = Regex("""(?:^|\s)['\u2018\u201C"](.+?)['\u2019\u201D"](?=\s|$)""")

// Bracketed video labels that are not part of the song's title.
private val VIDEO_LABEL =
    Regex("""\s*[(\[][^)\]]*\b(official|video|mv|m/v|lyrics?|visuali[sz]er|performance|audio|teaser)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

/** The song title a video title stands for, for the search query. */
private fun String.songTitleQuery(): String = (QUOTED_TITLE.find(this)?.groupValues?.get(1) ?: this).replace(VIDEO_LABEL, "").trim()

/** [songTitleQuery] in a form two titles can be compared by. */
private fun String.songTitleKey(): String = songTitleQuery().lowercase().replace(Regex("\\s+"), " ")
