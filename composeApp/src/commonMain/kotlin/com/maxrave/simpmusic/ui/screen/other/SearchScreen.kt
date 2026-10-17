package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.SearchRow
import com.maxrave.domain.data.model.searchResult.SearchRowKind
import com.maxrave.domain.data.model.searchResult.SearchTopResult
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmChips
import com.maxrave.simpmusic.ui.component.ytm.YtmOutlinedPill
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.component.ytm.openContent
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.ArrowOutward
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Sensors
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SearchScreenUIState
import com.maxrave.simpmusic.viewModel.SearchType
import com.maxrave.simpmusic.viewModel.SearchViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** YouTube's chip ids, mapped to the filtered lists Refine has. */
private val chipTypes =
    mapOf(
        "Songs" to SearchType.SONGS,
        "Videos" to SearchType.VIDEOS,
        "Albums" to SearchType.ALBUMS,
        "Artists" to SearchType.ARTISTS,
        "Community playlists" to SearchType.PLAYLISTS,
        "Featured playlists" to SearchType.FEATURED_PLAYLISTS,
    )

/**
 * YouTube Music's search, the Search tab: the field on top; while typing, recent searches and
 * YouTube's suggestions (the arrow fills the field without searching) with the songs, artists and
 * albums it suggests below them; after searching, YouTube's chips in the order it ranks them for
 * the query, then its results page as sent: the top result card with its buttons and rows, and
 * the ranked rows under it. A chip shows that one kind, a page at a time.
 */
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = koinInject(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val state by viewModel.searchScreenState.collectAsStateWithLifecycle()
    val uiState by viewModel.searchScreenUIState.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val reload by sharedViewModel.reloadDestination.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(true) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    menuTrack?.let { NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = it.toSongEntity()) }

    LaunchedEffect(Unit) { if (submitted == null) focus.requestFocus() }
    LaunchedEffect(query, editing) {
        if (editing && query.isNotBlank()) {
            delay(150)
            viewModel.suggestQuery(query)
        }
    }
    // Tapping the Search tab again: back to an empty field, ready to type.
    LaunchedEffect(reload) {
        if (reload == SearchDestination::class) {
            query = ""
            submitted = null
            editing = true
            focus.requestFocus()
            sharedViewModel.reloadDestinationDone()
        }
    }

    fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        submitted = text
        editing = false
        focusManager.clearFocus()
        viewModel.insertSearchHistory(text)
        viewModel.searchAll(text)
    }

    fun playSong(song: SongsResult) {
        val track = song.toTrack()
        sharedViewModel.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${song.videoId}",
                playlistName = "\"${track.title}\" Radio",
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
    }

    fun playVideo(video: VideosResult) {
        val track = video.toTrack()
        sharedViewModel.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${video.videoId}",
                playlistName = "\"${track.title}\" Radio",
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
    }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        // The search field, YouTube Music's top bar on this page.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (submitted != null || navController.previousBackStackEntry != null) {
                IconButton(onClick = {
                    if (submitted != null && !editing) {
                        // Back from results returns to the field, as YouTube Music does.
                        submitted = null
                        editing = true
                        focus.requestFocus()
                    } else {
                        navController.navigateUp()
                    }
                }) {
                    Icon(SimpIcons.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Ytm.Surface)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("Search songs, albums, artists", style = typo().bodyLarge, color = Ytm.TextSecondary, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        editing = true
                    },
                    singleLine = true,
                    textStyle = typo().bodyLarge.copy(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(query) }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) editing = true },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    query = ""
                    editing = true
                    focus.requestFocus()
                }) {
                    Icon(SimpIcons.Close, contentDescription = "Clear", tint = Color.White)
                }
            }
        }

        if (editing || submitted == null) {
            Suggestions(
                query = query,
                history = history,
                suggestions = if (query.isBlank()) emptyList() else state.suggestQueries,
                items = if (query.isBlank()) emptyList() else state.suggestYTItems,
                onSearch = ::search,
                onFill = {
                    query = it
                    editing = true
                },
                onClearHistory = viewModel::deleteSearchHistory,
                onSong = ::playSong,
                onVideo = ::playVideo,
                onAlbum = { navController.navigate(AlbumDestination(browseId = it.browseId)) },
                onArtist = { navController.navigate(ArtistDestination(channelId = it.browseId)) },
                onPlaylist = { navController.navigate(PlaylistDestination(playlistId = it.browseId)) },
            )
            return@Column
        }

        // YouTube's chips for this query, in its order.
        val chips = state.summary?.chips.orEmpty().mapNotNull { chip -> chipTypes[chip.id]?.let { it to chip.label } }
        if (chips.isNotEmpty()) {
            val selected = chips.indexOfFirst { it.first == state.searchType }.takeIf { it >= 0 }
            YtmChips(
                labels = chips.map { it.second },
                selected = selected,
                onSelect = { index -> viewModel.selectFilter(if (index == selected) SearchType.ALL else chips[index].first) },
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        when (uiState) {
            SearchScreenUIState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }

            SearchScreenUIState.Error ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    YtmOutlinedPill("Retry") { submitted?.let(::search) }
                }

            else ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    when (state.searchType) {
                        SearchType.SONGS ->
                            items(state.searchSongsResult, key = { "s-" + it.videoId }) { song ->
                                SongResultRow(song, { playSong(song) }) { menuTrack = song.toTrack() }
                            }

                        SearchType.VIDEOS ->
                            items(state.searchVideosResult, key = { "v-" + it.videoId }) { video ->
                                ResultRow(
                                    title = video.title,
                                    subtitle = listOfNotNull(video.artists?.joinToString(", ") { it.name }, video.views, video.duration).filter { it.isNotBlank() }.joinToString(" • "),
                                    thumbnail = video.thumbnails?.lastOrNull()?.url,
                                    shape = ThumbShape.Wide,
                                    onClick = { playVideo(video) },
                                    onMore = { menuTrack = video.toTrack() },
                                )
                            }

                        SearchType.ALBUMS ->
                            items(state.searchAlbumsResult, key = { "a-" + it.browseId }) { album ->
                                AlbumResultRow(album) { navController.navigate(AlbumDestination(browseId = album.browseId)) }
                            }

                        SearchType.ARTISTS ->
                            items(state.searchArtistsResult, key = { "r-" + it.browseId }) { artist ->
                                ArtistResultRow(artist) { navController.navigate(ArtistDestination(channelId = artist.browseId)) }
                            }

                        SearchType.PLAYLISTS ->
                            items(state.searchPlaylistsResult, key = { "p-" + it.browseId }) { p ->
                                PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) }
                            }

                        SearchType.FEATURED_PLAYLISTS ->
                            items(state.searchFeaturedPlaylistsResult, key = { "f-" + it.browseId }) { p ->
                                PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) }
                            }

                        else ->
                            summaryPage(
                                summary = state.summary,
                                onRow = { openContent(it.content, navController, sharedViewModel) },
                                onMore = { menuTrack = it.content.toTrack() },
                                onButton = { top, button ->
                                    if (top.row.kind == SearchRowKind.SONG || top.row.kind == SearchRowKind.VIDEO) {
                                        openContent(top.row.content, navController, sharedViewModel)
                                    } else {
                                        sharedViewModel.playRadio(button.endpoint, top.row.content.title)
                                    }
                                },
                            )
                    }
                }
        }
    }
}

