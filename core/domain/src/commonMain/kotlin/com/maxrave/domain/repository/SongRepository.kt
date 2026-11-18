package com.maxrave.domain.repository

import com.maxrave.domain.data.model.queue.RadioChip
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface SongRepository {
    suspend fun findSongVersion(track: Track): Track?

    suspend fun findVideoVersion(track: Track): Track?

    suspend fun getRadioChips(
        videoId: String,
        playlistId: String,
    ): List<RadioChip>

    fun getAllSongs(limit: Int): Flow<List<SongEntity>>

    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    )

    fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>>

    fun getDownloadedSongs(): Flow<List<SongEntity>?>

    fun getDownloadingSongs(): Flow<List<SongEntity>?>

    fun getPreparingSongs(): Flow<List<SongEntity>>

    fun getLikedSongs(): Flow<List<SongEntity>>

    suspend fun clearHistoryAndOrphanedSongs(): Int

    fun getCanvasSong(max: Int): Flow<List<SongEntity>>

    fun getSongById(id: String): Flow<SongEntity?>

    fun getSongAsFlow(id: String): Flow<SongEntity?>

    fun insertSong(songEntity: SongEntity): Flow<Long>

    fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int>

    fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Flow<Int>

    suspend fun updateListenCount(videoId: String)

    suspend fun resetTotalPlayTime(videoId: String)

    suspend fun updateLikeStatus(
        videoId: String,
        likeStatus: Int,
    )

    fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Flow<Int>

    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    )

    fun getMostPlayedSongs(): Flow<List<SongEntity>>

    suspend fun updateDownloadState(
        videoId: String,
        downloadState: Int,
    )

    suspend fun getRecentSong(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    suspend fun insertSongInfo(songInfo: SongInfoEntity)

    suspend fun recoverQueue(
        temp: List<Track>,
        playlistType: String? = null,
    )

    suspend fun getSavedQueue(): Flow<List<QueueEntity>?>

    fun getContinueTrack(
        playlistId: String,
        continuation: String,
        fromPlaylist: Boolean = false,
    ): Flow<Pair<ArrayList<Track>?, String?>>

    fun getSongInfo(videoId: String): Flow<SongInfoEntity?>

    suspend fun getLikeStatus(videoId: String): Flow<Boolean>

    suspend fun dislikeOnYouTube(mediaId: String?): Flow<Int>

    suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int>

    suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int>

    fun downloadToFile(
        track: Track,
        path: String,
        videoId: String,
        isVideo: Boolean,
    ): Flow<DownloadProgress>

    fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>>

    fun getRelatedPage(videoId: String): Flow<Resource<List<HomeItem>>>

    fun getYouTubeHistory(): Flow<Resource<List<Pair<String, List<Track>>>>>

    fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>>

    fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>>

    fun postComment(
        createCommentParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    fun postCommentReply(
        createReplyParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    fun performCommentAction(action: String): Flow<Resource<Unit>>

    fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>>
}