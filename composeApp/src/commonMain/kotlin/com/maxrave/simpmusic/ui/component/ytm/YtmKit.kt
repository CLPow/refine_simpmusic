package com.maxrave.simpmusic.ui.component.ytm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.Logout
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.Notifications
import com.maxrave.simpmusic.ui.icon.PersonAdd
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Settings
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.RecentlySongsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.mono

/** YouTube Music's palette, as used across every page. */
object Ytm {
    val Background = Color(0xFF030303)
    val Surface = Color(0xFF212121)
    val Raised = Color(0xFF2A2A2A)
    val TextSecondary = Color(0xFFAAAAAA)
    val ChipBackground = Color.White.copy(alpha = 0.1f)
    val Divider = Color.White.copy(alpha = 0.1f)
}

// region Top bar and account

/**
 * YouTube Music's top bar. On a tab page it carries the logo; on a sub-page, a back arrow and the
 * page title. Notifications, search and the account avatar sit on the right of both.
 */
@Composable
fun YtmTopBar(
    navController: NavController,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
) {
    var showAccount by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                title.orEmpty(),
                style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            YtmLogo()
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = { navController.navigate(NotificationDestination) }) {
            Icon(SimpIcons.Notifications, contentDescription = "Notifications", tint = Color.White)
        }
        IconButton(onClick = { navController.navigate(SearchDestination) }) {
            Icon(SimpIcons.Search, contentDescription = "Search", tint = Color.White)
        }
        AccountAvatar(size = 28.dp, modifier = Modifier.padding(horizontal = 10.dp)) { showAccount = true }
    }
    if (showAccount) {
        AccountMenuSheet(navController = navController, onDismiss = { showAccount = false })
    }
}

/** The app mark: the logo in red beside the name, YouTube Music's top-left. */
@Composable
fun YtmLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(Res.drawable.mono),
            contentDescription = null,
            tint = YtmRed,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Refine",
            style = typo().titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            color = Color.White,
        )
    }
}

/** The signed-in account's picture, or a grey disc when signed out. */
@Composable
fun AccountAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    dataStoreManager: DataStoreManager = koinInject(),
    onClick: () -> Unit,
) {
    val thumb by dataStoreManager.getString("AccountThumbUrl").collectAsStateWithLifecycle(null)
    Box(modifier.size(size).clip(CircleShape).background(Ytm.Raised).clickable(onClick = onClick)) {
        if (!thumb.isNullOrBlank()) {
            AsyncImage(
                model = thumb,
                contentDescription = "Account",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** The sheet the avatar opens, as in YouTube Music: the account, then History, Settings and the account actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuSheet(
    navController: NavController,
    onDismiss: () -> Unit,
    dataStoreManager: DataStoreManager = koinInject(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
) {
    val name by dataStoreManager.getString("AccountName").collectAsStateWithLifecycle(null)
    val signedIn = !name.isNullOrBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ytm.Surface,
        dragHandle = null,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(size = 40.dp) {}
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (signedIn) name.orEmpty() else "Not signed in",
                    style = typo().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = Color.White,
                )
                if (signedIn) {
                    Text("Google account", style = typo().bodyMedium, color = Ytm.TextSecondary)
                }
            }
        }
        HorizontalDivider(color = Ytm.Divider)
        AccountMenuRow(SimpIcons.History, "History") {
            onDismiss()
            navController.navigate(RecentlySongsDestination)
        }
        // Other saved accounts: a tap switches to it, as YouTube Music's account switcher does.
        val accounts by settingsViewModel.googleAccounts.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { settingsViewModel.getAllGoogleAccount() }
        accounts.data.orEmpty().filter { !it.isUsed }.forEach { account ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        settingsViewModel.setUsedAccount(account)
                        onDismiss()
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(account.thumbnailUrl, 32.dp, CircleShape)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(account.name, style = typo().bodyLarge, color = Color.White)
                    Text(account.email, style = typo().bodyMedium, color = Ytm.TextSecondary)
                }
            }
        }
        AccountMenuRow(SimpIcons.PersonAdd, if (signedIn) "Add account" else "Sign in") {
            onDismiss()
            navController.navigate(LoginDestination)
        }
        AccountMenuRow(SimpIcons.Settings, "Settings") {
            onDismiss()
            navController.navigate(SettingsDestination)
        }
        if (signedIn) {
            AccountMenuRow(SimpIcons.Logout, "Sign out") {
                settingsViewModel.logOutAllYouTube()
                onDismiss()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AccountMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(24.dp))
        Text(label, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White)
    }
}

