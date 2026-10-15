package com.maxrave.simpmusic.ui.screen.library

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
import com.maxrave.simpmusic.ui.icon.QueueMusic
import com.maxrave.simpmusic.ui.icon.SelectAll
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.LibraryChip
import com.maxrave.simpmusic.viewModel.LibrarySort
import com.maxrave.simpmusic.viewModel.LibraryViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** One row/tile of the Library, whatever kind of thing it stands for. */
private data class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val round: Boolean = false,
    val sortKey: Long = 0,
    val onClick: () -> Unit,
)

/**
 * YouTube Music's Library: the logo bar, chips (Playlists · Songs · Albums · Artists ·
 * Downloads), a sort menu with the list/grid switch, then the items. Playlists starts with Liked
 * music, and carries the white "New" button for a new playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavController,
    onScrolling: (onTop: Boolean) -> Unit = {},
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
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<SongEntity?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }

    menuSong?.let { song ->
        NowPlayingBottomSheet(onDismiss = { menuSong = null }, navController = navController, song = song)
    }
    if (showNewPlaylist) {
        NewPlaylistDialog(onDismiss = { showNewPlaylist = false }) { title ->
            viewModel.createPlaylist(title)
            showNewPlaylist = false
        }
    }

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
        when (chip) {
            LibraryChip.Playlists ->
                buildList {
                    add(
                        LibraryEntry(
                            key = "liked",
                            title = "Liked music",
                            subtitle = "Auto playlist",
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
                                subtitle = "Playlist • ${p.tracks?.size ?: 0} songs",
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
                                subtitle = listOf("Playlist", p.author, p.itemCount).filter { it.isNotBlank() }.joinToString(" • "),
                                thumbnail = p.thumbnails.lastOrNull()?.url,
                            ) { navController.navigate(PlaylistDestination(playlistId = p.browseId, isYourYouTubePlaylist = true)) },
                        )
                    }
                }
            LibraryChip.Songs ->
                liked.map { s ->
                    LibraryEntry(
                        key = s.videoId,
                        title = s.title,
                        subtitle = s.artistName?.joinToString(", ").orEmpty(),
                        thumbnail = s.thumbnails,
                        sortKey = (s.favoriteAt ?: s.inLibrary).toEpochKey(),
                    ) { playSongs(liked, s, "Liked songs") }
                }
            LibraryChip.Albums ->
                albums.map { a ->
                    LibraryEntry(
                        key = a.browseId,
                        title = a.title,
                        subtitle = listOfNotNull(a.type.ifBlank { "Album" }, a.artistName?.joinToString(", "), a.year).filter { it.isNotBlank() }.joinToString(" • "),
                        thumbnail = a.thumbnails,
                        sortKey = (a.favoriteAt ?: a.inLibrary).toEpochKey(),
                    ) { navController.navigate(AlbumDestination(browseId = a.browseId)) }
                }
            LibraryChip.Artists ->
                artists.map { a ->
                    LibraryEntry(
                        key = a.channelId,
                        title = a.name,
                        subtitle = "Artist",
                        thumbnail = a.thumbnails,
                        round = true,
                        sortKey = (a.followedAt ?: a.inLibrary).toEpochKey(),
                    ) { navController.navigate(ArtistDestination(channelId = a.channelId)) }
                }
            LibraryChip.Downloads ->
                downloads.map { s ->
                    LibraryEntry(
                        key = s.videoId,
                        title = s.title,
                        subtitle = s.artistName?.joinToString(", ").orEmpty(),
                        thumbnail = s.thumbnails,
                        sortKey = (s.downloadedAt ?: s.inLibrary).toEpochKey(),
                    ) { playSongs(downloads, s, "Downloads") }
                }
        }.let { list ->
            when (sort) {
                LibrarySort.RecentActivity -> list
                LibrarySort.RecentlyAdded -> list.sortedByDescending { it.sortKey }
                LibrarySort.Alphabetical -> list.sortedBy { it.title.lowercase() }
            }
        }
    val songChip = chip == LibraryChip.Songs || chip == LibraryChip.Downloads
    val useGrid = grid && !songChip

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        Column(Modifier.fillMaxSize()) {
            YtmTopBar(navController = navController)
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
                            labels = LibraryChip.entries.map { it.label },
                            selected = chip.ordinal,
                            onSelect = { viewModel.setChip(LibraryChip.entries[it]) },
                            contentPadding = PaddingValues(horizontal = if (useGrid) 0.dp else 16.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
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
                                    LibraryChip.Songs -> "Songs you like will show up here"
                                    LibraryChip.Albums -> "Albums you save will show up here"
                                    LibraryChip.Artists -> "Artists you subscribe to will show up here"
                                    LibraryChip.Downloads -> "Songs you download will show up here"
                                    LibraryChip.Playlists -> ""
                                },
                                style = typo().bodyLarge,
                                color = Ytm.TextSecondary,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        if (useGrid) {
                            GridTile(entry)
                        } else if (songChip) {
                            val song = (if (chip == LibraryChip.Songs) liked else downloads).firstOrNull { it.videoId == entry.key }
                            YtmSongRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                thumbnail = entry.thumbnail,
                                onClick = entry.onClick,
                                onMore = { menuSong = song },
                            )
                        } else {
                            ListRow(entry)
                        }
                    }
                }
            }
        }
        if (chip == LibraryChip.Playlists) {
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
        Modifier.fillMaxWidth().padding(start = horizontalPadding, end = horizontalPadding - 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sort.label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White)
                Icon(SimpIcons.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ytm.Surface) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = Color.White) },
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
                    if (grid) SimpIcons.QueueMusic else SimpIcons.SelectAll,
                    contentDescription = if (grid) "List" else "Grid",
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
    } else if (entry.thumbnail == null) {
        Box(Modifier.size(side).clip(shape).background(Ytm.Raised), contentAlignment = Alignment.Center) {
            Icon(SimpIcons.LibraryMusic, contentDescription = null, tint = Ytm.TextSecondary, modifier = Modifier.size(side * 0.4f))
        }
    } else {
        Thumb(entry.thumbnail, side, shape)
    }
}

@Composable
private fun ListRow(entry: LibraryEntry) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = entry.onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryThumb(entry, 56.dp, if (entry.round) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) {
                Text(entry.subtitle, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GridTile(entry: LibraryEntry) {
    Column(Modifier.padding(vertical = 8.dp).clickable(onClick = entry.onClick)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            EntryThumb(entry, maxWidth, if (entry.round) CircleShape else RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.subtitle.isNotBlank()) {
            Text(entry.subtitle, style = typo().bodyMedium.copy(fontSize = 13.sp), color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** YouTube Music's white "New" pill, for a new playlist. */
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
        Text("New", style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium), color = Color.Black)
    }
}

@Composable
private fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = Ytm.Surface,
        onDismissRequest = onDismiss,
        title = { Text("New playlist", color = Color.White) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
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
                Text("Create", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ytm.TextSecondary) } },
    )
}

private fun kotlinx.datetime.LocalDateTime.toEpochKey(): Long =
    ((((year * 12L + month.ordinal) * 31 + day) * 24 + hour) * 60 + minute) * 60 + second
