import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.extension.now
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

private const val PLACEHOLDER_ALBUM_NAME = "Album"

@Dao
interface DatabaseDao {
    @Transaction
    suspend fun getAllRecentData(): List<RecentlyType> {
        val a = mutableListOf<RecentlyType>()
        a.addAll(getAllSongs(30))
        a.addAll(getAllArtists(30))
        a.addAll(getAllAlbums(30))
        a.addAll(getAllPlaylists(30))
        val sortedList =
            a.sortedWith<RecentlyType>(
                Comparator { p0, p1 ->
                    val timeP0: LocalDateTime? =
                        when (p0) {
                            is SongEntity -> p0.inLibrary
                            is ArtistEntity -> p0.inLibrary
                            is AlbumEntity -> p0.inLibrary
                            is PlaylistEntity -> p0.inLibrary
                            else -> null
                        }
                    val timeP1: LocalDateTime? =
                        when (p1) {
                            is SongEntity -> p1.inLibrary
                            is ArtistEntity -> p1.inLibrary
                            is AlbumEntity -> p1.inLibrary
                            is PlaylistEntity -> p1.inLibrary
                            else -> null
                        }
                    if (timeP0 == null || timeP1 == null) {
                        return@Comparator if (timeP0 == null && timeP1 == null) {
                            0
                        } else if (timeP0 == null) {
                            -1
                        } else {
                            1
                        }
                    }
                    timeP0.compareTo(timeP1)
                },
            )
        return sortedList.takeLast(30)
    }

    @Transaction
    suspend fun getAllDownloadedPlaylist(): List<PlaylistType> {
        val a = mutableListOf<PlaylistType>()
        var shouldContinue = true to 0
        while (shouldContinue.first) {
            val fetched =
                getDownloadedAlbums(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinue =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinue.second + 100
                }
        }
        var shouldContinuePlaylist = true to 0
        while (shouldContinuePlaylist.first) {
            val fetched =
                getDownloadedPlaylists(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinuePlaylist =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinuePlaylist.second + 100
                }
        }
        val sortedList =
            a.sortedWith<PlaylistType>(
                Comparator { p0, p1 ->
                    val timeP0: LocalDateTime? =
                        when (p0) {
                            is AlbumEntity -> p0.downloadedAt ?: p0.inLibrary
                            is PlaylistEntity -> p0.downloadedAt ?: p0.inLibrary
                            else -> null
                        }
                    val timeP1: LocalDateTime? =
                        when (p1) {
                            is AlbumEntity -> p1.downloadedAt ?: p1.inLibrary
                            is PlaylistEntity -> p1.downloadedAt ?: p1.inLibrary
                            else -> null
                        }
                    if (timeP0 == null || timeP1 == null) {
                        return@Comparator if (timeP0 == null && timeP1 == null) {
                            0
                        } else if (timeP0 == null) {
                            -1
                        } else {
                            1
                        }
                    }
                    timeP0.compareTo(timeP1)
                },
            )
        return sortedList
    }

    @Transaction
    suspend fun getAllDownloadingPlaylist(): List<PlaylistType> {
        val a = mutableListOf<PlaylistType>()
        var shouldContinue = true to 0
        while (shouldContinue.first) {
            val fetched =
                getDownloadingAlbums(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinue =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinue.second + 100
                }
        }
        var shouldContinuePlaylist = true to 0
        while (shouldContinuePlaylist.first) {
            val fetched =
                getDownloadingPlaylists(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinuePlaylist =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinuePlaylist.second + 100
                }
        }
        return a
    }

    @Query("SELECT * FROM search_history")
    suspend fun getSearchHistory(): List<SearchHistory>

    @Query("DELETE FROM search_history")
    suspend fun deleteSearchHistory()

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchHistoryItem(query: String)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSearchHistory(searchHistory: SearchHistory): Long