// endregion

// region Chips

/** YouTube Music's filter chips: rounded rectangles, white when selected. */
@Composable
fun YtmChips(
    labels: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(labels) { index, label ->
            YtmChip(label, index == selected) { onSelect(index) }
        }
    }
}

@Composable
fun YtmChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White else Ytm.ChipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/** An outlined pill button, YouTube Music's "More" / "Play all" beside a shelf title. */
@Composable
fun YtmOutlinedPill(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1)
    }
}

// endregion

// region Shelves

/**
 * A shelf header: the optional small picture (round for an artist), the strapline above the
 * title, and an optional pill on the right.
 */
@Composable
fun YtmShelfHeader(
    title: String,
    strapline: String? = null,
    thumbnail: String? = null,
    roundThumbnail: Boolean = true,
    onTitleClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Thumb(thumbnail, 40.dp, if (roundThumbnail) CircleShape else RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column {
                if (!strapline.isNullOrBlank()) {
                    Text(strapline, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    title,
                    style = typo().headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            YtmOutlinedPill(actionLabel, onAction)
        }
    }
}

/** One shelf of a YouTube Music feed: list shelves as the Quick picks grid, the rest as cards. */
@Composable
fun YtmShelf(
    item: HomeItem,
    navController: NavController,
    sharedViewModel: SharedViewModel = koinInject(),
) {
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    menuTrack?.let { track ->
        NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = track.toSongEntity())
    }
    val contents = remember(item) { item.contents.filterNotNull() }
    val channelId = item.channelId
    val songs = contents.filter { !it.videoId.isNullOrEmpty() }
    YtmShelfHeader(
        title = item.title,
        strapline = item.subtitle,
        thumbnail = item.thumbnail?.lastOrNull()?.url,
        roundThumbnail = channelId != null,
        onTitleClick = channelId?.let { { navController.navigate(ArtistDestination(channelId = it)) } },
        actionLabel = if (item.isListShelf && songs.size > 1) "Play all" else null,
        onAction =
            if (item.isListShelf && songs.size > 1) {
                { playAll(songs.map { it.toTrack() }, item.title, sharedViewModel) }
            } else {
                null
            },
    )
    if (item.isListShelf) {
        YtmQuickPicks(
            contents = contents,
            onClick = { openContent(it, navController, sharedViewModel) },
            onMore = { menuTrack = it.toTrack() },
        )
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(contents) { content ->
                YtmCard(
                    content = content,
                    onClick = { openContent(content, navController, sharedViewModel) },
                    onLongClick = if (!content.videoId.isNullOrEmpty()) ({ menuTrack = content.toTrack() }) else null,
                )
            }
        }
    }
}

