package com.maxrave.data.db.datasource

import DatabaseDao
import com.maxrave.data.db.MusicDatabase
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
import com.maxrave.domain.extension.now
import com.maxrave.domain.utils.FilterState
import kotlinx.datetime.LocalDateTime

private const val DELETE_BATCH_SIZE = 400

private const val PLACEHOLDER_ALBUM_NAME = "Album"

internal class LocalDataSource(
    private val databaseDao: DatabaseDao,
    private val musicDatabase: MusicDatabase,
) {
    suspend fun checkpoint() = databaseDao.checkpoint()

    suspend fun vacuum() = musicDatabase.vacuum()

    suspend fun getAllRecentData() = databaseDao.getAllRecentData()

    suspend fun getAllDownloadedPlaylist() = databaseDao.getAllDownloadedPlaylist()

    suspend fun getAllDownloadingPlaylist() = databaseDao.getAllDownloadingPlaylist()

    suspend fun getSearchHistory() = databaseDao.getSearchHistory()

    suspend fun deleteSearchHistory() = databaseDao.deleteSearchHistory()

    suspend fun deleteSearchHistoryItem(query: String) = databaseDao.deleteSearchHistoryItem(query)

    suspend fun insertSearchHistory(searchHistory: SearchHistory) = databaseDao.insertSearchHistory(searchHistory)

    suspend fun getAllSongs(limit: Int) = databaseDao.getAllSongs(limit)

    suspend fun getRecentSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getRecentSongs(limit, offset)

    suspend fun getCanvasSong(max: Int) = databaseDao.getCanvasSong(max)

    suspend fun getSongByListVideoIdFull(primaryKeyList: List<String>) = databaseDao.getSongByListVideoIdFull(primaryKeyList)

    suspend fun getDownloadedSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getDownloadedSongs(
        limit,
        offset,
    )

    suspend fun getDownloadingSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getDownloadingSongs(
        limit,
        offset,
    )

    suspend fun deleteUnfollowedArtists() = databaseDao.deleteUnfollowedArtists()

    suspend fun deleteUnreferencedAlbums() = databaseDao.deleteUnreferencedAlbums()

    suspend fun deleteUnreferencedPlaylists() = databaseDao.deleteUnreferencedPlaylists()

    suspend fun getOrphanedSongIds() = databaseDao.getOrphanedSongIds()

    suspend fun deleteSongsAndRelatedData(videoIds: List<String>): Int =
        videoIds.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
            val deleted = databaseDao.deleteSongsByIds(batch)
            databaseDao.deleteLyricsByIds(batch)
            databaseDao.deleteNewFormatsByIds(batch)
            databaseDao.deleteSongInfoByIds(batch)
            deleted
        }

    suspend fun deleteStaleSongSatellites(): Int =
        databaseDao.deleteStaleNewFormats() +
            databaseDao.deleteStaleLyrics() +
            databaseDao.deleteStaleSongInfo()

    suspend fun getLikedSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedSongs(
        limit,
        offset,
    )

    suspend fun getSong(videoId: String) = databaseDao.getSong(videoId)

    fun getSongAsFlow(videoId: String) = databaseDao.getSongAsFlow(videoId)

    suspend fun insertSong(song: SongEntity): Long {
        val rowId = databaseDao.insertSong(song)
        if (rowId == -1L) {
            val albumName = song.albumName
            if (!albumName.isNullOrBlank() && albumName != PLACEHOLDER_ALBUM_NAME) {
                databaseDao.refreshAlbumIfPlaceholder(
                    videoId = song.videoId,
                    albumName = albumName,
                    albumId = song.albumId,
                )
            }
            val artistName = song.artistName
            if (!artistName.isNullOrEmpty()) {
                databaseDao.refreshArtists(
                    videoId = song.videoId,
                    artistName = artistName,
                    artistId = song.artistId,
                )
            }
        }
        return rowId
    }

    suspend fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ) = databaseDao.updateThumbnailsSongEntity(thumbnail, videoId)

    suspend fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ) = databaseDao.updateVideoTypeSongEntity(videoType, videoId)

    suspend fun updateListenCount(videoId: String) = databaseDao.updateTotalPlayTime(videoId)

    suspend fun resetTotalPlayTime(videoId: String) = databaseDao.resetTotalPlayTime(videoId)

    suspend fun updateLiked(
        liked: Int,
        videoId: String,
    ) = databaseDao.updateLiked(liked, videoId)

    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    ) = databaseDao.updateDurationSeconds(durationSeconds, videoId)

    suspend fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ) = databaseDao.updateSongInLibrary(inLibrary, videoId)

    fun getMostPlayedSongs() = databaseDao.getMostPlayedSongs()

    suspend fun updateDownloadState(
        downloadState: Int,
        videoId: String,
    ) = databaseDao.updateDownloadState(downloadState, videoId)

    suspend fun getAllArtists(limit: Int) = databaseDao.getAllArtists(limit)

    suspend fun insertArtist(artist: ArtistEntity) = databaseDao.insertArtist(artist)

    suspend fun updateArtistImage(
        channelId: String,
        thumbnails: String,
    ) = databaseDao.updateArtistImage(channelId, thumbnails)

    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    ) = databaseDao.updateArtistNameLogo(channelId, nameLogoUrl, nameLogoColor)

    suspend fun updateFollowed(
        followed: Int,
        channelId: String,
    ) = databaseDao.updateFollowed(followed, channelId)

    suspend fun getArtist(channelId: String) = databaseDao.getArtist(channelId)

    suspend fun getFollowedArtists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getFollowedArtists(
        limit,
        offset,
    )

    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    ) = databaseDao.updateArtistInLibrary(inLibrary, channelId)

    suspend fun getAllAlbums(limit: Int) = databaseDao.getAllAlbums(limit)

    suspend fun insertAlbum(album: AlbumEntity) = databaseDao.insertAlbum(album)

    suspend fun updateAlbumLiked(
        liked: Int,
        albumId: String,
    ) = databaseDao.updateAlbumLiked(liked, albumId)

    suspend fun getAlbum(albumId: String) = databaseDao.getAlbum(albumId)

    fun getAlbumAsFlow(albumId: String) = databaseDao.getAlbumAsFlow(albumId)

    suspend fun getLikedAlbums(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedAlbums(
        limit,
        offset,
    )

    suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        albumId: String,
    ) = databaseDao.updateAlbumInLibrary(inLibrary, albumId)

    suspend fun updateAlbumDownloadState(
        downloadState: Int,
        albumId: String,
    ) = databaseDao.updateAlbumDownloadState(downloadState, albumId)

    suspend fun getAllPlaylists(limit: Int) = databaseDao.getAllPlaylists(limit)

    suspend fun insertPlaylist(playlist: PlaylistEntity) = databaseDao.insertPlaylist(playlist)

    suspend fun insertAndReplacePlaylist(playlist: PlaylistEntity) = databaseDao.insertAndReplacePlaylist(playlist)

    suspend fun insertRadioPlaylist(playlist: PlaylistEntity) = databaseDao.insertRadioPlaylist(playlist)

    suspend fun updatePlaylistLiked(
        liked: Int,
        playlistId: String,
    ) = databaseDao.updatePlaylistLiked(liked, playlistId)

    suspend fun getPlaylist(playlistId: String) = databaseDao.getPlaylist(playlistId)

    suspend fun getLikedPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedPlaylists(
        limit,
        offset,
    )

    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    ) = databaseDao.updatePlaylistInLibrary(inLibrary, playlistId)

    suspend fun updatePlaylistDownloadState(
        downloadState: Int,
        playlistId: String,
    ) = databaseDao.updatePlaylistDownloadState(downloadState, playlistId)

    suspend fun getAllLocalPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllLocalPlaylists(
        limit,
        offset,
    )

    suspend fun getAllDownloadingLocalPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllDownloadingLocalPlaylists(
        limit,
        offset,
    )

    suspend fun getLocalPlaylist(id: Long) = databaseDao.getLocalPlaylist(id)

    suspend fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity) = databaseDao.insertLocalPlaylist(localPlaylist)

    suspend fun deleteLocalPlaylist(id: Long) = databaseDao.deleteLocalPlaylist(id)

    suspend fun updateLocalPlaylistTitle(
        title: String,
        id: Long,
    ) = databaseDao.updateLocalPlaylistTitle(title, id)

    suspend fun updateLocalPlaylistThumbnail(
        thumbnail: String,
        id: Long,
    ) = databaseDao.updateLocalPlaylistThumbnail(thumbnail, id)

    suspend fun updateLocalPlaylistTracks(
        tracks: List<String>,
        id: Long,
    ) = databaseDao.updateLocalPlaylistTracks(tracks, id)

    suspend fun updateLocalPlaylistDownloadState(
        downloadState: Int,
        id: Long,
    ) = databaseDao.updateLocalPlaylistDownloadState(downloadState, id)

    suspend fun updateLocalPlaylistYouTubePlaylistId(
        id: Long,
        ytId: String?,
    ) = databaseDao.updateLocalPlaylistYouTubePlaylistId(id, ytId)

    suspend fun updateLocalPlaylistYouTubePlaylistSyncState(
        id: Long,
        syncState: Int,
    ) = databaseDao.updateLocalPlaylistYouTubePlaylistSyncState(id, syncState)

    fun getListTracksFlowOfLocalPlaylist(id: Long) = databaseDao.getListTracksFlowOfLocalPlaylist(id)

    suspend fun getSavedLyrics(videoId: String) = databaseDao.getLyrics(videoId)

    suspend fun insertLyrics(lyrics: LyricsEntity) = databaseDao.insertLyrics(lyrics)

    suspend fun deleteAllLyrics(): Int = databaseDao.deleteAllLyrics()

    suspend fun getPreparingSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getPreparingSongs(
        limit,
        offset,
    )

    suspend fun insertNewFormat(format: NewFormatEntity) = databaseDao.insertNewFormat(format)

    suspend fun getNewFormat(videoId: String) = databaseDao.getNewFormat(videoId)

    suspend fun updateNewFormat(newFormatEntity: NewFormatEntity) = databaseDao.updateNewFormat(newFormatEntity)

    suspend fun getNewFormatAsFlow(videoId: String) = databaseDao.getNewFormatAsFlow(videoId)

    suspend fun insertSongInfo(songInfo: SongInfoEntity) = databaseDao.insertSongInfo(songInfo)

    suspend fun getSongInfo(videoId: String) = databaseDao.getSongInfo(videoId)

    suspend fun recoverQueue(queueEntity: QueueEntity) = databaseDao.recoverQueue(queueEntity)

    suspend fun getQueue() = databaseDao.getQueue()

    suspend fun getLocalPlaylistByYoutubePlaylistId(playlistId: String) = databaseDao.getLocalPlaylistByYoutubePlaylistId(playlistId)

    suspend fun insertSetVideoId(setVideoIdEntity: SetVideoIdEntity) = databaseDao.insertSetVideoId(setVideoIdEntity)

    suspend fun getSetVideoId(
        videoId: String,
        youtubePlaylistIds: List<String>,
    ) = databaseDao.getSetVideoId(videoId, youtubePlaylistIds)

    suspend fun deleteSetVideoId(
        videoId: String,
        youtubePlaylistId: String,
    ) = databaseDao.deleteSetVideoId(videoId, youtubePlaylistId)

    suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist) =
        databaseDao.insertPairSongLocalPlaylist(pairSongLocalPlaylist)

    suspend fun unsyncLocalPlaylist(id: Long) = databaseDao.unsyncLocalPlaylist(id)

    suspend fun getPlaylistPairOfSong(
        videoId: String,
        localPlaylistId: Long,
    ) = databaseDao.getPlaylistPairOfSong(videoId, localPlaylistId)

    suspend fun getPlaylistPairSongByListPosition(
        playlistId: Long,
        listPosition: List<Int>,
    ) = databaseDao.getPlaylistPairSongByListPosition(playlistId, listPosition)

    suspend fun searchPlaylistPairSong(
        playlistId: Long,
        query: String,
        limit: Int,
    ) = databaseDao.searchPlaylistPairSong(
        playlistId = playlistId,
        query = "%" + query.escapeForLike() + "%",
        limit = limit,
    )

    suspend fun getPlaylistPairSongByOffset(
        playlistId: Long,
        offset: Int,
        filterState: FilterState,
    ) = if (filterState == FilterState.CustomOrder) {
        databaseDao.getPlaylistPairSongByOffset(
            playlistId,
            offset * 50,
        )
    } else if (filterState == FilterState.Title) {
        databaseDao.getPlaylistPairSongByTitle(
            playlistId,
            offset * 50,
        )
    } else {
        null
    }

    suspend fun getPlaylistPairSongByTime(
        playlistId: Long,
        filterState: FilterState,
        localDateTime: LocalDateTime,
    ) = if (filterState == FilterState.OlderFirst) {
        databaseDao.getPlaylistPairSongByOlderFirst(
            playlistId,
            localDateTime,
        )
    } else if (filterState == FilterState.NewerFirst) {
        databaseDao.getPlaylistPairSongByNewerFirst(
            playlistId,
            localDateTime,
        )
    } else {
        null
    }

    suspend fun getNewestPlaylistPairSong(playlistId: Long) = databaseDao.getNewestPlaylistPairSong(playlistId)

    suspend fun moveSongInPlaylist(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
    ) = databaseDao.moveSongInPlaylist(playlistId, fromIndex, toIndex)

    suspend fun getAllPlaylistPairSongByPosition(playlistId: Long) =
        databaseDao.getAllPlaylistPairSongByPosition(playlistId)

    suspend fun deletePairSongLocalPlaylist(
        playlistId: Long,
        videoId: String,
    ) = databaseDao.deletePairSongLocalPlaylist(playlistId, videoId)

    suspend fun getGoogleAccounts() = databaseDao.getAllGoogleAccount()

    suspend fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity) = databaseDao.insertGoogleAccount(googleAccountEntity)

    suspend fun getUsedGoogleAccount() = databaseDao.getUsedGoogleAccount()

    suspend fun deleteGoogleAccount(email: String) = databaseDao.deleteGoogleAccount(email)

    suspend fun updateGoogleAccountUsed(
        email: String,
        isUsed: Boolean,
    ) = databaseDao.updateGoogleAccountUsed(isUsed, email)

    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    ) = databaseDao.setInLibrary(videoId, inLibrary)

    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) =
        databaseDao.insertYourYouTubePlaylist(yourYouTubePlaylist)

    suspend fun getYourYouTubePlaylistList(
        emailPageId: String
    ): YourYouTubePlaylistList? =
        databaseDao.getYourYouTubePlaylistList(emailPageId)

    suspend fun deleteAllYourYouTubePlaylist() =
        databaseDao.deleteAllYourYouTubePlaylist()

}

private fun String.escapeForLike(): String =
    replace("\\", "\\\\")
        .replace("_", "\\_")
        .replace("%", "\\%")
