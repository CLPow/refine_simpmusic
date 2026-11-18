package com.maxrave.simpmusic.ui.screen.library

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.rememberCoroutineScope
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.ui.component.selection.SongSelectionOverlay
import com.maxrave.simpmusic.ui.component.selection.rememberSongSelectionState
import com.maxrave.simpmusic.ui.icon.PushPin
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import simpmusic.composeapp.generated.resources.ytm_pin
import simpmusic.composeapp.generated.resources.ytm_unpin
import simpmusic.composeapp.generated.resources.ytm_pinned
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmChips
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.KeyboardArrowDown
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.EpisodesForLaterDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.icon.Bookmark
import simpmusic.composeapp.generated.resources.podcast_episodes_for_later
import simpmusic.composeapp.generated.resources.podcast_library_empty
import simpmusic.composeapp.generated.resources.podcast_n_episodes
import simpmusic.composeapp.generated.resources.podcast_podcast
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.LibraryChip
import com.maxrave.simpmusic.viewModel.LibrarySort
import com.maxrave.simpmusic.viewModel.LibraryViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.maxrave.simpmusic.ui.icon.GridView
import com.maxrave.simpmusic.ui.icon.ViewList
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.create
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.title
import simpmusic.composeapp.generated.resources.ytm_artist
import simpmusic.composeapp.generated.resources.ytm_auto_playlist
import simpmusic.composeapp.generated.resources.ytm_downloads
import simpmusic.composeapp.generated.resources.ytm_grid_view
import simpmusic.composeapp.generated.resources.ytm_library_empty_albums
import simpmusic.composeapp.generated.resources.ytm_library_empty_artists
import simpmusic.composeapp.generated.resources.ytm_library_empty_downloads
import simpmusic.composeapp.generated.resources.ytm_library_empty_songs
import simpmusic.composeapp.generated.resources.ytm_liked_music
import simpmusic.composeapp.generated.resources.ytm_liked_songs
import simpmusic.composeapp.generated.resources.ytm_list_view
import simpmusic.composeapp.generated.resources.ytm_new
import simpmusic.composeapp.generated.resources.ytm_new_playlist