/** YouTube Music's two-line card: square cover (round for an artist), title, subtitle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmCard(
    content: Content,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 152.dp,
) {
    val isArtist = content.isArtist()
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Thumb(
            content.thumbnails.lastOrNull()?.url,
            width,
            if (isArtist) CircleShape else RoundedCornerShape(4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            content.title,
            style = typo().bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = content.subtitle()
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = typo().bodyMedium.copy(fontSize = 13.sp), color = Ytm.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Quick picks: song rows four to a column, columns side by side with the next one peeking in,
 * snapping column by column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmQuickPicks(
    contents: List<Content>,
    onClick: (Content) -> Unit,
    onMore: (Content) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = (maxWidth - 48.dp).coerceAtMost(420.dp)
        val rows = minOf(4, contents.size).coerceAtLeast(1)
        val state = rememberLazyGridState()
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth().height((64 * rows).dp),
        ) {
            items(contents) { content ->
                YtmSongRow(
                    title = content.title,
                    subtitle = content.subtitle(),
                    thumbnail = content.thumbnails.lastOrNull()?.url,
                    modifier = Modifier.width(columnWidth),
                    onClick = { onClick(content) },
                    onMore = { onMore(content) },
                )
            }
        }
    }
}

/** YouTube Music's song row: 48dp cover, title, grey subtitle, the ⋮ menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmSongRow(
    title: String,
    subtitle: String,
    thumbnail: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    leading: (@Composable () -> Unit)? = null,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: onMore)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Thumb(thumbnail, 48.dp, RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = typo().bodyLarge.copy(fontSize = 15.sp),
                color = if (isCurrent) YtmRed else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(SimpIcons.MoreVert, contentDescription = "More", tint = Ytm.TextSecondary)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

/** A cover image clipped to [shape]; square unless the caller sizes it otherwise. */
@Composable
fun Thumb(
    url: String?,
    side: Dp,
    shape: Shape,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCacheKey(url)
                .crossfade(200)
                .build(),
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(side).aspectRatio(1f).clip(shape),
    )
}

// endregion

// region Routing

private fun Content.isArtist(): Boolean =
    (browseId?.startsWith("UC") == true || playlistId?.startsWith("UC") == true) && videoId.isNullOrEmpty()

/** The grey line under a card or row, built the way YouTube Music writes it. */
fun Content.subtitle(): String {
    val artistNames = artists?.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.orEmpty().joinToString(", ")
    return when {
        !videoId.isNullOrEmpty() -> listOf(artistNames, album?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
        isArtist() -> description ?: views.orEmpty()
        else -> artistNames.ifBlank { description.orEmpty() }
    }
}

/**
 * Opens what a card or row stands for, as YouTube Music does: a song starts its radio, an album,
 * playlist or artist opens its page.
 */
fun openContent(
    content: Content,
    navController: NavController,
    sharedViewModel: SharedViewModel,
) {
    val videoId = content.videoId
    val browseId = content.browseId
    val playlistId = content.playlistId
    when {
        !videoId.isNullOrEmpty() -> {
            val track = content.toTrack()
            sharedViewModel.setQueueData(
                QueueData.Data(
                    listTracks = arrayListOf(track),
                    firstPlayedTrack = track,
                    playlistId = "RDAMVM$videoId",
                    playlistName = "\"${content.title}\" Radio",
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
        }
        content.isArtist() -> navController.navigate(ArtistDestination(channelId = browseId ?: playlistId.orEmpty()))
        !browseId.isNullOrEmpty() && (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) ->
            navController.navigate(AlbumDestination(browseId = browseId))
        !browseId.isNullOrEmpty() && browseId.startsWith("VL") -> navController.navigate(PlaylistDestination(playlistId = browseId))
        !playlistId.isNullOrEmpty() -> navController.navigate(PlaylistDestination(playlistId = playlistId))
        !browseId.isNullOrEmpty() -> navController.navigate(AlbumDestination(browseId = browseId))
    }
}

/** Plays [tracks] in order as a queue named [title] (a shelf's "Play all"). */
fun playAll(
    tracks: List<Track>,
    title: String,
    sharedViewModel: SharedViewModel,
    shuffle: Boolean = false,
) {
    if (tracks.isEmpty()) return
    val list = if (shuffle) tracks.shuffled() else tracks
    sharedViewModel.setQueueData(
        QueueData.Data(
            listTracks = ArrayList(list),
            firstPlayedTrack = list.first(),
            playlistId = null,
            playlistName = title,
            playlistType = PlaylistType.PLAYLIST,
            continuation = null,
        ),
    )
    sharedViewModel.loadMediaItem(list.first(), Config.PLAYLIST_CLICK, 0)
}

// endregion
