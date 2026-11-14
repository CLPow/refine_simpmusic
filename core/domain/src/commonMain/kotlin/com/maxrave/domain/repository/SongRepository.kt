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
    /**
     * The song (`MUSIC_VIDEO_TYPE_ATV`) that an official music video [track] is the video of, or
     * null when [track] is not an official video or no result matches it exactly.
     */
    suspend fun findSongVersion(track: Track): Track?

    /** Up next's radio tuning chips for the radio of [videoId] in [playlistId]; empty when none. */
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

    /**
     * Erase the local listening history and drop every song row nothing refers to any more.
     *
     * A song is kept when its id appears anywhere else at all: in an album, a YouTube playlist, a
     * local playlist, the saved queue, a podcast, or `set_video_id`. Everything left is a row the
     * app accumulated just by seeing the track once, and it takes its lyrics, translations, cached
     * format and extra info with it.
     *
     * @return how many songs were removed.
     */
    suspend fun clearHistoryAndOrphanedSongs(): Int

    fun getCanvasSong(max: Int): Flow<List<SongEntity>>

    fun getSongById(id: String): Flow<SongEntity?>

    fun getSongAsFlow(id: String): Flow<SongEntity?>

    fun insertSong(songEntity: SongEntity): Flow<Long>

    fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int>

    /**
     * Corrects `song.videoType` for a row that predates, or was written before, the parsers started
     * carrying YouTube's real `MUSIC_VIDEO_TYPE_*`. Callers must pass a value they actually know —
     * writing an unknown over a correct one loses information.
     */
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

    /**
     * YouTube Music's Related tab for [videoId], as YouTube sends it: "You might also like",
     * "Recommended playlists", "Similar artists", "More from ...", in its order.
     */
    fun getRelatedPage(videoId: String): Flow<Resource<List<HomeItem>>>

    /** The account's YouTube history (FEmusic_history), grouped as YouTube groups it: Today, Yesterday… */
    fun getYouTubeHistory(): Flow<Resource<List<Pair<String, List<Track>>>>>

    /**
     * A track's comments, from YouTube Music's Comments tab: the first page and its header.
     * Success with null when the track has none (comments turned off, or a live stream with only its chat).
     */
    fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>>

    /** Whatever a comments token loads: the next page, a thread's replies, or more of them. */
    fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>>

    /** Success with null when YouTube's answer did not carry the new comment back. */
    fun postComment(
        createCommentParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    /** Success with null when YouTube's answer did not carry the new reply back. */
    fun postCommentReply(
        createReplyParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    /** Like, dislike, or undo either: [action] is the token the comment carries for it. */
    fun performCommentAction(action: String): Flow<Resource<Unit>>

    fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>>
}