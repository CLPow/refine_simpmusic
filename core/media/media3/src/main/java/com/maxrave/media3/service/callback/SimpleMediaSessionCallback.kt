package com.maxrave.media3.service.callback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
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
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toMediaItem
import kotlinx.coroutines.CoroutineScope
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
) : MediaLibrarySession.Callback {
    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    /** Results of each recent search, by query; the host asks for them in a second call. */
    private val searchResults = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    /** Tracks of the YouTube playlist last browsed, so playback need not re-fetch it. */
    private val onlinePlaylistCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    /** Songs of the artist last browsed, so playback can build a queue without a second fetch. */
    private val artistSongCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    /** The Home shelves last served; replaced whole, never mutated, since browse runs on IO threads. */
    @Volatile
    private var listHomeItem: List<HomeItem> = emptyList()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Logger.w(TAG, "onConnect: ${controller.packageName}")
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                // Add custom commands
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
        Logger.w(TAG, "onDisconnected: ${controller.packageName}")
        super.onDisconnected(session, controller)
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        Logger.w(TAG, "Player Command $playerCommand")
        scope.launch {
            when (playerCommand) {
                COMMAND_SEEK_TO_NEXT -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)
                }
                COMMAND_SEEK_TO_PREVIOUS -> {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)
                }
                // Do NOT intercept COMMAND_SET_SHUFFLE_MODE / COMMAND_SET_REPEAT_MODE.
                // The session applies the host's requested mode onto player.shuffleModeEnabled /
                // player.repeatMode (forwarding adapter). Toggling here raced that write and
                // left the car UI's shuffle/repeat icons stuck.
                COMMAND_GET_TIMELINE -> {
                }
            }
        }
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
                toggleLike()
            }

            MEDIA_CUSTOM_COMMAND.REPEAT -> {
                // Toggle through the adapter so listeners fire and the host refreshes the icon.
                val player = session.player
                player.repeatMode =
                    when (player.repeatMode) {
                        ExoPlayer.REPEAT_MODE_OFF -> ExoPlayer.REPEAT_MODE_ONE
                        ExoPlayer.REPEAT_MODE_ONE -> ExoPlayer.REPEAT_MODE_ALL
                        else -> ExoPlayer.REPEAT_MODE_OFF
                    }
            }

            MEDIA_CUSTOM_COMMAND.RADIO -> {
                toggleRadio()
            }

            MEDIA_CUSTOM_COMMAND.SHUFFLE -> {
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
                            // A media root has to be browsable: it is what Android Auto opens.
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                // Declared once at the root: collections default to artwork tiles, tracks to
                // rows. Individual items override this where they differ.
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(
                        Bundle().apply {
                            putBoolean(CONTENT_STYLE_SUPPORTED, true)
                            // Home · Recent · Library as AA's tabs, opted in as YouTube Music does.
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
            val tracks =
                (searchRepository.getSearchDataSong(query).lastOrNull() as? Resource.Success)
                    ?.data
                    ?.toListTrack()
                    ?.filter { MusicVideoType.isPlayableSong(it.videoType) }
                    .orEmpty()
            searchResults[query] = tracks
            if (searchResults.size > 8) searchResults.keys.firstOrNull { it != query }?.let(searchResults::remove)
            session.notifySearchResultChanged(browser, query, tracks.size, params)
            LibraryResult.ofVoid()
        }

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
            val tracks = searchResults[query].orEmpty()
            val window = if (pageSize in 1 until Int.MAX_VALUE) tracks.drop(page * pageSize).take(pageSize) else tracks
            LibraryResult.ofItemList(window.map { it.toMediaItemWithoutPath() }, params)
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
                    // Three tabs, like YouTube Music's own car root (Home | Library, plus Recent
                    // here). Android Auto draws at most four; the old five-tab root lost one.
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
                            ),
                        )
                    }

                    LIBRARY -> {
                        listOf(
                            folderRow(FAVORITE, context.getString(R.string.favorites), R.drawable.baseline_favorite_24),
                            folderRow(DOWNLOADED, context.getString(R.string.downloaded), R.drawable.baseline_downloaded),
                            folderRow(PLAYLIST, context.getString(R.string.playlists), R.drawable.baseline_playlist_add_24),
                            folderRow("$HOME/$ARTIST", context.getString(R.string.your_artists), R.drawable.baseline_album_24),
                            folderRow(SONG, context.getString(R.string.songs), R.drawable.baseline_album_24),
                        )
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
                        // The account's own playlists, and the mixes YouTube builds from what it
                        // has heard. Both were missing entirely: this tab only ever listed
                        // playlists created inside the app, so a signed-in user saw nothing here.
                        // Failures degrade to the local list rather than emptying the tab.
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
                        // Bounded, never `while (continuation != null)`: YouTube's home feed keeps
                        // offering continuations, and the old unbounded loop fetched every one of
                        // them before the head unit saw a single row.
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
                        // One flat list, each shelf's items carrying the shelf title as their group
                        // — how YouTube Music lays out its car Home. Songs play straight from it;
                        // playlists and albums open one level down.
                        listHomeItem.flatMap { shelf ->
                            shelfItems(shelf)
                                .take(HOME_ITEMS_PER_SHELF)
                                .map { it.withGroupTitle(shelf.title) }
                        }
                    }

                    "$HOME/$RECENT" -> {
                        songRepository
                            .getRecentSong(RECENT_LIMIT, 0)
                            .map { it.toMediaItem(RECENT, asTile = true) }
                    }

                    "$HOME/$ARTIST" -> {
                        val followed = artistRepository.getFollowedArtists().first()
                        // Some artists carry no stored image — followed before the importer
                        // existed, or a subscription row that arrived without a thumbnail — and a
                        // tile with nothing to draw is the broken-image placeholder again.
                        // Backfilled here, but strictly bounded: an unbounded sweep over a long
                        // follow list is the same burst of requests that got the session
                        // throttled before. The rest fill in on later visits.
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
                            // A YouTube playlist or mix. Cached on the way through so pressing a
                            // track does not have to fetch the whole playlist a second time.
                            parentId.startsWith("$ONLINE_PLAYLIST/") -> {
                                val playlistId = parentId.substringAfter("$ONLINE_PLAYLIST/")
                                val tracks =
                                    playlistRepository
                                        .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                        .lastOrNull()
                                        ?.data
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

                            // An artist tile opens their songs. Cached on the way through so
                            // playback does not have to re-fetch the artist page to build a queue.
                            parentId.startsWith("$ARTIST/") -> {
                                val channelId = parentId.substringAfter("$ARTIST/")
                                val tracks =
                                    artistRepository
                                        .getArtistData(channelId)
                                        .lastOrNull()
                                        ?.data
                                        ?.songs
                                        ?.results
                                        ?.map { it.toTrack() }
                                        .orEmpty()
                                artistSongCache[channelId] = tracks
                                tracks.map { it.toMediaItemWithoutPath("$ARTIST/$channelId") }
                            }

                            parentId.startsWith("$HOME/") -> {
                                if (parentId.split("/").size == 2) {
                                    // A whole shelf, from an older head-unit cache that still
                                    // holds the previous two-level layout.
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
            // Media3 hands the host's page window through; honour it instead of answering every
            // request with the whole list (1 000 songs in one Binder transaction).
            val paged =
                if (pageSize in 1 until Int.MAX_VALUE) {
                    children.drop(page * pageSize).take(pageSize)
                } else {
                    children
                }
            return@future LibraryResult.ofItemList(paged, libraryParams)
        }

    /**
     * The browse items of one Home shelf, songs-only. Songs are playable rows; playlists and
     * albums are browsable tiles keyed by their own id.
     *
     * Emptiness matters, not just nullity: the home parser gives an album `playlistId = null` and
     * `videoId = ""`. Before albums were routed by browseId every album in a shelf got the same
     * media id, and the head unit — which keys items by id — drew one cover for all of them.
     */
    private fun shelfItems(shelf: HomeItem): List<MediaItem> {
        val shelfKey = Uri.encode(shelf.title)
        return shelf.contents.filterNotNull().mapNotNull { content ->
            when {
                !content.playlistId.isNullOrEmpty() ->
                    browsableMediaItem(
                        id = "$HOME/$shelfKey/$PLAYLIST/${content.playlistId}",
                        title = content.title,
                        subtitle = content.description,
                        iconUri = content.thumbnails.lastOrNull()?.url?.asSquareArtwork()?.toUri(),
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )

                !content.videoId.isNullOrEmpty() ->
                    if (MusicVideoType.isPlayableSong(content.videoType)) {
                        content
                            .toTrack()
                            .toSongEntity()
                            .toMediaItem("$HOME/$shelfKey/$SONG", asTile = true)
                    } else {
                        null
                    }

                !content.browseId.isNullOrEmpty() ->
                    browsableMediaItem(
                        id = "$HOME/$shelfKey/$PLAYLIST/${content.browseId}",
                        title = content.title,
                        // The artist, not album.name — the parser sets that to the shelf's title.
                        subtitle = content.artists?.joinToString(", ") { a -> a.name }?.takeIf { n -> n.isNotBlank() },
                        iconUri = content.thumbnails.lastOrNull()?.url?.asSquareArtwork()?.toUri(),
                        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                    )

                else -> null
            }
        }
    }

    /** Marks [this] as part of a titled section in a flat list (Android Auto groups consecutive items). */
    private fun MediaItem.withGroupTitle(title: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(CONTENT_STYLE_GROUP_TITLE_HINT, title) }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    /** A plain list row that opens a library folder. */
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
                .setExtras(contentStyle(browsable = CONTENT_STYLE_LIST_ITEM))
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

    @UnstableApi
    /**
     * The queue as [mediaPlayerHandler] has just staged it on the player.
     *
     * Every branch below used to hand back an empty list. media3 applies this result to the player
     * *after* the future resolves, so an empty list wiped the queue `loadMediaItem` had just built:
     * the `play()` that Android Auto sends straight after then ran against an empty timeline and
     * nothing was heard. Skipping to the next track repopulated the queue through the handler's own
     * path, which is why sound only ever arrived one track late.
     *
     * Handing back what is already on the player makes media3 re-apply the same queue — a no-op
     * instead of a wipe. Error paths keep returning the empty result, since nothing was staged.
     *
     * Safe to touch the player here: the service scope is [Dispatchers.Main], the app thread.
     */
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
            // Play from Android Auto
            val defaultResult =
                MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val path =
                mediaItems.firstOrNull()?.mediaId?.split("/")
                    ?: return@future defaultResult
            when (path.firstOrNull()) {
                SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val firstQueue = songRepository.getSongById(songId).first()?.toTrack() ?: return@future defaultResult
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = arrayListOf(firstQueue),
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

                ONLINE_PLAYLIST -> {
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val tracks =
                        onlinePlaylistCache[playlistId]
                            ?: playlistRepository
                                .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                .lastOrNull()
                                ?.data
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
                    val recent = songRepository.getRecentSong(RECENT_LIMIT, 0)
                    val clicked = recent.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                    val index = recent.indexOf(clicked)
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = recent.toArrayListTrack(),
                            firstPlayedTrack = clicked.toTrack(),
                            playlistId = RECENT,
                            playlistName = context.getString(R.string.recently_played),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(clicked.toTrack(), Config.PLAYLIST_CLICK, index)
                    stagedQueue(mediaSession, defaultResult)
                }

                ARTIST -> {
                    val channelId = path.getOrNull(1) ?: return@future defaultResult
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    // Populated while browsing the artist; re-fetched only if the session was
                    // restarted between browsing and pressing play.
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
                            // isNullOrEmpty, not != null: the home parser gives albums and
                            // artists videoId = "", which is non-null and would otherwise be
                            // treated as a playable track here.
                            content
                                ?.filter { !it?.videoId.isNullOrEmpty() && MusicVideoType.isPlayableSong(it?.videoType) }
                                ?.mapNotNull { it?.toTrack() }
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
                // A tile is a picture with a caption; asking for one without artwork gets a
                // broken-image placeholder, which is worse than the row it replaced. Nodes that
                // have no icon stay rows.
                .setExtras(
                    contentStyle(
                        browsable = if (iconUri != null) CONTENT_STYLE_GRID_ITEM else CONTENT_STYLE_LIST_ITEM,
                    ),
                ).build(),
        ).build()

    /**
     * [asTile] renders the track as an artwork tile rather than a row. Used for the top-level
     * tabs, where there is nothing to read your way through and a cover is recognised faster;
     * the contents of a playlist or album stay rows, because there the point is picking a
     * specific track out of an order.
     */
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

    /**
     * Rewrites a Google image URL to a square centre crop.
     *
     * Artist photos come back at whatever aspect ratio the channel art happens to be — some
     * portrait, some 16:9 — so a grid built from them looks ragged. The size suffix is a
     * server-side transform, so asking for a square costs nothing: no download, no scaling here.
     *
     * The trailing `-dc<token>` must go. It is bound to the original rendition, and changing the
     * size while it is present makes the server answer 400 — measured, not assumed. That is why a
     * handful of artists lost their images when this transform was first added while the rest kept
     * theirs. Dropping the token, the resized URL serves normally.
     *
     * `-c` is crop; `-p` (pad) would letterbox, which is the ragged look again with bars. The
     * optional group swallows whichever fit flag is already there rather than stacking a second
     * one, since `-c-p` is contradictory and also rejected.
     */
    private fun String.asSquareArtwork(size: Int = ARTWORK_SQUARE_PX): String {
        if (!contains("googleusercontent.com") && !contains("ggpht.com")) return this
        val base = replace(RENDITION_TOKEN_SUFFIX, "")
        return when {
            WIDTH_HEIGHT_SUFFIX.containsMatchIn(base) -> base.replace(WIDTH_HEIGHT_SUFFIX, "=w$size-h$size-c")
            SIZE_SUFFIX.containsMatchIn(base) -> base.replace(SIZE_SUFFIX, "=s$size")
            else -> base
        }
    }

    /** Per-item override of the root's default browse styling. */
    private fun contentStyle(
        browsable: Int? = null,
        playable: Int? = null,
    ) = Bundle().apply {
        browsable?.let { putInt(CONTENT_STYLE_BROWSABLE_HINT, it) }
        playable?.let { putInt(CONTENT_STYLE_PLAYABLE_HINT, it) }
    }

    private fun Track.toMediaItemWithoutPath(path: String? = SONG) =
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
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val HOME = "home"

        /**
         * Android Auto's classic browse surface picks list-or-grid from these extras. Without
         * them the host has nothing to go on and renders every level as a plain text list, which
         * is what SimpMusic showed however much artwork the items carried.
         *
         * Names and values are fixed by the platform contract, not by us.
         */
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val AUTO_TABS_OPT_IN_HINT = "android.media.browse.AUTO_TABS_OPT_IN_HINT"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM = 1
        const val CONTENT_STYLE_GRID_ITEM = 2

        const val LIBRARY = "library"

        /** Home feed continuation pages fetched per browse, on top of the first page. */
        private const val HOME_CONTINUATION_PAGES = 2

        /** Items shown per Home shelf; the rest are a scroll too far while driving. */
        private const val HOME_ITEMS_PER_SHELF = 10

        /**
         * Synthetic Home shelves, prefixed so they cannot collide with a YouTube shelf that
         * happens to be titled "recent" or "artist" — the generic handler below matches shelves
         * by their display title.
         */
        const val RECENT = "__recent"

        /** Rows the Recently played shelf offers; the car host trims further to its own budget. */
        private const val RECENT_LIMIT = 50

        /** Square artwork edge requested from Google's image server for browse tiles. */
        /** Artists whose missing image is fetched per browse; bounded to avoid a request burst. */
        private const val ARTWORK_BACKFILL_PER_BROWSE = 5

        /** Square artwork edge requested from Google's image server for browse tiles. */
        private const val ARTWORK_SQUARE_PX = 544

        /** Google image size suffixes: `=w1200-h675-p-...` and `=s176-...`. */
        private val WIDTH_HEIGHT_SUFFIX = Regex("""=w\d+-h\d+(-[pc])?""")
        private val SIZE_SUFFIX = Regex("""=s\d+""")

        /** Trailing `-dc<token>`, valid only for the rendition it came with. */
        private val RENDITION_TOKEN_SUFFIX = Regex("""-dc[A-Za-z0-9]+$""")

        /** Google image size suffixes: `=w1200-h675-...` and `=s176-...`. */
        const val ARTIST = "__artist"
        const val ONLINE_PLAYLIST = "online_playlist"
        const val PLAYLIST = "playlist"
        const val FAVORITE = "favorite"
        const val DOWNLOADED = "downloaded"
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}