private data class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val round: Boolean = false,
    val songCount: Int? = null,
    val sortKey: Long = 0,
    val pinned: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun LibraryEntry.secondLine(): String {
    val count = songCount?.let { pluralStringResource(Res.plurals.n_song, it, it) }
    return listOfNotNull(subtitle.ifBlank { null }, count).joinToString(" • ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val chip by viewModel.chip.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val youTubePlaylists by viewModel.youTubePlaylists.collectAsStateWithLifecycle()
    val localPlaylists by viewModel.localPlaylists.collectAsStateWithLifecycle()
    val liked by viewModel.likedSongs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val episodesForLater by viewModel.episodesForLater.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<SongEntity?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    val dataStoreManager: DataStoreManager = koinInject()
    val pinnedKeys by dataStoreManager.pinnedLibraryKeys.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val selection = rememberSongSelectionState()
    LaunchedEffect(chip) { selection.exit() }
    val onPinChange: (LibraryEntry) -> Unit = { entry ->
        scope.launch { dataStoreManager.setLibraryPinned(entry.key, !entry.pinned) }
    }

    menuSong?.let { song ->
        NowPlayingBottomSheet(onDismiss = { menuSong = null }, navController = navController, song = song)
    }
    if (showNewPlaylist) {
        NewPlaylistDialog(onDismiss = { showNewPlaylist = false }) { title ->
            viewModel.createPlaylist(title)
            showNewPlaylist = false
        }
    }

    val likedMusic = stringResource(Res.string.ytm_liked_music)
    val autoPlaylist = stringResource(Res.string.ytm_auto_playlist)
    val playlistLabel = stringResource(Res.string.playlist)
    val albumLabel = stringResource(Res.string.album)
    val artistLabel = stringResource(Res.string.ytm_artist)
    val likedSongsName = stringResource(Res.string.ytm_liked_songs)
    val downloadsName = stringResource(Res.string.ytm_downloads)
    val podcastLabel = stringResource(Res.string.podcast_podcast)
    val laterName = stringResource(Res.string.podcast_episodes_for_later)
    val laterCount = pluralStringResource(Res.plurals.podcast_n_episodes, episodesForLater.size, episodesForLater.size)

    fun playSongs(
        songs: List<SongEntity>,
        start: SongEntity,
        name: String,
    ) {
        sharedViewModel.setQueueData(
            QueueData.Data(
                listTracks = songs.toArrayListTrack(),
                firstPlayedTrack = start.toTrack(),
                playlistId = null,
                playlistName = name,
                playlistType = PlaylistType.PLAYLIST,
                continuation = null,
            ),
        )
        sharedViewModel.loadMediaItem(start.toTrack(), Config.PLAYLIST_CLICK, songs.indexOf(start).coerceAtLeast(0))
    }

    val entries: List<LibraryEntry> =
        remember(chip, sort, loggedIn, youTubePlaylists, localPlaylists, liked, albums, artists, downloads, likedMusic, pinnedKeys, podcasts, episodesForLater, laterCount) {
            fun playlistEntries() =
                buildList {
                    add(
                        LibraryEntry(
                            key = "liked",
                            title = likedMusic,
                            subtitle = autoPlaylist,
                            thumbnail = null,
                            sortKey = Long.MAX_VALUE,
                        ) {
                            if (loggedIn) {
                                navController.navigate(PlaylistDestination(playlistId = "VLLM"))
                            } else {
                                navController.navigate(LibraryDynamicPlaylistDestination(type = "favorite"))
                            }
                        },
                    )
                    localPlaylists.forEach { p ->
                        add(
                            LibraryEntry(
                                key = "local-${p.id}",
                                title = p.title,
                                subtitle = playlistLabel,
                                songCount = p.tracks?.size ?: 0,
                                thumbnail = p.thumbnail,
                                sortKey = p.inLibrary.toEpochKey(),
                            ) { navController.navigate(LocalPlaylistDestination(id = p.id)) },
                        )
                    }
                    youTubePlaylists.orEmpty().filter { it.browseId != "VLLM" && it.browseId != "LM" }.forEach { p ->
                        add(
                            LibraryEntry(
                                key = "yt-${p.browseId}",
                                title = p.title,
                                subtitle = listOf(playlistLabel, p.author, p.itemCount).filter { it.isNotBlank() }.joinToString(" • "),
                                thumbnail = p.thumbnails.lastOrNull()?.url,
                            ) { navController.navigate(PlaylistDestination(playlistId = p.browseId, isYourYouTubePlaylist = true)) },
                        )
                    }
                }

            fun albumEntries() =
                albums.map { a ->
                    LibraryEntry(
                        key = "album-${a.browseId}",
                        title = a.title,
                        subtitle = listOfNotNull(a.type.ifBlank { albumLabel }, a.artistName?.joinToString(", "), a.year).filter { it.isNotBlank() }.joinToString(" • "),
                        thumbnail = a.thumbnails,
                        sortKey = (a.favoriteAt ?: a.inLibrary).toEpochKey(),
                    ) { navController.navigate(AlbumDestination(browseId = a.browseId)) }
                }

            fun artistEntries() =
                artists.map { a ->
                    LibraryEntry(
                        key = "artist-${a.channelId}",
                        title = a.name,
                        subtitle = artistLabel,
                        thumbnail = a.thumbnails,
                        round = true,
                        sortKey = (a.followedAt ?: a.inLibrary).toEpochKey(),
                    ) { navController.navigate(ArtistDestination(channelId = a.channelId)) }
                }

            fun podcastEntries(includeLater: Boolean) =
                buildList {
                    if (includeLater) {
                        add(
                            LibraryEntry(
                                key = LATER_KEY,
                                title = laterName,
                                subtitle = listOf(autoPlaylist, laterCount).joinToString(" • "),
                                thumbnail = null,
                                sortKey = Long.MAX_VALUE - 1,
                            ) { navController.navigate(EpisodesForLaterDestination) },
                        )
                    }
                    podcasts.forEach { p ->
                        add(
                            LibraryEntry(
                                key = "podcast-${p.podcastId}",
                                title = p.title,
                                subtitle = listOf(podcastLabel, p.authorName).filter { it.isNotBlank() }.joinToString(" • "),
                                thumbnail = p.thumbnail,
                                sortKey = p.savedAt.millisToEpochKey(),
                            ) { navController.navigate(PodcastDestination(podcastId = p.podcastId)) },
                        )
                    }
                }

            fun songEntries(
                songs: List<SongEntity>,
                name: String,
                added: (SongEntity) -> Long,
            ) = songs.map { s ->
                LibraryEntry(
                    key = s.videoId,
                    title = s.title,
                    subtitle = s.artistName?.joinToString(", ").orEmpty(),
                    thumbnail = s.thumbnails,
                    sortKey = added(s),
                ) { playSongs(songs, s, name) }
            }

            val list =
                when (chip) {
                    null -> playlistEntries() + albumEntries() + artistEntries() + podcastEntries(episodesForLater.isNotEmpty())
                    LibraryChip.Playlists -> playlistEntries()
                    LibraryChip.Songs -> songEntries(liked, likedSongsName) { (it.favoriteAt ?: it.inLibrary).toEpochKey() }
                    LibraryChip.Albums -> albumEntries()
                    LibraryChip.Artists -> artistEntries()
                    LibraryChip.Podcasts -> podcastEntries(true)
                    LibraryChip.Downloads -> songEntries(downloads, downloadsName) { (it.downloadedAt ?: it.inLibrary).toEpochKey() }
                }
            val sorted =
                when (sort) {
                    LibrarySort.RecentActivity, LibrarySort.RecentlyAdded -> list.sortedByDescending { it.sortKey }
                    LibrarySort.Alphabetical -> list.sortedBy { if (it.key == "liked") "" else it.title.lowercase() }
                }
            val (likedMusicEntry, rest) = sorted.partition { it.key == "liked" }
            val (pinned, others) = rest.partition { it.key in pinnedKeys }
            likedMusicEntry + pinned.map { it.copy(pinned = true) } + others
        }
    val songChip = chip == LibraryChip.Songs || chip == LibraryChip.Downloads
    val useGrid = grid && !songChip
    val songsById =
        remember(chip, liked, downloads) {
            (if (chip == LibraryChip.Songs) liked else downloads).associateBy { it.videoId }
        }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        Column(Modifier.fillMaxSize()) {
            if (songChip && selection.isActive) {
                SongSelectionOverlay(selection, entries.mapNotNull { songsById[it.key]?.toTrack() })
            } else {
                YtmTopBar(navController = navController)
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { viewModel.refreshYouTubePlaylists() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = if (useGrid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (useGrid) 12.dp else 0.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (useGrid) 16.dp else 0.dp),
                ) {
                    item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                        YtmChips(
                            labels = LibraryChip.entries.map { stringResource(it.label) },
                            selected = chip?.ordinal,
                            onSelect = { viewModel.setChip(LibraryChip.entries[it]) },
                            contentPadding = PaddingValues(horizontal = if (useGrid) 0.dp else 16.dp),
                            modifier = Modifier,
                        )
                    }
                    item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
                        SortRow(
                            sort = sort,
                            onSort = viewModel::setSort,
                            grid = grid,
                            showGridToggle = !songChip,
                            onToggleGrid = viewModel::toggleGrid,
                            horizontalPadding = if (useGrid) 0.dp else 16.dp,
                        )
                    }
                    if (entries.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                when (chip) {
                                    LibraryChip.Songs -> stringResource(Res.string.ytm_library_empty_songs)
                                    LibraryChip.Albums -> stringResource(Res.string.ytm_library_empty_albums)
                                    LibraryChip.Artists -> stringResource(Res.string.ytm_library_empty_artists)
                                    LibraryChip.Podcasts -> stringResource(Res.string.podcast_library_empty)
                                    LibraryChip.Downloads -> stringResource(Res.string.ytm_library_empty_downloads)
                                    LibraryChip.Playlists, null -> ""
                                },
                                style = typo().bodyLarge,
                                color = Ytm.TextSecondary,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        if (useGrid) {
                            GridTile(entry, onPinChange)
                        } else if (songChip) {
                            YtmSongRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                thumbnail = entry.thumbnail,
                                onClick = { if (selection.isActive) selection.toggle(entry.key) else entry.onClick() },
                                onMore = { menuSong = songsById[entry.key] },
                                onLongClick = { selection.start(entry.key) },
                                onSwipeQueue = { songsById[entry.key]?.let { sharedViewModel.addSongToQueue(it.toTrack()) } },
                                selected = if (selection.isActive) selection.isSelected(entry.key) else null,
                            )
                        } else {
                            ListRow(entry, onPinChange)
                        }
                    }
                }
            }
        }
        if (chip == null || chip == LibraryChip.Playlists) {
            NewButton(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { showNewPlaylist = true }
        }
    }
}

