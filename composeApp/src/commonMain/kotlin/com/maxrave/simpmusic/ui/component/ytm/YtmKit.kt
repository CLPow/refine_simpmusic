package com.maxrave.simpmusic.ui.component.ytm

import com.maxrave.simpmusic.ui.component.RefineControlButton
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.RowScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.maxrave.simpmusic.ui.icon.QueueMusic
import com.maxrave.simpmusic.ui.icon.CheckCircle
import com.maxrave.simpmusic.ui.icon.AddCircleOutline
import simpmusic.composeapp.generated.resources.add_to_queue
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.launch
import com.maxrave.simpmusic.ui.icon.ArrowForwardIos
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.utils.MusicVideoType
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
import com.maxrave.simpmusic.ui.icon.PersonAdd
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Settings
import com.maxrave.simpmusic.ui.icon.SimpIcons
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
import simpmusic.composeapp.generated.resources.logo_mark
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import com.maxrave.domain.repository.AccountRepository
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.ytm_account
import simpmusic.composeapp.generated.resources.ytm_add_account
import simpmusic.composeapp.generated.resources.ytm_back
import simpmusic.composeapp.generated.resources.ytm_history
import simpmusic.composeapp.generated.resources.ytm_next
import simpmusic.composeapp.generated.resources.ytm_not_signed_in
import simpmusic.composeapp.generated.resources.ytm_play_all
import simpmusic.composeapp.generated.resources.ytm_previous
import simpmusic.composeapp.generated.resources.ytm_search
import simpmusic.composeapp.generated.resources.ytm_search_hint
import simpmusic.composeapp.generated.resources.ytm_settings
import simpmusic.composeapp.generated.resources.ytm_sign_in
import simpmusic.composeapp.generated.resources.ytm_sign_out
import simpmusic.composeapp.generated.resources.ytm_sign_out_confirm
import simpmusic.composeapp.generated.resources.ytm_sign_out_confirm_body
import simpmusic.composeapp.generated.resources.ytm_signed_out_title
import simpmusic.composeapp.generated.resources.ytm_speed_dial
import androidx.compose.foundation.layout.fillMaxSize
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.ytm_page_failed

val LocalWideLayout = staticCompositionLocalOf { false }

object Ytm {
    val Background = Color(0xFF030303)
    val Surface = Color(0xFF212121)
    val Raised = Color(0xFF2A2A2A)
    val TextSecondary = Color(0xFFAAAAAA)
    val ChipBackground = Color.White.copy(alpha = 0.1f)
    val Divider = Color.White.copy(alpha = 0.1f)
}

// region Top bar and account

@Composable
fun YtmTopBar(
    navController: NavController,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    showActions: Boolean = true,
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
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                title.orEmpty(),
                style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else if (LocalWideLayout.current) {
            Row(
                Modifier
                    .padding(start = 40.dp)
                    .widthIn(max = 480.dp)
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { navController.navigate(SearchDestination) { launchSingleTop = true } }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SimpIcons.Search, contentDescription = null, tint = Ytm.TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.ytm_search_hint), style = typo().bodyLarge, color = Ytm.TextSecondary, maxLines = 1)
            }
            Spacer(Modifier.weight(0.4f))
        } else {
            YtmLogo()
            Spacer(Modifier.weight(1f))
        }
        if (showActions) {
            if (onBack != null) {
                IconButton(onClick = { navController.navigate(SearchDestination) { launchSingleTop = true } }) {
                    Icon(SimpIcons.Search, contentDescription = stringResource(Res.string.ytm_search), tint = Color.White)
                }
            }
            RefineControlButton()
            AccountAvatar(size = 28.dp, modifier = Modifier.padding(end = 4.dp)) { showAccount = true }
        }
    }
    if (showAccount) {
        AccountMenuSheet(navController = navController, onDismiss = { showAccount = false })
    }
}

@Composable
fun YtmLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(Res.drawable.logo_mark),
            contentDescription = null,
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

