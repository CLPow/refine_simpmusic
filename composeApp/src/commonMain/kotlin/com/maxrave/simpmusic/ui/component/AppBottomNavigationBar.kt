package com.maxrave.simpmusic.ui.component

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
import com.maxrave.simpmusic.ui.icon.Explore
import com.maxrave.simpmusic.ui.icon.Home
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.ExploreDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.explore
import simpmusic.composeapp.generated.resources.home
import simpmusic.composeapp.generated.resources.library
import kotlin.reflect.KClass

private val BarColor = Color(0xFF030303)
private val Unselected = Color(0xFFAAAAAA)

/** YouTube Music's three tabs (Samples is left out: Refine is songs only). */
enum class YtmTab(
    val destination: Any,
    val title: StringResource,
    val icon: ImageVector,
) {
    Home(HomeDestination, Res.string.home, SimpIcons.Home),
    Explore(ExploreDestination, Res.string.explore, SimpIcons.Explore),
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
    navigate(tab.destination) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * YouTube Music's bottom bar: Home · Explore · Library on the page's near-black, the label always
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
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
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
 * The same tabs as a side rail, for tablets and Desktop: YouTube Music's web layout keeps them in
 * a column on the left, icon above label.
 */
@Composable
fun AppNavigationRail(
    navController: NavController,
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
) {
    val current = currentTab(navController)
    Column(
        Modifier.fillMaxHeight().width(80.dp).background(BarColor).padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YtmTab.entries.forEach { tab ->
            val selected = tab == current
            Column(
                Modifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .background(if (selected) Color.White.copy(alpha = 0.1f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable { navController.selectTab(tab, current, reloadDestinationIfNeeded) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(tab.title), style = typo().bodySmall.copy(fontSize = 11.sp), color = Color.White, maxLines = 1)
            }
        }
    }
}