@Composable
private fun SortRow(
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    grid: Boolean,
    showGridToggle: Boolean,
    onToggleGrid: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = horizontalPadding, end = (horizontalPadding - 8.dp).coerceAtLeast(0.dp), bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.DropdownList) { open = true }
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(sort.label), style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White)
                Icon(SimpIcons.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ytm.Surface) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.label), color = Color.White) },
                        onClick = {
                            onSort(option)
                            open = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (showGridToggle) {
            IconButton(onClick = onToggleGrid) {
                Icon(
                    if (grid) SimpIcons.ViewList else SimpIcons.GridView,
                    contentDescription = stringResource(if (grid) Res.string.ytm_list_view else Res.string.ytm_grid_view),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun EntryThumb(
    entry: LibraryEntry,
    side: androidx.compose.ui.unit.Dp,
    shape: Shape,
) {
    if (entry.key == "liked") {
        Box(
            Modifier.size(side).clip(shape).background(Color(0xFF6B4CDA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SimpIcons.ThumbUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(side * 0.45f))
        }
    } else if (entry.key == LATER_KEY) {
        Box(
            Modifier.size(side).clip(shape).background(Color(0xFF2E5C8A)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SimpIcons.Bookmark, contentDescription = null, tint = Color.White, modifier = Modifier.size(side * 0.45f))
        }
    } else if (entry.thumbnail == null) {
        Box(Modifier.size(side).clip(shape).background(Ytm.Raised), contentAlignment = Alignment.Center) {
            Icon(SimpIcons.LibraryMusic, contentDescription = null, tint = Ytm.TextSecondary, modifier = Modifier.size(side * 0.4f))
        }
    } else {
        Thumb(entry.thumbnail, side, shape)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    entry: LibraryEntry,
    onPinChange: (LibraryEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = entry.onClick, onLongClick = { menu = true }.takeIf { entry.key != LIKED_KEY })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PinMenu(entry, menu, onDismiss = { menu = false }, onPinChange = onPinChange)
        EntryThumb(entry, 56.dp, if (entry.round) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SecondLine(entry, typo().bodyMedium)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    entry: LibraryEntry,
    onPinChange: (LibraryEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp).combinedClickable(onClick = entry.onClick, onLongClick = { menu = true }.takeIf { entry.key != LIKED_KEY })) {
        PinMenu(entry, menu, onDismiss = { menu = false }, onPinChange = onPinChange)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            EntryThumb(entry, maxWidth, if (entry.round) CircleShape else RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SecondLine(entry, typo().bodyMedium.copy(fontSize = 13.sp))
    }
}

@Composable
private fun SecondLine(
    entry: LibraryEntry,
    style: androidx.compose.ui.text.TextStyle,
) {
    val second = entry.secondLine()
    if (second.isBlank() && !entry.pinned) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (entry.pinned) {
            Icon(
                SimpIcons.PushPin,
                contentDescription = stringResource(Res.string.ytm_pinned),
                tint = Ytm.TextSecondary,
                modifier = Modifier.padding(end = 4.dp).size(14.dp),
            )
        }
        Text(second, style = style, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PinMenu(
    entry: LibraryEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPinChange: (LibraryEntry) -> Unit,
) {
    if (entry.key == LIKED_KEY) return
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Ytm.Surface) {
        DropdownMenuItem(
            text = { Text(stringResource(if (entry.pinned) Res.string.ytm_unpin else Res.string.ytm_pin), color = Color.White) },
            leadingIcon = { Icon(SimpIcons.PushPin, contentDescription = null, tint = Color.White) },
            onClick = {
                onDismiss()
                onPinChange(entry)
            },
        )
    }
}

@Composable
private fun NewButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SimpIcons.Add, contentDescription = null, tint = Color.Black)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.ytm_new), style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium), color = Color.Black)
    }
}

@Composable
internal fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = Ytm.Surface,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ytm_new_playlist), color = Color.White) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.title)) },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        focusedLabelColor = Color.White,
                        cursorColor = Color.White,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onCreate(title.trim()) }, enabled = title.isNotBlank()) {
                Text(stringResource(Res.string.create), color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
    )
}

private fun kotlinx.datetime.LocalDateTime.toEpochKey(): Long =
    ((((year * 12L + month.ordinal) * 31 + day) * 24 + hour) * 60 + minute) * 60 + second

private fun Long.millisToEpochKey(): Long =
    kotlin.time.Instant
        .fromEpochMilliseconds(this)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        .toEpochKey()

// Liked Music's entry: always first, never pinned.
private const val LIKED_KEY = "liked"

private const val LATER_KEY = "episodes-for-later"
