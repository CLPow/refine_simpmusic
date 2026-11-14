package com.maxrave.simpmusic.ui.component

import org.koin.compose.viewmodel.koinViewModel
import com.maxrave.simpmusic.viewModel.LibraryViewModel
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.icon.Menu
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.component.ytm.YtmLogo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import com.maxrave.simpmusic.ui.icon.Home
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.home
import simpmusic.composeapp.generated.resources.library
import simpmusic.composeapp.generated.resources.search
import kotlin.reflect.KClass
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.screen.library.NewPlaylistDialog
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.ytm_auto_playlist
import simpmusic.composeapp.generated.resources.ytm_guide
import simpmusic.composeapp.generated.resources.ytm_liked_music
import simpmusic.composeapp.generated.resources.ytm_new_playlist

private val BarColor = Ytm.Background
private val Unselected = Ytm.TextSecondary

/** The three tabs: Home · Search · Library. Search takes the place Explore had. */
enum class YtmTab(
    val destination: Any,
    val title: StringResource,
    val icon: ImageVector,
) {
    Home(HomeDestination, Res.string.home, SimpIcons.Home),
    Search(SearchDestination, Res.string.search, SimpIcons.Search),
    Library(LibraryDestination, Res.string.library, SimpIcons.LibraryMusic),
}

/** The tab whose page is on screen, read from the back stack so deep links and back presses agree. */
@Composable
private fun currentTab(navController: NavController): YtmTab? {
    val entry by navController.currentBackStackEntryAsState()
    val hierarchy = entry?.destination?.hierarchy ?: return YtmTab.Home
    return YtmTab.entries.firstOrNull { tab -> hierarchy.any { it.hasRoute(tab.destination::class) } }
}

private fun NavController.selectTab(
    tab: YtmTab,
    current: YtmTab?,
    reload: (KClass<*>) -> Unit,
) {
    if (tab == current) {
        // A tap on the open tab scrolls it to the top or reloads it, as in YouTube Music.
        reload(tab.destination::class)
        return
    }
    if (tab == YtmTab.Home) {
        // Home always lands on Home itself, whatever page is open on top of it. Restoring Home's
        // saved stack used to bring back the album or artist that had been open, so Home could
        // not be reached from there at all; Home's own stack is therefore never saved. Search and
        // Library are: leaving them keeps their query, chip, sort and scroll, as in YouTube Music.
        if (!popBackStack(HomeDestination, inclusive = false, saveState = current != null)) {
            navigate(HomeDestination) { launchSingleTop = true }
        }
        return
    }
    navigate(tab.destination) {
        popUpTo(HomeDestination) {
            inclusive = false
            saveState = current != null && current != YtmTab.Home
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * YouTube Music's bottom bar: Home · Search · Library on the page's near-black, the label always
 * shown, the open tab in white and the others grey. No indicator pill.
 */
@Composable
fun AppBottomNavigationBar(
    navController: NavController,
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
) {
    val current = currentTab(navController)
    Row(
        Modifier
            .fillMaxWidth()
            .background(BarColor)
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YtmTab.entries.forEach { tab ->
            val color = if (tab == current) Color.White else Unselected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = tab == current, role = Role.Tab) {
                        navController.selectTab(tab, current, reloadDestinationIfNeeded)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(tab.title), style = typo().bodySmall.copy(fontSize = 12.sp), color = color, maxLines = 1)
            }
        }
    }
}

/**
 * YouTube Music's web sidebar, for Desktop and tablets: the menu button and logo, the three
 * tabs as 48dp rows (the open one on a light wash), a divider, "New playlist", then Liked Music
 * and the account's playlists. The menu button folds it to a slim rail of icons, as on the web.
 */
@Composable
fun AppNavigationRail(
    navController: NavController,
    libraryViewModel: LibraryViewModel = koinViewModel(),
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
) {
    val current = currentTab(navController)
    // A landscape phone is short: the rail starts folded there so the page keeps its width.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    var expanded by rememberSaveable { mutableStateOf(windowHeight >= 480.dp) }
    val playlists by libraryViewModel.youTubePlaylists.collectAsStateWithLifecycle()
    val localPlaylists by libraryViewModel.localPlaylists.collectAsStateWithLifecycle()
    val loggedIn by libraryViewModel.loggedIn.collectAsStateWithLifecycle()
    var showNewPlaylist by remember { mutableStateOf(false) }
    // The view model fetches the account's playlists when it is created; no second fetch here.
    if (showNewPlaylist) {
        NewPlaylistDialog(onDismiss = { showNewPlaylist = false }) { title ->
            libraryViewModel.createPlaylist(title)
            showNewPlaylist = false
        }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .background(BarColor)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(if (expanded) 240.dp else 72.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(SimpIcons.Menu, contentDescription = stringResource(Res.string.ytm_guide), tint = Color.White)
            }
            if (expanded) {
                Spacer(Modifier.width(4.dp))
                YtmLogo()
            }
        }
        YtmTab.entries.forEach { tab ->
            val selected = tab == current
            if (expanded) {
                Row(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab) { navController.selectTab(tab, current, reloadDestinationIfNeeded) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(stringResource(tab.title), style = typo().bodyLarge.copy(fontSize = 16.sp), color = Color.White, maxLines = 1)
                }
            } else {
                Column(
                    Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab) { navController.selectTab(tab, current, reloadDestinationIfNeeded) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(tab.title), style = typo().bodySmall.copy(fontSize = 10.sp), color = Color.White, maxLines = 1)
                }
            }
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = Color.White.copy(alpha = 0.15f))
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(role = Role.Button) { showNewPlaylist = true },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SimpIcons.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.ytm_new_playlist), style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item(key = "liked") {
                    // Signed out, Liked music is the songs liked on this device, as in Library.
                    GuidePlaylist(stringResource(Res.string.ytm_liked_music), stringResource(Res.string.ytm_auto_playlist)) {
                        if (loggedIn) {
                            navController.navigate(PlaylistDestination(playlistId = "VLLM")) { launchSingleTop = true }
                        } else {
                            navController.navigate(LibraryDynamicPlaylistDestination(type = "favorite")) { launchSingleTop = true }
                        }
                    }
                }
                items(localPlaylists, key = { "l-" + it.id }) { p ->
                    GuidePlaylist(p.title, stringResource(Res.string.playlist)) { navController.navigate(LocalPlaylistDestination(id = p.id)) { launchSingleTop = true } }
                }
                items(playlists.orEmpty().filter { it.browseId != "VLLM" && it.browseId != "LM" }, key = { "y-" + it.browseId }) { p ->
                    GuidePlaylist(p.title, p.author.ifBlank { p.itemCount }) {
                        navController.navigate(PlaylistDestination(playlistId = p.browseId, isYourYouTubePlaylist = true)) { launchSingleTop = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePlaylist(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = typo().bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = typo().bodySmall.copy(fontSize = 12.sp), color = Unselected, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