    @Query("SELECT * FROM song ORDER BY inLibrary DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllSongs(limit: Int): List<SongEntity>

    @Query("SELECT * FROM song WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE videoId = :videoId")
    suspend fun getSong(videoId: String): SongEntity?

    @Query("SELECT * FROM song WHERE videoId = :videoId")
    fun getSongAsFlow(videoId: String): Flow<SongEntity?>

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + 1 WHERE videoId = :videoId")
    suspend fun updateTotalPlayTime(videoId: String)

    @Query("UPDATE song SET totalPlayTime = 0 WHERE videoId = :videoId")
    suspend fun resetTotalPlayTime(videoId: String)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE videoId = :videoId")
    suspend fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Int

    @Query("UPDATE song SET liked = :liked, favoriteAt = :favoriteAt WHERE videoId = :videoId")
    suspend fun updateLiked(
        liked: Int,
        videoId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    @Query(
        "UPDATE song SET albumName = :albumName, albumId = :albumId " +
            "WHERE videoId = :videoId AND (albumName IS NULL OR albumName = '' OR albumName = 'Album')",
    )
    suspend fun refreshAlbumIfPlaceholder(
        videoId: String,
        albumName: String,
        albumId: String?,
    )

    @Query(
        "UPDATE song SET artistName = :artistName, artistId = :artistId " +
            "WHERE videoId = :videoId AND artistName IS NOT :artistName",
    )
    suspend fun refreshArtists(
        videoId: String,
        artistName: List<String>,
        artistId: List<String>?,
    )

    @Query("UPDATE song SET thumbnails = :thumbnails WHERE videoId = :videoId")
    suspend fun updateThumbnailsSongEntity(
        thumbnails: String,
        videoId: String,
    ): Int

    @Query("UPDATE song SET videoType = :videoType WHERE videoId = :videoId")
    suspend fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Int

    @Query("SELECT * FROM song WHERE totalPlayTime > 1 ORDER BY totalPlayTime DESC LIMIT 50")
    fun getMostPlayedSongs(): Flow<List<SongEntity>>

    @Query("UPDATE song SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE videoId = :videoId")
    suspend fun updateDownloadState(
        downloadState: Int,
        videoId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("UPDATE song SET durationSeconds = :durationSeconds WHERE videoId = :videoId")
    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    )

    @Query("SELECT * FROM song WHERE downloadState = 3 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE videoId IN (:primaryKeyList) LIMIT 1000")
    suspend fun getSongByListVideoIdFull(primaryKeyList: List<String>): List<SongEntity>

    @Query("SELECT * FROM song WHERE canvasThumbUrl IS NOT NULL ORDER BY totalPlayTime DESC LIMIT :max")
    suspend fun getCanvasSong(max: Int): List<SongEntity>

    @Query("SELECT * FROM artist ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllArtists(limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM artist WHERE channelId = :channelId")
    suspend fun getArtist(channelId: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE followed = 1 ORDER BY followedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getFollowedArtists(
        limit: Int,
        offset: Int,
    ): List<ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertArtist(artist: ArtistEntity)

    @Query("UPDATE artist SET thumbnails = :thumbnails WHERE channelId = :channelId")
    suspend fun updateArtistImage(
        channelId: String,
        thumbnails: String,
    )

    @Query("UPDATE artist SET nameLogoUrl = :nameLogoUrl, nameLogoColor = :nameLogoColor WHERE channelId = :channelId")
    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    )

    @Query("UPDATE artist SET followed = :followed, followedAt = :followedAt WHERE channelId = :channelId")
    suspend fun updateFollowed(
        followed: Int,
        channelId: String,
        followedAt: LocalDateTime? = if (followed == 1) now() else null,
    )

    @Query("UPDATE artist SET inLibrary = :inLibrary WHERE channelId = :channelId")
    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    )

    @Query("SELECT * FROM album ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllAlbums(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM album WHERE browseId = :browseId")
    suspend fun getAlbum(browseId: String): AlbumEntity?

    @Query("SELECT * FROM album WHERE browseId = :browseId")
    fun getAlbumAsFlow(browseId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM album WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("UPDATE album SET liked = :liked, favoriteAt = :favoriteAt  WHERE browseId = :browseId")
    suspend fun updateAlbumLiked(
        liked: Int,
        browseId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Query("UPDATE album SET inLibrary = :inLibrary WHERE browseId = :browseId")
    suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        browseId: String,
    )

    @Query("UPDATE album SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE browseId = :browseId")
    suspend fun updateAlbumDownloadState(
        downloadState: Int,
        browseId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("SELECT * FROM album WHERE downloadState = 3 ORDER BY downloadedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    @Query("SELECT * FROM album WHERE downloadState = 2 OR downloadState = 1 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    @Query("SELECT * FROM playlist ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllPlaylists(limit: Int): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertAndReplacePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertRadioPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlist SET liked = :liked, favoriteAt = :favoriteAt WHERE id = :playlistId")
    suspend fun updatePlaylistLiked(
        liked: Int,
        playlistId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Query("UPDATE playlist SET inLibrary = :inLibrary WHERE id = :playlistId")
    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    )

    @Query("UPDATE playlist SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE id = :playlistId")
    suspend fun updatePlaylistDownloadState(
        downloadState: Int,
        playlistId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("SELECT * FROM playlist WHERE downloadState = 3 ORDER BY downloadedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    @Query("SELECT * FROM local_playlist ORDER BY inLibrary DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllLocalPlaylists(
        limit: Int,
        offset: Int,
    ): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlist WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getAllDownloadingLocalPlaylists(
        limit: Int,
        offset: Int,
    ): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlist WHERE id = :id")
    suspend fun getLocalPlaylist(id: Long): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity): Long

    @Query("DELETE FROM local_playlist WHERE id = :id")
    suspend fun deleteLocalPlaylist(id: Long)

    @Query("UPDATE local_playlist SET title = :title WHERE id = :id")
    suspend fun updateLocalPlaylistTitle(
        title: String,
        id: Long,
    )

    @Query("UPDATE local_playlist SET tracks = :tracks WHERE id = :id")
    suspend fun updateLocalPlaylistTracks(
        tracks: List<String>,
        id: Long,
    )

    @Query("UPDATE local_playlist SET thumbnail = :thumbnail WHERE id = :id")
    suspend fun updateLocalPlaylistThumbnail(
        thumbnail: String,
        id: Long,
    )

    @Query("UPDATE local_playlist SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE id = :id")
    suspend fun updateLocalPlaylistDownloadState(
        downloadState: Int,
        id: Long,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("UPDATE local_playlist SET youtubePlaylistId = :youtubePlaylistId WHERE id = :id")
    suspend fun updateLocalPlaylistYouTubePlaylistId(
        id: Long,
        youtubePlaylistId: String?,
    )

    @Query("UPDATE local_playlist SET youtube_sync_state = :state WHERE id = :id")
    suspend fun updateLocalPlaylistYouTubePlaylistSyncState(
        id: Long,
        state: Int,
    )

    @Query("UPDATE local_playlist SET youtube_sync_state = 0, youtubePlaylistId = NULL WHERE id = :id")
    suspend fun unsyncLocalPlaylist(id: Long)

    @Query("SELECT tracks FROM local_playlist WHERE id = :id")
    fun getListTracksFlowOfLocalPlaylist(id: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun getLyrics(videoId: String): LyricsEntity?

    @RawQuery
    suspend fun raw(supportSQLiteQuery: RoomRawQuery): Int

    suspend fun checkpoint() {
        raw(RoomRawQuery("pragma wal_checkpoint(full)"))
    }

    @Query("SELECT * FROM song WHERE downloadState = 1 OR downloadState = 2 ORDER BY downloadedAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getPreparingSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertNewFormat(format: NewFormatEntity)

    @Update
    suspend fun updateNewFormat(format: NewFormatEntity)

    @Query("SELECT * FROM new_format WHERE videoId = :videoId")
    suspend fun getNewFormat(videoId: String): NewFormatEntity?

    @Query("SELECT * FROM new_format WHERE videoId = :videoId")
    fun getNewFormatAsFlow(videoId: String): Flow<NewFormatEntity?>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSongInfo(songInfo: SongInfoEntity)

    @Query("SELECT * FROM song_info WHERE videoId = :videoId")
    suspend fun getSongInfo(videoId: String): SongInfoEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun recoverQueue(queue: QueueEntity)

    @Query("SELECT * FROM queue LIMIT 1 OFFSET 0")
    suspend fun getQueue(): List<QueueEntity>

    @Query("SELECT * FROM local_playlist WHERE youtubePlaylistId = :youtubePlaylistId")
    suspend fun getLocalPlaylistByYoutubePlaylistId(youtubePlaylistId: String): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSetVideoId(setVideoIdEntity: SetVideoIdEntity)

    @Query(
        "SELECT * FROM set_video_id WHERE videoId = :videoId AND youtubePlaylistId IN (:youtubePlaylistIds) " +
            "AND setVideoId IS NOT NULL LIMIT 1",
    )
    suspend fun getSetVideoId(
        videoId: String,
        youtubePlaylistIds: List<String>,
    ): SetVideoIdEntity?

    @Query("DELETE FROM set_video_id WHERE videoId = :videoId AND youtubePlaylistId = :youtubePlaylistId")
    suspend fun deleteSetVideoId(
        videoId: String,
        youtubePlaylistId: String,
    )

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist)

    @Query("SELECT * FROM pair_song_local_playlist WHERE songId = :videoId AND playlistId = :localPlaylistId LIMIT 1")
    suspend fun getPlaylistPairOfSong(
        videoId: String,
        localPlaylistId: Long,
    ): PairSongLocalPlaylist

    @Query("SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND position in (:positionList)")
    suspend fun getPlaylistPairSongByListPosition(
        playlistId: Long,
        positionList: List<Int>,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT p.* FROM pair_song_local_playlist p JOIN song s ON s.videoId = p.songId " +
            "WHERE p.playlistId = :playlistId AND " +
            "(s.title LIKE :query ESCAPE '\\' OR s.artistName LIKE :query ESCAPE '\\') " +
            "ORDER BY p.position ASC LIMIT :limit",
    )
    suspend fun searchPlaylistPairSong(
        playlistId: Long,
        query: String,
        limit: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position " +
            "ASC LIMIT 50 OFFSET :offset",
    )
    suspend fun getPlaylistPairSongByOffset(
        playlistId: Long,
        offset: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND inPlaylist > :cutPoint ORDER BY position " +
            "ASC LIMIT 50",
    )
    suspend fun getPlaylistPairSongByOlderFirst(
        playlistId: Long,
        cutPoint: LocalDateTime,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND inPlaylist < :cutPoint ORDER BY position " +
            "DESC LIMIT 50",
    )
    suspend fun getPlaylistPairSongByNewerFirst(
        playlistId: Long,
        cutPoint: LocalDateTime,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position DESC LIMIT 1",
    )
    suspend fun getNewestPlaylistPairSong(playlistId: Long): PairSongLocalPlaylist?

    @Query(
        "UPDATE pair_song_local_playlist SET position = :newPosition WHERE playlistId = :playlistId AND songId = :videoId",
    )
    suspend fun editPositionOfSongInPlaylist(
        playlistId: Long,
        videoId: String,
        newPosition: Int,
    )

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position ASC",
    )
    suspend fun getAllPlaylistPairSongByPosition(playlistId: Long): List<PairSongLocalPlaylist>

    @Query(
        "UPDATE pair_song_local_playlist SET position = position + 1 WHERE playlistId = :playlistId AND position >= :from AND position < :to",
    )
    suspend fun shiftPositionsForward(
        playlistId: Long,
        from: Int,
        to: Int,
    )

    @Query(
        "UPDATE pair_song_local_playlist SET position = position - 1 WHERE playlistId = :playlistId AND position > :from AND position <= :to",
    )
    suspend fun shiftPositionsBackward(
        playlistId: Long,
        from: Int,
        to: Int,
    )

    @Transaction
    suspend fun moveSongInPlaylist(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        val pairs = getAllPlaylistPairSongByPosition(playlistId)
        if (fromIndex !in pairs.indices || toIndex !in pairs.indices) return false
        val moved = pairs[fromIndex]
        val target = pairs[toIndex].position
        if (fromIndex < toIndex) {
            shiftPositionsBackward(playlistId, moved.position, target)
        } else {
            shiftPositionsForward(playlistId, target, moved.position)
        }
        editPositionOfSongInPlaylist(playlistId, moved.songId, target)
        return true
    }

    @Query(
        "SELECT p.* FROM pair_song_local_playlist p JOIN song s ON p.songId = s.videoId WHERE" +
            " p.playlistId = :playlistId ORDER BY s.title ASC LIMIT 50 OFFSET :offset",
    )
    suspend fun getPlaylistPairSongByTitle(
        playlistId: Long,
        offset: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "DELETE FROM pair_song_local_playlist WHERE songId = :videoId AND playlistId = :playlistId",
    )
    suspend fun deletePairSongLocalPlaylist(
        playlistId: Long,
        videoId: String,
    )

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Long

    @Query("SELECT * FROM googleaccountentity")
    suspend fun getAllGoogleAccount(): List<GoogleAccountEntity>

    @Query("SELECT * FROM googleaccountentity WHERE isUsed = 1")
    suspend fun getUsedGoogleAccount(): GoogleAccountEntity?

    @Query("UPDATE googleaccountentity SET isUsed = :isUsed WHERE email = :email")
    suspend fun updateGoogleAccountUsed(
        isUsed: Boolean,
        email: String,
    ): Int

    @Query("DELETE FROM googleaccountentity WHERE email = :email")
    suspend fun deleteGoogleAccount(email: String)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE videoId = :videoId")
    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    )

    @Insert(onConflict = REPLACE)
    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList)

    @Query("SELECT * FROM your_youtube_playlist_list WHERE emailPageId = :emailPageId LIMIT 1")
    suspend fun getYourYouTubePlaylistList(emailPageId: String): YourYouTubePlaylistList?

    @Query("DELETE FROM your_youtube_playlist_list")
    suspend fun deleteAllYourYouTubePlaylist()

    @Query(
        "DELETE FROM artist WHERE followed = 0 AND " +
            "NOT EXISTS (SELECT 1 FROM song s WHERE (s.liked = 1 OR s.downloadState != 0) AND s.artistId LIKE " +
            "'%\"' || replace(replace(replace(artist.channelId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteUnfollowedArtists(): Int

    @Query(
        "DELETE FROM album WHERE liked = 0 AND downloadState = 0 AND " +
            "NOT EXISTS (SELECT 1 FROM song s WHERE s.albumId = album.browseId AND (s.liked = 1 OR s.downloadState != 0))",
    )
    suspend fun deleteUnreferencedAlbums(): Int

    @Query(
        "DELETE FROM playlist WHERE liked = 0 AND downloadState = 0 AND " +
            "id NOT IN (SELECT youtubePlaylistId FROM set_video_id) AND " +
            "id NOT IN (SELECT youtubePlaylistId FROM local_playlist WHERE youtubePlaylistId IS NOT NULL) AND " +
            "NOT EXISTS (SELECT 1 FROM your_youtube_playlist_list y WHERE y.listBrowseIds LIKE " +
            "'%\"' || replace(replace(replace(playlist.id, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteUnreferencedPlaylists(): Int

    @Query(
        "SELECT videoId FROM song WHERE " +
            "liked = 0 AND downloadState = 0 AND " +
            "videoId NOT IN (SELECT songId FROM pair_song_local_playlist) AND " +
            "videoId NOT IN (SELECT videoId FROM set_video_id) AND " +
            "NOT EXISTS (SELECT 1 FROM album WHERE album.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM playlist WHERE playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM local_playlist WHERE local_playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM queue WHERE queue.listTrack LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun getOrphanedSongIds(): List<String>

    @Query(
        "DELETE FROM song WHERE videoId IN (:videoIds) AND " +
            "liked = 0 AND downloadState = 0 AND " +
            "videoId NOT IN (SELECT songId FROM pair_song_local_playlist) AND " +
            "videoId NOT IN (SELECT videoId FROM set_video_id) AND " +
            "NOT EXISTS (SELECT 1 FROM album WHERE album.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM playlist WHERE playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM local_playlist WHERE local_playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM queue WHERE queue.listTrack LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteSongsByIds(videoIds: List<String>): Int

    @Query("DELETE FROM lyrics WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteLyricsByIds(videoIds: List<String>)

    @Query("DELETE FROM new_format WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteNewFormatsByIds(videoIds: List<String>)

    @Query("DELETE FROM song_info WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteSongInfoByIds(videoIds: List<String>)

    @Query("DELETE FROM new_format WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleNewFormats(): Int

    @Query("DELETE FROM lyrics WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleLyrics(): Int

    @Query("DELETE FROM lyrics")
    suspend fun deleteAllLyrics(): Int

    @Query("DELETE FROM song_info WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleSongInfo(): Int

}