@Composable
fun AccountAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    dataStoreManager: DataStoreManager = koinInject(),
    accountRepository: AccountRepository = koinInject(),
    onClick: (() -> Unit)?,
) {
    val thumb by dataStoreManager.getString("AccountThumbUrl").collectAsStateWithLifecycle(null)
    val expired by accountRepository.signInExpired.collectAsStateWithLifecycle()
    val label = stringResource(Res.string.ytm_account)
    Box(
        modifier
            .then(
                if (onClick != null) {
                    Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(Ytm.Raised)) {
            if (!thumb.isNullOrBlank()) {
                AsyncImage(
                    model = thumb,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    alpha = if (expired) 0.35f else 1f,
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuSheet(
    navController: NavController,
    onDismiss: () -> Unit,
    dataStoreManager: DataStoreManager = koinInject(),
    accountRepository: AccountRepository = koinInject(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
) {
    val name by dataStoreManager.getString("AccountName").collectAsStateWithLifecycle(null)
    val signedIn = !name.isNullOrBlank()
    val expired by accountRepository.signInExpired.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val accounts by settingsViewModel.googleAccounts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settingsViewModel.getAllGoogleAccount() }
    if (confirmSignOut) {
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(Res.string.ytm_sign_out_confirm), color = Color.White) },
            text = { Text(stringResource(Res.string.ytm_sign_out_confirm_body), color = Ytm.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    settingsViewModel.logOutAllYouTube()
                    onDismiss()
                }) { Text(stringResource(Res.string.ytm_sign_out), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ytm.Surface,
        dragHandle = null,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(size = 40.dp, onClick = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (signedIn) name.orEmpty() else stringResource(Res.string.ytm_not_signed_in),
                    style = typo().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = Color.White,
                )
                if (signedIn && expired) {
                    Text(stringResource(Res.string.ytm_signed_out_title), style = typo().bodyMedium, color = YtmRed)
                } else if (signedIn) {
                    val email = accounts.data.orEmpty().firstOrNull { it.isUsed }?.email
                    if (!email.isNullOrBlank()) {
                        Text(email, style = typo().bodyMedium, color = Ytm.TextSecondary)
                    }
                }
            }
        }
        HorizontalDivider(color = Ytm.Divider)
        AccountMenuRow(SimpIcons.History, stringResource(Res.string.ytm_history)) {
            onDismiss()
            navController.navigate(RecentlySongsDestination) { launchSingleTop = true }
        }
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
        AccountMenuRow(
            SimpIcons.PersonAdd,
            stringResource(if (signedIn && !expired) Res.string.ytm_add_account else Res.string.ytm_sign_in),
        ) {
            onDismiss()
            navController.navigate(LoginDestination) { launchSingleTop = true }
        }
        AccountMenuRow(SimpIcons.Settings, stringResource(Res.string.ytm_settings)) {
            onDismiss()
            navController.navigate(SettingsDestination) { launchSingleTop = true }
        }
        if (signedIn) {
            AccountMenuRow(SimpIcons.Logout, stringResource(Res.string.ytm_sign_out)) { confirmSignOut = true }
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
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(24.dp))
        Text(label, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White)
    }
}

// endregion

// region Chips

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
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White else Ytm.ChipBackground)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
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

@Composable
fun YtmOutlinedPill(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1)
    }
}

// endregion

// region Shelves

@Composable
fun YtmShelfHeader(
    title: String,
    strapline: String? = null,
    thumbnail: String? = null,
    roundThumbnail: Boolean = true,
    onTitleClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
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
        if (onPrevious != null && onNext != null) {
            Spacer(Modifier.width(12.dp))
            ShelfArrow(SimpIcons.ArrowBackIosNew, stringResource(Res.string.ytm_previous), onPrevious)
            Spacer(Modifier.width(8.dp))
            ShelfArrow(SimpIcons.ArrowForwardIos, stringResource(Res.string.ytm_next), onNext)
        }
    }
}

