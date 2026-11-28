package com.maxrave.media3.service.callback

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.repository.AlbumRepository
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE
import androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.service.CarConnection
import com.maxrave.media3.extension.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

private const val TAG = "AndroidAuto"

@UnstableApi
internal class SimpleMediaSessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val homeRepository: HomeRepository,
    private val artistRepository: ArtistRepository,
    private val streamRepository: StreamRepository,
    private val albumRepository: AlbumRepository,
    dataStoreManager: DataStoreManager,
    private val carConnection: CarConnection,
) : MediaLibrarySession.Callback {
    private val externalStartAllowed =
        dataStoreManager.allowExternalStartPlayback.stateIn(scope, SharingStarted.Eagerly, true)

    @Volatile private var listeningSession = false

    init {
        scope.launch { mediaPlayerHandler.controlState.collect { if (it.isPlaying) listeningSession = true } }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    listeningSession = false
                }
            },
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    private val searchResults = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()

    private val onlinePlaylistCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    private val artistSongCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    private val albumCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    @Volatile
    private var recentTracks: List<Track> = emptyList()

    // The Home shelves last served; replaced whole, never mutated, since browse runs on IO threads.
    @Volatile
    private var listHomeItem: List<HomeItem> = emptyList()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Logger.w(TAG, "onConnect: ${controller.packageName}")
        if (controller.isCar(session)) carConnection.refresh()
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle()))
                .build()
        return MediaSession.ConnectionResult
            .AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .remove(COMMAND_GET_TIMELINE)
                    .build(),
            ).build()
    }

    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        if (controller.isCar(session)) carConnection.refresh()
    }

    private fun MediaSession.ControllerInfo.isCar(session: MediaSession): Boolean =
        packageName == ANDROID_AUTO_PACKAGE || session.isAutoCompanionController(this) || session.isAutomotiveController(this)

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        Logger.d(TAG, "Player Command $playerCommand")
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    @UnstableApi
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MEDIA_CUSTOM_COMMAND.LIKE -> {
                (session.player as? com.maxrave.media3.service.RemoteSessionPlayer)?.toggleLike() ?: toggleLike()
            }

            MEDIA_CUSTOM_COMMAND.REPEAT -> {
                val player = session.player
                player.repeatMode =
                    when (player.repeatMode) {
                        ExoPlayer.REPEAT_MODE_OFF -> ExoPlayer.REPEAT_MODE_ONE
                        ExoPlayer.REPEAT_MODE_ONE -> ExoPlayer.REPEAT_MODE_ALL
                        else -> ExoPlayer.REPEAT_MODE_OFF
                    }
            }

            MEDIA_CUSTOM_COMMAND.RADIO -> {
                if (session.player !is com.maxrave.media3.service.RemoteSessionPlayer) toggleRadio()
            }

            MEDIA_CUSTOM_COMMAND.SHUFFLE -> {
                (session.player as? com.maxrave.media3.service.RemoteSessionPlayer)?.let {
                    it.toggleShuffle()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }

            MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN -> {
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putParcelable(MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN, session.platformToken)
                        },
                    ),
                )
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Logger.w(TAG, "onPlaybackResumption: ${controller.packageName}, isForPlayback=$isForPlayback")
        if (isForPlayback && externalStartAllowed.value) resumeSavedQueue()
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
        )
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val player = session.player
        val nothingLoaded = player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE
        if (!externalStartAllowed.value &&
            !session.isMediaNotificationController(controllerInfo) &&
            !player.isPlaying &&
            (nothingLoaded || !listeningSession)
        ) {
            val keyCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)?.keyCode
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)?.keyCode
                }
            if (keyCode in PLAY_KEYS) {
                Logger.w(TAG, "Ignoring external play key $keyCode from ${controllerInfo.packageName}")
                return true
            }
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    private fun resumeSavedQueue() {
        scope.launch {
            if (!mediaPlayerHandler.restoreQueueAndPlay()) {
                Logger.w(TAG, "onPlaybackResumption: nothing to resume")
            }
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(
                        Bundle().apply {
                            putBoolean(MEDIA_SEARCH_SUPPORTED, true)
                            putBoolean(CONTENT_STYLE_SUPPORTED, true)
                            putBoolean(AUTO_TABS_OPT_IN_HINT, true)
                            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM)
                            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM)
                        },
                    ).build(),
            ),
        )

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        scope.future(Dispatchers.IO) {
            val items = searchItems(query)
            searchResults[query] = items
            if (searchResults.size > 8) searchResults.keys.firstOrNull { it != query }?.let(searchResults::remove)
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        }

    private suspend fun searchSongs(
        query: String,
        limit: Int,
    ): List<Track> =
        (searchRepository.getSearchDataSong(query).lastOrNull() as? Resource.Success)
            ?.data
            ?.toListTrack()
            ?.filter { MusicVideoType.isPlayableSong(it.videoType) }
            .orEmpty()
            .take(limit)
            .onEach { songRepository.insertSong(it.toSongEntity()).first() }

    private suspend fun searchItems(query: String): List<MediaItem> =
        coroutineScope {
            val songs = async { searchSongs(query, SEARCH_SONGS) }
            val artists = async { (searchRepository.getSearchDataArtist(query).lastOrNull() as? Resource.Success)?.data.orEmpty() }
            val albums = async { (searchRepository.getSearchDataAlbum(query).lastOrNull() as? Resource.Success)?.data.orEmpty() }
            val playlists = async { (searchRepository.getSearchDataPlaylist(query).lastOrNull() as? Resource.Success)?.data.orEmpty() }
            val songsTitle = context.getString(R.string.car_songs)
            val artistsTitle = context.getString(R.string.car_artists)
            val albumsTitle = context.getString(R.string.albums)
            val playlistsTitle = context.getString(R.string.playlists)
            songs.await().map { it.toMediaItemWithoutPath().inSection(songsTitle, CONTENT_STYLE_LIST_ITEM) } +
                artists.await().take(SEARCH_COLLECTIONS).map {
                    browsableMediaItem("$ARTIST/${it.browseId}", it.artist, null, it.thumbnails.artwork(), MediaMetadata.MEDIA_TYPE_ARTIST)
                        .inSection(artistsTitle, CONTENT_STYLE_GRID_ITEM)
                } +
                albums.await().take(SEARCH_COLLECTIONS).map { album ->
                    browsableMediaItem(
                        "$ALBUM/${album.browseId}",
                        album.title,
                        album.artists.joinToString(", ") { it.name }.ifBlank { null },
                        album.thumbnails.artwork(),
                        MediaMetadata.MEDIA_TYPE_ALBUM,
                    ).inSection(albumsTitle, CONTENT_STYLE_GRID_ITEM)
                } +
                playlists.await().take(SEARCH_COLLECTIONS).map {
                    browsableMediaItem(
                        "$ONLINE_PLAYLIST/${it.browseId.removePrefix("VL")}",
                        it.title,
                        it.author.ifBlank { null },
                        it.thumbnails.artwork(),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ).inSection(playlistsTitle, CONTENT_STYLE_GRID_ITEM)
                }
        }

    private fun List<Thumbnail>.artwork(): Uri? = lastOrNull()?.url?.asSquareArtwork()?.toUri()

    @UnstableApi
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            val items = searchResults[query].orEmpty()
            val window = if (pageSize in 1 until Int.MAX_VALUE) items.drop(page * pageSize).take(pageSize) else items
            LibraryResult.ofItemList(window, params)
        }

    @UnstableApi
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            val rootExtras =
                Bundle().apply {
                    putBoolean(
                        MEDIA_SEARCH_SUPPORTED,
                        true,
                    )
                }
            val libraryParams =
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(rootExtras)
                    .build()
            val children: List<MediaItem> =
                when (parentId) {
                    ROOT -> {
                        listOf(
                            browsableMediaItem(
                                HOME,
                                context.getString(R.string.home),
                                null,
                                drawableUri(R.drawable.home_android_auto),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            browsableMediaItem(
                                "$HOME/$RECENT",
                                context.getString(R.string.recently_played),
                                null,
                                drawableUri(R.drawable.baseline_album_24),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                LIBRARY,
                                context.getString(R.string.library),
                                null,
                                drawableUri(R.drawable.baseline_playlist_add_24),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                childStyle = CONTENT_STYLE_LIST_ITEM,
                            ),
                        )
                    }

                    LIBRARY -> {
                        listOf(
                            folderRow(FAVORITE, context.getString(R.string.liked_music), R.drawable.baseline_favorite_24),
                            folderRow(DOWNLOADED, context.getString(R.string.downloaded), R.drawable.baseline_downloaded),
                            folderRow(PLAYLIST, context.getString(R.string.playlists), R.drawable.baseline_playlist_add_24),
                            folderRow(ALBUM, context.getString(R.string.albums), R.drawable.baseline_album_24),
                            folderRow("$HOME/$ARTIST", context.getString(R.string.your_artists), R.drawable.baseline_album_24),
                        )
                    }

                    ALBUM -> {
                        albumRepository
                            .getLikedAlbums()
                            .first()
                            .map { album ->
                                browsableMediaItem(
                                    id = "$ALBUM/${album.browseId}",
                                    title = album.title,
                                    subtitle = album.artistName?.joinToString(", "),
                                    iconUri = album.thumbnails?.asSquareArtwork()?.toUri(),
                                    mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                                )
                            }
                    }

                    SONG -> {
                        songRepository
                            .getAllSongs(1000)
                            .last()
                            .sortedBy { it.inLibrary }
                            .map { it.toMediaItem(parentId) }
                    }

                    FAVORITE -> {
                        songRepository
                            .getLikedSongs()
                            .first()
                            .map { it.toMediaItem(parentId, asTile = true) }
                    }

                    DOWNLOADED -> {
                        songRepository
                            .getDownloadedSongs()
                            .first()
                            ?.map { it.toMediaItem(parentId, asTile = true) }
                            ?: emptyList()
                    }

                    PLAYLIST -> {
                        val local =
                            localPlaylistRepository
                                .getAllLocalPlaylists()
                                .first()
                                .sortedBy { it.inLibrary }
                                .map {
                                    browsableMediaItem(
                                        "$PLAYLIST/${it.id}",
                                        it.title,
                                        "${it.tracks?.size ?: 0} ${context.getString(R.string.track)}",
                                        it.thumbnail?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                        val online =
                            runCatching {
                                (playlistRepository.getLibraryPlaylist().lastOrNull().orEmpty() +
                                    playlistRepository.getMixedForYou().lastOrNull().orEmpty())
                                    .distinctBy { it.browseId }
                                    .map { yt ->
                                        browsableMediaItem(
                                            "$ONLINE_PLAYLIST/${yt.browseId}",
                                            yt.title,
                                            yt.author.takeIf { a -> a.isNotBlank() } ?: yt.itemCount,
                                            yt.thumbnails
                                                .lastOrNull()
                                                ?.url
                                                ?.toUri(),
                                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                        )
                                    }
                            }.onFailure {
                                Logger.w(TAG, "Could not load YouTube playlists: ${it.message}")
                            }.getOrDefault(emptyList())
                        local + online
                    }

                    HOME -> {
                        val temp =
                            homeRepository
                                .getHomeData(
                                    viewString = context.getString(R.string.view_count),
                                    songString = context.getString(R.string.song),
                                ).lastOrNull()
                                ?.data
                        val shelves = ArrayList(temp?.second ?: emptyList())
                        var continueParam = temp?.first
                        var pages = 0
                        while (!continueParam.isNullOrEmpty() && pages < HOME_CONTINUATION_PAGES) {
                            val more =
                                homeRepository
                                    .getHomeDataContinue(
                                        continueParam,
                                        viewString = context.getString(R.string.view_count),
                                        songString = context.getString(R.string.song),
                                    ).lastOrNull()
                                    ?.data
                            shelves.addAll(more?.second ?: emptyList())
                            continueParam = more?.first
                            pages++
                        }
                        listHomeItem = shelves.filter { it.title.isNotBlank() }
                        listHomeItem.flatMap { shelf ->
                            shelfItems(shelf)
                                .take(HOME_ITEMS_PER_SHELF)
                                .map { it.withGroupTitle(shelf.title) }
                        }
                    }

                    "$HOME/$RECENT" -> {
                        val history =
                            (songRepository.getYouTubeHistory().lastOrNull() as? Resource.Success)
                                ?.data
                                ?.flatMap { it.second }
                                .orEmpty()
                                .distinctBy { it.videoId }
                                .take(RECENT_LIMIT)
                        recentTracks =
                            history.ifEmpty {
                                songRepository
                                    .getMostPlayedSongs()
                                    .first()
                                    .take(RECENT_LIMIT)
                                    .map { it.toTrack() }
                            }
                        recentTracks.map { it.toMediaItemWithoutPath(RECENT, asTile = true) }
                    }

                    "$HOME/$ARTIST" -> {
                        val followed = artistRepository.getFollowedArtists().first()
                        followed
                            .filter { it.thumbnails.isNullOrBlank() }
                            .take(ARTWORK_BACKFILL_PER_BROWSE)
                            .forEach { artist ->
                                runCatching {
                                    artistRepository
                                        .getArtistData(artist.channelId)
                                        .lastOrNull()
                                        ?.data
                                        ?.thumbnails
                                        ?.lastOrNull()
                                        ?.url
                                        ?.let { url -> artistRepository.updateArtistImage(artist.channelId, url) }
                                }.onFailure {
                                    Logger.w(TAG, "artwork backfill failed for ${artist.name}: ${it.message}")
                                }
                            }
                        artistRepository
                            .getFollowedArtists()
                            .first()
                            .map { artist ->
                                val uri = artist.thumbnails?.asSquareArtwork()?.toUri()
                                browsableMediaItem(
                                    id = "$ARTIST/${artist.channelId}",
                                    title = artist.name,
                                    subtitle = null,
                                    iconUri = uri,
                                    mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                                )
                            }
                    }

                    else -> {
                        when {
                            parentId.startsWith("$ONLINE_PLAYLIST/") -> {
                                val playlistId = parentId.substringAfter("$ONLINE_PLAYLIST/")
                                val tracks =
                                    playlistRepository
                                        .getPlaylistData(playlistId, context.getString(R.string.view_count))
                                        .lastOrNull()
                                        ?.data
                                        ?.first
                                        ?.tracks
                                        .orEmpty()
                                        .filter { MusicVideoType.isPlayableSong(it.videoType) }
                                onlinePlaylistCache[playlistId] = tracks
                                tracks.map { track ->
                                    track
                                        .toSongEntity()
                                        .also { songRepository.insertSong(it).first() }
                                        .toMediaItem("$ONLINE_PLAYLIST/$playlistId")
                                }
                            }

                            parentId.startsWith("$ALBUM/") -> {
                                val browseId = parentId.substringAfter("$ALBUM/")
                                val tracks =
                                    albumRepository
                                        .getAlbumData(browseId)
                                        .lastOrNull()
                                        ?.data
                                        ?.tracks
                                        .orEmpty()
                                albumCache[browseId] = tracks
                                tracks.map { it.toMediaItemWithoutPath("$ALBUM/$browseId") }
                            }

                            parentId.startsWith("$ARTIST/") -> {
                                val channelId = parentId.substringAfter("$ARTIST/")
                                val artist = artistRepository.getArtistData(channelId).lastOrNull()?.data
                                val tracks = artist?.songs?.results?.map { it.toTrack() }.orEmpty()
                                artistSongCache[channelId] = tracks
                                val songsTitle = context.getString(R.string.car_songs)
                                val albumsTitle = context.getString(R.string.albums)
                                val singlesTitle = context.getString(R.string.car_singles)
                                tracks.map { it.toMediaItemWithoutPath("$ARTIST/$channelId").inSection(songsTitle, CONTENT_STYLE_LIST_ITEM) } +
                                    artist?.albums?.results.orEmpty().map {
                                        browsableMediaItem("$ALBUM/${it.browseId}", it.title, it.year, it.thumbnails.artwork(), MediaMetadata.MEDIA_TYPE_ALBUM)
                                            .inSection(albumsTitle, CONTENT_STYLE_GRID_ITEM)
                                    } +
                                    artist?.singles?.results.orEmpty().map {
                                        browsableMediaItem("$ALBUM/${it.browseId}", it.title, it.year, it.thumbnails.artwork(), MediaMetadata.MEDIA_TYPE_ALBUM)
                                            .inSection(singlesTitle, CONTENT_STYLE_GRID_ITEM)
                                    }
                            }

                            parentId.startsWith("$HOME/") -> {
                                if (parentId.split("/").size == 2) {
                                    listHomeItem
                                        .find { it.title == Uri.decode(parentId.split("/")[1]) }
                                        ?.let { shelfItems(it) }
                                        .orEmpty()
                                } else {
                                    val playlistId = parentId.split("/").getOrNull(3)
                                    if (playlistId != null) {
                                        val playlist =
                                            playlistRepository
                                                .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                                .lastOrNull()
                                        if (playlist?.data?.tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            playlist.data?.toPlaylistEntity()?.let { playlistRepository.insertAndReplacePlaylist(it) }
                                            playlist.data?.tracks?.map { track ->
                                                track
                                                    .toSongEntity()
                                                    .also {
                                                        songRepository.insertSong(it).first()
                                                    }.toMediaItem(parentId)
                                            } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

                            parentId.startsWith("$PLAYLIST/") -> {
                                val playlistId = parentId.split("/").getOrNull(1)
                                if (playlistId != null) {
                                    val playlist =
                                        localPlaylistRepository.getLocalPlaylist(playlistId.toLong()).lastOrNull()?.data
                                    if (playlist != null) {
                                        Logger.w(TAG, "onGetChildren: $playlist")
                                        val tracks = playlist.tracks
                                        if (tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            songRepository
                                                .getSongsByListVideoId(tracks)
                                                .lastOrNull()
                                                ?.map { it.toMediaItem(parentId) } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                }
            val paged =
                if (pageSize in 1 until Int.MAX_VALUE) {
                    children.drop(page * pageSize).take(pageSize)
                } else {
                    children
                }
            return@future LibraryResult.ofItemList(paged, libraryParams)
        }

    private fun shelfItems(shelf: HomeItem): List<MediaItem> {
        val shelfKey = Uri.encode(shelf.title)
        return shelf.contents.filterNotNull().mapNotNull { content ->
            val art = content.thumbnails.lastOrNull()?.url?.asSquareArtwork()?.toUri()
            val browseId = content.browseId.orEmpty()
            val artists = content.artists?.joinToString(", ") { a -> a.name }?.takeIf { n -> n.isNotBlank() }
            when {
                !content.videoId.isNullOrEmpty() ->
                    if (MusicVideoType.isPlayableSong(content.videoType) || MusicVideoType.isOfficialVideo(content.videoType)) {
                        content
                            .toTrack()
                            .toSongEntity()
                            .toMediaItem("$HOME/$shelfKey/$SONG", asTile = true)
                    } else {
                        playableMediaItem("$RADIO/${content.videoId}", content.title, artists, art)
                    }

                browseId.startsWith("MPRE") || browseId.startsWith("OLAK") ->
                    browsableMediaItem("$ALBUM/$browseId", content.title, artists, art, MediaMetadata.MEDIA_TYPE_ALBUM)

                browseId.startsWith("UC") && content.playlistId.isNullOrEmpty() ->
                    browsableMediaItem("$ARTIST/$browseId", content.title, null, art, MediaMetadata.MEDIA_TYPE_ARTIST)

                !content.playlistId.isNullOrEmpty() || browseId.startsWith("VL") ->
                    browsableMediaItem(
                        "$ONLINE_PLAYLIST/${(content.playlistId ?: browseId).removePrefix("VL")}",
                        content.title,
                        content.description ?: artists,
                        art,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )

                else -> null
            }
        }
    }

    private fun playableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setExtras(contentStyle(playable = CONTENT_STYLE_GRID_ITEM))
                .build(),
        ).build()

    private fun MediaItem.withGroupTitle(title: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(CONTENT_STYLE_GROUP_TITLE_HINT, title) }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun MediaItem.inSection(
        title: String,
        style: Int,
    ): MediaItem {
        val extras =
            Bundle(mediaMetadata.extras ?: Bundle()).apply {
                putString(CONTENT_STYLE_GROUP_TITLE_HINT, title)
                putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, style)
            }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun folderRow(
        id: String,
        title: String,
        @DrawableRes icon: Int,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtworkUri(drawableUri(icon))
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(contentStyle(browsable = CONTENT_STYLE_GRID_ITEM, playable = CONTENT_STYLE_LIST_ITEM))
                .build(),
        ).build()

    @UnstableApi
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            songRepository.getSongById(mediaId).first()?.let {
                LibraryResult.ofItem(it.toMediaItem(), null)
            } ?: streamRepository.getFullMetadata(mediaId).lastOrNull()?.data?.let {
                LibraryResult.ofItem(it.toMediaItemWithoutPath(), null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    private suspend fun startSongRadio(track: Track) {
        mediaPlayerHandler.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${track.videoId}",
                playlistName = "\"${track.title}\" Radio",
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        mediaPlayerHandler.loadMediaItem(track, Config.SONG_CLICK, 0)
    }

    @UnstableApi
    private fun stagedQueue(
        mediaSession: MediaSession,
        fallback: MediaSession.MediaItemsWithStartPosition,
    ): MediaSession.MediaItemsWithStartPosition {
        val player = mediaSession.player
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        return if (items.isEmpty()) {
            fallback
        } else {
            MediaSession.MediaItemsWithStartPosition(
                items,
                player.currentMediaItemIndex,
                player.currentPosition,
            )
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
            val defaultResult =
                MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val spoken = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            if (!spoken.isNullOrBlank() && mediaItems.first().mediaId.isEmpty()) {
                val top = searchSongs(spoken, 1).firstOrNull() ?: return@future defaultResult
                startSongRadio(top)
                return@future stagedQueue(mediaSession, defaultResult)
            }
            val path =
                mediaItems.firstOrNull()?.mediaId?.split("/")
                    ?: return@future defaultResult
            when (path.firstOrNull()) {
                SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val firstQueue = songRepository.getSongById(songId).first()?.toTrack() ?: return@future defaultResult
                    startSongRadio(firstQueue)
                    stagedQueue(mediaSession, defaultResult)
                }

                ONLINE_PLAYLIST -> {
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val tracks =
                        onlinePlaylistCache[playlistId]
                            ?: playlistRepository
                                .getPlaylistData(playlistId, context.getString(R.string.view_count))
                                .lastOrNull()
                                ?.data
                                ?.first
                                ?.tracks
                                .orEmpty()
                                .filter { MusicVideoType.isPlayableSong(it.videoType) }
                                .also { onlinePlaylistCache[playlistId] = it }
                    val clicked = tracks.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(tracks),
                            firstPlayedTrack = clicked,
                            playlistId = playlistId,
                            playlistName = clicked.album?.name.orEmpty(),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(clicked, Config.PLAYLIST_CLICK, tracks.indexOf(clicked))
                    stagedQueue(mediaSession, defaultResult)
                }

                RECENT -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val recent = recentTracks
                    val clicked = recent.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                    val index = recent.indexOf(clicked)
                    recent.forEach { songRepository.insertSong(it.toSongEntity()).first() }
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(recent),
                            firstPlayedTrack = clicked,
                            playlistId = RECENT,
                            playlistName = context.getString(R.string.recently_played),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(clicked, Config.PLAYLIST_CLICK, index)
                    stagedQueue(mediaSession, defaultResult)
                }

                ALBUM -> {
                    val browseId = path.getOrNull(1) ?: return@future defaultResult
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val album = albumRepository.getAlbumData(browseId).lastOrNull()?.data
                    val tracks =
                        albumCache[browseId]
                            ?: album?.tracks.orEmpty().also { albumCache[browseId] = it }
                    val clicked = tracks.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                    tracks.forEach { songRepository.insertSong(it.toSongEntity()).first() }
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(tracks),
                            firstPlayedTrack = clicked,
                            playlistId = album?.audioPlaylistId ?: browseId,
                            playlistName = album?.title ?: clicked.album?.name.orEmpty(),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(clicked, Config.PLAYLIST_CLICK, tracks.indexOf(clicked))
                    stagedQueue(mediaSession, defaultResult)
                }

                RADIO -> {
                    val videoId = path.getOrNull(1) ?: return@future defaultResult
                    val radio =
                        (songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(videoId = videoId, playlistId = "RDAMVM$videoId")).lastOrNull() as? Resource.Success)
                            ?.data
                    val tracks = radio?.first.orEmpty()
                    val first = tracks.firstOrNull() ?: return@future defaultResult
                    tracks.forEach { songRepository.insertSong(it.toSongEntity()).first() }
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(tracks),
                            firstPlayedTrack = first,
                            playlistId = "RDAMVM$videoId",
                            playlistName = context.getString(R.string.radio),
                            playlistType = PlaylistType.RADIO,
                            continuation = radio?.second,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(first, Config.PLAYLIST_CLICK, 0)
                    stagedQueue(mediaSession, defaultResult)
                }

                ARTIST -> {
                    val channelId = path.getOrNull(1) ?: return@future defaultResult
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val tracks =
                        artistSongCache[channelId]
                            ?: artistRepository
                                .getArtistData(channelId)
                                .lastOrNull()
                                ?.data
                                ?.songs
                                ?.results
                                ?.map { it.toTrack() }
                                .orEmpty()
                                .also { artistSongCache[channelId] = it }
                    val clicked = tracks.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                    tracks.forEach { songRepository.insertSong(it.toSongEntity()).first() }
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(tracks),
                            firstPlayedTrack = clicked,
                            playlistId = channelId,
                            playlistName = clicked.artists?.firstOrNull()?.name.orEmpty(),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(clicked, Config.PLAYLIST_CLICK, tracks.indexOf(clicked))
                    stagedQueue(mediaSession, defaultResult)
                }

                FAVORITE -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val likedSongs = songRepository.getLikedSongs().first()
                    if (likedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            likedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = likedSongs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = likedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.favorites),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        stagedQueue(mediaSession, defaultResult)
                    }
                }

                DOWNLOADED -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val downloadedSongs = songRepository.getDownloadedSongs().first().orEmpty()
                    if (downloadedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            downloadedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = downloadedSongs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = downloadedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.downloaded),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        stagedQueue(mediaSession, defaultResult)
                    }
                }

                PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                    var title = ""
                    val songs =
                        localPlaylistRepository
                            .getLocalPlaylist(playlistId.toLong())
                            .lastOrNull()
                            ?.data
                            ?.also {
                                title = it.title
                            }?.tracks
                            ?.let {
                                songRepository.getSongsByListVideoId(it)
                            }?.lastOrNull()
                    Logger.w(TAG, "onSetMediaItems songs: $songs")
                    if (songs.isNullOrEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            songs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = songs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = songs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = playlistId,
                                playlistName = "${
                                    context.getString(
                                        R.string.playlists,
                                    )
                                } \"${title}\"",
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        stagedQueue(mediaSession, defaultResult)
                    }
                }

                HOME -> {
                    val type = path.getOrNull(2) ?: return@future defaultResult
                    val shelfTitle = path.getOrNull(1)?.let(Uri::decode)
                    val content = listHomeItem.find { it.title == shelfTitle }?.contents
                    if (type == SONG) {
                        val songId = path.getOrNull(3) ?: return@future defaultResult
                        val songs =
                            content
                                ?.filter {
                                    !it?.videoId.isNullOrEmpty() &&
                                        (MusicVideoType.isPlayableSong(it?.videoType) || MusicVideoType.isOfficialVideo(it?.videoType))
                                }?.mapNotNull { it?.toTrack() }
                        if (songs.isNullOrEmpty()) {
                            defaultResult
                        } else {
                            songs.forEach {
                                songRepository.insertSong(it.toSongEntity()).first()
                            }
                            val firstQueue = songs.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                            mediaPlayerHandler.setQueueData(
                                QueueData.Data(
                                    listTracks = songs,
                                    firstPlayedTrack = firstQueue,
                                    playlistId = "RDAMVM$songId",
                                    playlistName = "\"${firstQueue.title}\" Radio",
                                    playlistType = PlaylistType.RADIO,
                                    continuation = null,
                                ),
                            )
                            mediaPlayerHandler.loadMediaItem(
                                firstQueue,
                                Config.SONG_CLICK,
                                0,
                            )
                            stagedQueue(mediaSession, defaultResult)
                        }
                    } else if (type == PLAYLIST) {
                        val songId = path.getOrNull(4) ?: return@future defaultResult
                        val playlistId = path.getOrNull(3) ?: return@future defaultResult
                        Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                        val playlistEntity = playlistRepository.getPlaylist(playlistId).first()
                        Logger.w(TAG, "onSetMediaItems playlistEntity: $playlistEntity")
                        val tracks = playlistEntity?.tracks
                        if (tracks.isNullOrEmpty()) {
                            defaultResult
                        } else if (tracks.isNotEmpty()) {
                            val songs =
                                tracks
                                    .let {
                                        songRepository
                                            .getSongsByListVideoId(tracks)
                                            .first()
                                            .sortedBy {
                                                tracks.indexOf(it.videoId)
                                            }.also {
                                                Logger.w(TAG, "onSetMediaItems list songs: $it")
                                            }
                                    }
                            var index = 0
                            val clickedSong =
                                songs
                                    .firstOrNull { it.videoId == songId }
                                    ?.also {
                                        index = songs.indexOf(it)
                                    }?.toTrack() ?: return@future defaultResult
                            mediaPlayerHandler.setQueueData(
                                QueueData.Data(
                                    listTracks = songs.toArrayListTrack(),
                                    firstPlayedTrack = clickedSong,
                                    playlistId = playlistId,
                                    playlistName = "${
                                        context.getString(
                                            R.string.playlists,
                                        )
                                    } \"${playlistEntity.title}\"",
                                    playlistType = PlaylistType.LOCAL_PLAYLIST,
                                    continuation = null,
                                ),
                            )
                            mediaPlayerHandler.loadMediaItem(
                                clickedSong,
                                Config.PLAYLIST_CLICK,
                                index,
                            )
                            stagedQueue(mediaSession, defaultResult)
                        } else {
                            defaultResult
                        }
                    } else {
                        return@future defaultResult
                    }
                }

                else -> {
                    defaultResult
                }
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
        childStyle: Int? = null,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .setExtras(
                    contentStyle(
                        browsable = childStyle ?: if (iconUri != null) CONTENT_STYLE_GRID_ITEM else CONTENT_STYLE_LIST_ITEM,
                    ),
                ).build(),
        ).build()

    private fun SongEntity.toMediaItem(
        path: String,
        asTile: Boolean = false,
    ) = MediaItem
        .Builder()
        .setMediaId("$path/${this.videoId}")
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(this.title)
                .setSubtitle(this.artistName?.joinToString(", "))
                .setArtist(this.artistName?.joinToString(" "))
                .setArtworkUri(this.thumbnails?.asSquareArtwork()?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(
                    contentStyle(
                        playable = if (asTile) CONTENT_STYLE_GRID_ITEM else CONTENT_STYLE_LIST_ITEM,
                    ),
                ).build(),
        ).build()

    private fun String.asSquareArtwork(size: Int = ARTWORK_SQUARE_PX): String {
        if (!contains("googleusercontent.com") && !contains("ggpht.com")) return this
        val base = replace(RENDITION_TOKEN_SUFFIX, "")
        return when {
            WIDTH_HEIGHT_SUFFIX.containsMatchIn(base) -> base.replace(WIDTH_HEIGHT_SUFFIX, "=w$size-h$size-c")
            SIZE_SUFFIX.containsMatchIn(base) -> base.replace(SIZE_SUFFIX, "=s$size")
            else -> base
        }
    }

    private fun contentStyle(
        browsable: Int? = null,
        playable: Int? = null,
    ) = Bundle().apply {
        browsable?.let { putInt(CONTENT_STYLE_BROWSABLE_HINT, it) }
        playable?.let { putInt(CONTENT_STYLE_PLAYABLE_HINT, it) }
    }

    private fun Track.toMediaItemWithoutPath(
        path: String? = SONG,
        asTile: Boolean = false,
    ) =
        MediaItem
            .Builder()
            .setMediaId(if (path == null) this.videoId else "$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artists?.toListName()?.connectArtists())
                    .setArtist(this.artists?.toListName()?.connectArtists())
                    .setArtworkUri(
                        this.thumbnails
                            ?.lastOrNull()
                            ?.url
                            ?.asSquareArtwork()
                            ?.toUri(),
                    ).setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(contentStyle(playable = if (asTile) CONTENT_STYLE_GRID_ITEM else CONTENT_STYLE_LIST_ITEM))
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val HOME = "home"

        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val AUTO_TABS_OPT_IN_HINT = "android.media.browse.AUTO_TABS_OPT_IN_HINT"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val CONTENT_STYLE_SINGLE_ITEM_HINT = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        const val CONTENT_STYLE_LIST_ITEM = 1
        const val CONTENT_STYLE_GRID_ITEM = 2

        const val LIBRARY = "library"

        private const val HOME_CONTINUATION_PAGES = 2

        private const val HOME_ITEMS_PER_SHELF = 10

        const val RECENT = "__recent"

        private const val RECENT_LIMIT = 50

        private const val SEARCH_SONGS = 8

        private const val SEARCH_COLLECTIONS = 6

        private const val ARTWORK_BACKFILL_PER_BROWSE = 5

        private const val ARTWORK_SQUARE_PX = 544

        private val WIDTH_HEIGHT_SUFFIX = Regex("""=w\d+-h\d+(-[pc])?""")
        private val SIZE_SUFFIX = Regex("""=s\d+""")

        private val RENDITION_TOKEN_SUFFIX = Regex("""-dc[A-Za-z0-9]+$""")

        const val ARTIST = "__artist"
        const val ONLINE_PLAYLIST = "online_playlist"
        const val ALBUM = "album"
        const val RADIO = "radio"
        const val PLAYLIST = "playlist"
        const val FAVORITE = "favorite"
        const val DOWNLOADED = "downloaded"
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}

private val PLAY_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