/** The unfiltered page: the top result card, then each of YouTube's shelves with its rows. */
private fun LazyListScope.summaryPage(
    summary: com.maxrave.domain.data.model.searchResult.SearchSummary?,
    onRow: (SearchRow) -> Unit,
    onMore: (SearchRow) -> Unit,
    onButton: (SearchTopResult, com.maxrave.domain.data.model.searchResult.SearchTopButton) -> Unit,
) {
    if (summary == null) return
    if (summary.top == null && summary.sections.isEmpty()) {
        item(key = "none") {
            Text(
                "No results",
                style = typo().bodyLarge,
                color = Ytm.TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        return
    }
    summary.top?.let { top ->
        item(key = "top") { TopResultCard(top, onRow, onMore, onButton) }
    }
    summary.sections.forEachIndexed { sectionIndex, (title, rows) ->
        if (title.isNotBlank()) item(key = "h-$sectionIndex") { SectionTitle(title) }
        itemsIndexed(rows, key = { index, _ -> "r-$sectionIndex-$index" }) { _, row ->
            SummaryRow(row, { onRow(row) }, if (row.kind == SearchRowKind.SONG || row.kind == SearchRowKind.VIDEO) ({ onMore(row) }) else null)
        }
    }
}

/**
 * YouTube Music's top result: a raised card with the picture (round for an artist), the name and
 * YouTube's line under it, its buttons (Shuffle and Mix for an artist, Play for an album or song),
 * and the rows YouTube puts under it.
 */
@Composable
private fun TopResultCard(
    top: SearchTopResult,
    onRow: (SearchRow) -> Unit,
    onMore: (SearchRow) -> Unit,
    onButton: (SearchTopResult, com.maxrave.domain.data.model.searchResult.SearchTopButton) -> Unit,
) {
    val row = top.row
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ytm.Surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onRow(row) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(row.content.thumbnails.lastOrNull()?.url, 72.dp, if (row.kind == SearchRowKind.ARTIST) CircleShape else RoundedCornerShape(4.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.content.title,
                    style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.subtitle, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (top.buttons.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                top.buttons.take(2).forEachIndexed { index, button ->
                    val primary = index == 0
                    val icon: ImageVector =
                        when {
                            button.label.contains("huffle", ignoreCase = true) -> SimpIcons.Shuffle
                            button.endpoint.playlistId?.startsWith("RDEM") == true || button.endpoint.playlistId?.startsWith("RDAMPL") == true -> SimpIcons.Sensors
                            else -> SimpIcons.PlayArrow
                        }
                    Row(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (primary) Color.White else Color.White.copy(alpha = 0.1f))
                            .clickable { onButton(top, button) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            button.label,
                            style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (primary) Color.Black else Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        top.rows.forEach { sub ->
            SummaryRow(sub, { onRow(sub) }, if (sub.kind == SearchRowKind.SONG || sub.kind == SearchRowKind.VIDEO) ({ onMore(sub) }) else null)
        }
        if (top.rows.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryRow(
    row: SearchRow,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) = ResultRow(
    title = row.content.title,
    subtitle = row.subtitle,
    thumbnail = row.content.thumbnails.lastOrNull()?.url,
    shape =
        when (row.kind) {
            SearchRowKind.ARTIST -> ThumbShape.Round
            SearchRowKind.VIDEO -> ThumbShape.Wide
            else -> ThumbShape.Square
        },
    onClick = onClick,
    onMore = onMore,
)

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Suggestions(
    query: String,
    history: List<String>,
    suggestions: List<String>,
    items: List<SearchResultType>,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSong: (SongsResult) -> Unit,
    onVideo: (VideosResult) -> Unit,
    onAlbum: (AlbumsResult) -> Unit,
    onArtist: (ArtistsResult) -> Unit,
    onPlaylist: (PlaylistsResult) -> Unit,
) {
    val recent = if (query.isBlank()) history else history.filter { it.contains(query, ignoreCase = true) }.take(3)
    LazyColumn(Modifier.fillMaxSize()) {
        items(recent, key = { "h-$it" }) { text -> SuggestionRow(SimpIcons.History, text, { onSearch(text) }) { onFill(text) } }
        items(suggestions.filter { it !in recent }, key = { "s-$it" }) { text ->
            SuggestionRow(SimpIcons.Search, text, { onSearch(text) }) { onFill(text) }
        }
        // The songs, artists and albums YouTube suggests for what is typed so far.
        itemsIndexed(items, key = { index, _ -> "i-$index" }) { _, item ->
            when (item) {
                is SongsResult -> SongResultRow(item, { onSong(item) }, null)
                is VideosResult ->
                    ResultRow(
                        title = item.title,
                        subtitle = listOfNotNull("Video", item.artists?.joinToString(", ") { it.name }).filter { it.isNotBlank() }.joinToString(" • "),
                        thumbnail = item.thumbnails?.lastOrNull()?.url,
                        shape = ThumbShape.Wide,
                        onClick = { onVideo(item) },
                    )
                is AlbumsResult -> AlbumResultRow(item) { onAlbum(item) }
                is ArtistsResult -> ArtistResultRow(item) { onArtist(item) }
                is PlaylistsResult -> PlaylistResultRow(item) { onPlaylist(item) }
                else -> Unit
            }
        }
        if (query.isBlank() && history.isNotEmpty()) {
            item(key = "clear") {
                Box(Modifier.padding(16.dp)) { YtmOutlinedPill("Clear search history", onClearHistory) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    onFill: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ytm.TextSecondary)
        Spacer(Modifier.width(20.dp))
        Text(text, style = typo().bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onFill) {
            // The arrow points up-left: it puts the suggestion in the field without searching.
            Icon(SimpIcons.ArrowOutward, contentDescription = "Fill in", tint = Ytm.TextSecondary, modifier = Modifier.size(20.dp).rotate(-90f))
        }
    }
}

@Composable
private fun SongResultRow(
    song: SongsResult,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) {
    YtmSongRow(
        title = song.title.orEmpty(),
        subtitle =
            listOfNotNull(
                song.artists?.joinToString(", ") { it.name },
                song.album?.name,
                song.duration,
            ).filter { it.isNotBlank() }.joinToString(" • "),
        thumbnail = song.thumbnails?.lastOrNull()?.url,
        onClick = onClick,
        onMore = onMore,
    )
}

@Composable
private fun AlbumResultRow(
    album: AlbumsResult,
    onClick: () -> Unit,
) = ResultRow(
    title = album.title,
    subtitle = listOf(album.type, album.artists.joinToString(", ") { it.name }, album.year).filter { it.isNotBlank() && it != "null" }.joinToString(" • "),
    thumbnail = album.thumbnails.lastOrNull()?.url,
    shape = ThumbShape.Square,
    onClick = onClick,
)

@Composable
private fun ArtistResultRow(
    artist: ArtistsResult,
    onClick: () -> Unit,
) = ResultRow(artist.artist, "Artist", artist.thumbnails.lastOrNull()?.url, ThumbShape.Round, onClick = onClick)

@Composable
private fun PlaylistResultRow(
    playlist: PlaylistsResult,
    onClick: () -> Unit,
) = ResultRow(
    title = playlist.title,
    subtitle = listOf("Playlist", playlist.author, playlist.itemCount).filter { it.isNotBlank() }.joinToString(" • "),
    thumbnail = playlist.thumbnails.lastOrNull()?.url,
    shape = ThumbShape.Square,
    onClick = onClick,
)

private enum class ThumbShape { Square, Round, Wide }

/** A result row: picture (square, round for an artist, 16:9 for a video), title, YouTube's line, ⋮. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    thumbnail: String?,
    shape: ThumbShape,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (shape) {
            ThumbShape.Wide -> WideThumb(thumbnail, 96.dp)
            ThumbShape.Round -> Thumb(thumbnail, 56.dp, CircleShape)
            ThumbShape.Square -> Thumb(thumbnail, 56.dp, RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(SimpIcons.MoreVert, contentDescription = "More", tint = Ytm.TextSecondary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun WideThumb(
    url: String?,
    width: Dp,
) {
    AsyncImage(
        model = url,
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)),
    )
}