@Composable
private fun ShelfArrow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

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
    val wideLayout = LocalWideLayout.current
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val speedDial = item.isSpeedDial && !wideLayout
    val cardRow = !speedDial && !item.isListShelf
    YtmShelfHeader(
        title = if (speedDial) stringResource(Res.string.ytm_speed_dial) else item.title,
        strapline = item.subtitle,
        thumbnail = item.thumbnail?.lastOrNull()?.url,
        roundThumbnail = channelId != null,
        onTitleClick = channelId?.let { { navController.navigate(ArtistDestination(channelId = it)) } },
        actionLabel = if (item.isListShelf && songs.size > 1) stringResource(Res.string.ytm_play_all) else null,
        onAction =
            if (item.isListShelf && songs.size > 1) {
                { playAll(songs.map { it.toTrack() }, item.title, sharedViewModel) }
            } else {
                null
            },
        onPrevious =
            if (wideLayout && cardRow) {
                {
                    scope.launch {
                        val page = rowState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        rowState.animateScrollToItem((rowState.firstVisibleItemIndex - page).coerceAtLeast(0))
                    }
                }
            } else {
                null
            },
        onNext =
            if (wideLayout && cardRow) {
                {
                    scope.launch {
                        val page = (rowState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
                        rowState.animateScrollToItem((rowState.firstVisibleItemIndex + page).coerceAtMost((contents.size - 1).coerceAtLeast(0)))
                    }
                }
            } else {
                null
            },
    )
    when {
        speedDial ->
            YtmSpeedDial(
                contents = contents,
                onClick = { openContent(it, navController, sharedViewModel) },
                onLongClick = { if (!it.videoId.isNullOrEmpty()) menuTrack = it.toTrack() },
            )

        item.isListShelf ->
            YtmQuickPicks(
                contents = contents,
                onClick = { openContent(it, navController, sharedViewModel) },
                onMore = { menuTrack = it.toTrack() },
            )

        else -> {
            val wide = contents.count { it.isWideArtwork() } * 2 > contents.size
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wideLayout) 24.dp else 12.dp),
            ) {
                items(contents) { content ->
                    YtmCard(
                        content = content,
                        onClick = { openContent(content, navController, sharedViewModel) },
                        onLongClick = if (!content.videoId.isNullOrEmpty()) ({ menuTrack = content.toTrack() }) else null,
                        width = if (wideLayout) 180.dp else 152.dp,
                        wide = wide,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmSpeedDial(
    contents: List<Content>,
    onClick: (Content) -> Unit,
    onLongClick: (Content) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val columns = maxOf(3, ((maxWidth - 32.dp + gap) / (168.dp + gap)).toInt())
        val tile = (maxWidth - 32.dp - gap * (columns - 1)) / columns
        val perPage = columns * 3
        val pages = remember(contents, perPage) { contents.chunked(perPage) }
        val pagerState = rememberPagerState { pages.size }
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = gap,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    pages[page].chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { content ->
                                SpeedDialTile(content, tile, { onClick(content) }, { onLongClick(content) })
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(pages.size) { index ->
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (index == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.3f)),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialTile(
    content: Content,
    side: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val artist = content.isArtist()
    val opensPage = content.videoId.isNullOrEmpty()
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(8.dp))
            .background(Ytm.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Thumb(content.thumbnails.lastOrNull()?.url, side, if (artist) CircleShape else RoundedCornerShape(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(side * 0.4f)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                content.title,
                style = typo().bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (opensPage) {
                Icon(
                    SimpIcons.ArrowForwardIos,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(start = 4.dp).size(12.dp),
                )
            }
        }
    }
}

private fun Content.isWideArtwork(): Boolean {
    val art = thumbnails.lastOrNull() ?: return false
    return art.width > 0 && art.height > 0 && art.width * 10 > art.height * 13
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmCard(
    content: Content,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 152.dp,
    wide: Boolean = false,
) {
    val isArtist = content.isArtist()
    val cardWidth = if (wide) (if (LocalWideLayout.current) 320.dp else 256.dp) else width
    Column(
        Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (wide) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(content.thumbnails.lastOrNull()?.url)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(200)
                        .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(cardWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Thumb(
                content.thumbnails.lastOrNull()?.url,
                cardWidth,
                if (isArtist) CircleShape else RoundedCornerShape(4.dp),
            )
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtmQuickPicks(
    contents: List<Content>,
    onClick: (Content) -> Unit,
    onMore: (Content) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = (maxWidth - 48.dp).coerceIn(160.dp, 420.dp)
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
    onSwipeQueue: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    SwipeToQueueRow(
        modifier = modifier.height(64.dp),
        onClick = onClick,
        onLongClick = onLongClick ?: onMore,
        onSwipeQueue = onSwipeQueue,
        selected = selected,
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
        if (onMore != null && selected == null) {
            IconButton(onClick = onMore) {
                Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Ytm.TextSecondary)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToQueueRow(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onSwipeQueue: (() -> Unit)?,
    selected: Boolean?,
    content: @Composable RowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val swipeLimit = with(LocalDensity.current) { SWIPE_QUEUE_DISTANCE.toPx() }
    Box(modifier) {
        if (swipe.value > 0f) {
            Icon(
                SimpIcons.QueueMusic,
                contentDescription = stringResource(Res.string.add_to_queue),
                tint = if (swipe.value >= swipeLimit / 2) Color.White else Ytm.TextSecondary,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
            )
        }
        Row(
            Modifier
                .matchParentSize()
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                .background(
                    when {
                        selected == true -> Color.White.copy(alpha = 0.12f)
                        swipe.value > 0f -> Ytm.Background
                        else -> Color.Transparent
                    },
                ).then(
                    if (onSwipeQueue != null && selected == null) {
                        Modifier.pointerInput(onSwipeQueue) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount ->
                                    val next = (swipe.value + amount).coerceIn(0f, swipeLimit)
                                    if (next != swipe.value) {
                                        change.consume()
                                        scope.launch { swipe.snapTo(next) }
                                    }
                                },
                                onDragEnd = {
                                    if (swipe.value >= swipeLimit / 2) onSwipeQueue()
                                    scope.launch { swipe.animateTo(0f) }
                                },
                                onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                            )
                        }
                    } else {
                        Modifier
                    },
                ).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .then(
                    if (selected != null) {
                        Modifier.semantics {
                            this.selected = selected
                            role = Role.Checkbox
                        }
                    } else {
                        Modifier
                    },
                ).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Icon(
                    if (selected) SimpIcons.CheckCircle else SimpIcons.AddCircleOutline,
                    contentDescription = null,
                    tint = if (selected) Color.White else Ytm.TextSecondary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                )
            }
            content()
        }
    }
}

private val SWIPE_QUEUE_DISTANCE = 120.dp

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

fun Content.subtitle(): String {
    val artistNames = artists?.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.orEmpty().joinToString(", ")
    return when {
        !videoId.isNullOrEmpty() -> listOf(artistNames, album?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
        isArtist() -> description ?: views.orEmpty()
        else -> artistNames.ifBlank { description.orEmpty() }
    }
}

fun openContent(
    content: Content,
    navController: NavController,
    sharedViewModel: SharedViewModel,
) {
    val videoId = content.videoId
    val browseId = content.browseId
    val playlistId = content.playlistId
    when {
        !videoId.isNullOrEmpty() &&
            !MusicVideoType.isPlayableSong(content.videoType) &&
            !MusicVideoType.isOfficialVideo(content.videoType) ->
            sharedViewModel.playRadio(
                YouTubeWatchEndpoint(videoId = videoId, playlistId = "RDAMVM$videoId"),
                content.title,
            )

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

fun playAll(
    tracks: List<Track>,
    title: String,
    sharedViewModel: SharedViewModel,
    shuffle: Boolean = false,
    startIndex: Int = 0,
) {
    if (tracks.isEmpty()) return
    val list = if (shuffle) tracks.shuffled() else tracks
    val start = if (shuffle) 0 else startIndex.coerceIn(0, list.lastIndex)
    sharedViewModel.setQueueData(
        QueueData.Data(
            listTracks = ArrayList(list),
            firstPlayedTrack = list[start],
            playlistId = null,
            playlistName = title,
            playlistType = PlaylistType.PLAYLIST,
            continuation = null,
        ),
    )
    sharedViewModel.loadMediaItem(list[start], Config.PLAYLIST_CLICK, start)
}

// endregion

// region States

@Composable
fun YtmErrorPage(
    onBack: (() -> Unit)?,
    message: String? = null,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp)) {
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                message ?: stringResource(Res.string.ytm_page_failed),
                style = typo().bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            YtmOutlinedPill(stringResource(Res.string.retry), onClick = onRetry)
        }
    }
}

// endregion
