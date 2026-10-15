package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmChips
import com.maxrave.simpmusic.ui.component.ytm.YtmOutlinedPill
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.ArrowOutward
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SearchScreenUIState
import com.maxrave.simpmusic.viewModel.SearchType
import com.maxrave.simpmusic.viewModel.SearchViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** The result filters YouTube Music offers, songs-only: no videos, podcasts, episodes or profiles. */
private val searchFilters =
    listOf(
        SearchType.SONGS to "Songs",
        SearchType.ALBUMS to "Albums",
        SearchType.ARTISTS to "Artists",
        SearchType.PLAYLISTS to "Community playlists",
        SearchType.FEATURED_PLAYLISTS to "Featured playlists",
    )

/**
 * YouTube Music's search: the field in the top bar; while typing, recent searches and suggestions
 * (the arrow fills the field without searching); after searching, the filter chips over the
 * results, the unfiltered view led by the top result and then a few of each kind.
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
            delay(200)
            viewModel.suggestQuery(query)
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
        viewModel.setSearchType(SearchType.ALL)
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

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        // The search field, YouTube Music's top bar on this page.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
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
                onSearch = ::search,
                onFill = {
                    query = it
                    editing = true
                },
                onClearHistory = viewModel::deleteSearchHistory,
            )
            return@Column
        }

        val selected = searchFilters.indexOfFirst { it.first == state.searchType }.takeIf { it >= 0 }
        YtmChips(
            labels = searchFilters.map { it.second },
            selected = selected,
            onSelect = { index -> viewModel.setSearchType(if (index == selected) SearchType.ALL else searchFilters[index].first) },
            modifier = Modifier.padding(vertical = 8.dp),
        )
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
                        SearchType.SONGS -> items(state.searchSongsResult, key = { it.videoId }) { song ->
                            SongResultRow(song, { playSong(song) }) { menuTrack = song.toTrack() }
                        }
                        SearchType.ALBUMS -> items(state.searchAlbumsResult, key = { it.browseId }) { album ->
                            AlbumResultRow(album) { navController.navigate(AlbumDestination(browseId = album.browseId)) }
                        }
                        SearchType.ARTISTS -> items(state.searchArtistsResult, key = { it.browseId }) { artist ->
                            ArtistResultRow(artist) { navController.navigate(ArtistDestination(channelId = artist.browseId)) }
                        }
                        SearchType.PLAYLISTS -> items(state.searchPlaylistsResult, key = { it.browseId }) { p ->
                            PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) }
                        }
                        SearchType.FEATURED_PLAYLISTS -> items(state.searchFeaturedPlaylistsResult, key = { it.browseId }) { p ->
                            PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) }
                        }
                        else -> {
                            val top = state.searchAllResult.firstOrNull()
                            if (top != null) {
                                item(key = "top") {
                                    SectionTitle("Top result")
                                    TopResult(
                                        top,
                                        onSong = ::playSong,
                                        onAlbum = { navController.navigate(AlbumDestination(browseId = it.browseId)) },
                                        onArtist = { navController.navigate(ArtistDestination(channelId = it.browseId)) },
                                        onPlaylist = { navController.navigate(PlaylistDestination(playlistId = it.browseId)) },
                                    )
                                }
                            }
                            section(
                                "Songs",
                                state.searchSongsResult.take(4),
                                onShowAll = { viewModel.setSearchType(SearchType.SONGS) },
                            ) { song -> SongResultRow(song, { playSong(song) }) { menuTrack = song.toTrack() } }
                            section(
                                "Albums",
                                state.searchAlbumsResult.take(3),
                                onShowAll = { viewModel.setSearchType(SearchType.ALBUMS) },
                            ) { album -> AlbumResultRow(album) { navController.navigate(AlbumDestination(browseId = album.browseId)) } }
                            section(
                                "Artists",
                                state.searchArtistsResult.take(3),
                                onShowAll = { viewModel.setSearchType(SearchType.ARTISTS) },
                            ) { artist -> ArtistResultRow(artist) { navController.navigate(ArtistDestination(channelId = artist.browseId)) } }
                            section(
                                "Community playlists",
                                state.searchPlaylistsResult.take(3),
                                onShowAll = { viewModel.setSearchType(SearchType.PLAYLISTS) },
                            ) { p -> PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) } }
                            section(
                                "Featured playlists",
                                state.searchFeaturedPlaylistsResult.take(3),
                                onShowAll = { viewModel.setSearchType(SearchType.FEATURED_PLAYLISTS) },
                            ) { p -> PlaylistResultRow(p) { navController.navigate(PlaylistDestination(playlistId = p.browseId)) } }
                        }
                    }
                }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    entries: List<T>,
    onShowAll: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "h-$title") { SectionTitle(title) }
    entries.forEachIndexed { index, entry -> item(key = "$title-$index") { row(entry) } }
    item(key = "all-$title") {
        Box(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)) { YtmOutlinedPill("Show all", onShowAll) }
    }
}

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
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val recent = if (query.isBlank()) history else history.filter { it.contains(query, ignoreCase = true) }.take(3)
    LazyColumn(Modifier.fillMaxSize()) {
        items(recent, key = { "h-$it" }) { text -> SuggestionRow(SimpIcons.History, text, { onSearch(text) }) { onFill(text) } }
        items(suggestions.filter { it !in recent }, key = { "s-$it" }) { text ->
            SuggestionRow(SimpIcons.Search, text, { onSearch(text) }) { onFill(text) }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
private fun TopResult(
    item: SearchResultType,
    onSong: (SongsResult) -> Unit,
    onAlbum: (AlbumsResult) -> Unit,
    onArtist: (ArtistsResult) -> Unit,
    onPlaylist: (PlaylistsResult) -> Unit,
) {
    when (item) {
        is SongsResult -> SongResultRow(item, { onSong(item) }, null)
        is AlbumsResult -> AlbumResultRow(item) { onAlbum(item) }
        is ArtistsResult -> ArtistResultRow(item) { onArtist(item) }
        is PlaylistsResult -> PlaylistResultRow(item) { onPlaylist(item) }
        else -> Unit
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
                "Song",
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
    subtitle = listOf(album.type, album.artists.joinToString(", ") { it.name }, album.year).filter { it.isNotBlank() }.joinToString(" • "),
    thumbnail = album.thumbnails.lastOrNull()?.url,
    round = false,
    onClick = onClick,
)

@Composable
private fun ArtistResultRow(
    artist: ArtistsResult,
    onClick: () -> Unit,
) = ResultRow(artist.artist, "Artist", artist.thumbnails.lastOrNull()?.url, round = true, onClick = onClick)

@Composable
private fun PlaylistResultRow(
    playlist: PlaylistsResult,
    onClick: () -> Unit,
) = ResultRow(
    title = playlist.title,
    subtitle = listOf("Playlist", playlist.author, playlist.itemCount).filter { it.isNotBlank() }.joinToString(" • "),
    thumbnail = playlist.thumbnails.lastOrNull()?.url,
    round = false,
    onClick = onClick,
)

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    thumbnail: String?,
    round: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbnail, 56.dp, if (round) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
