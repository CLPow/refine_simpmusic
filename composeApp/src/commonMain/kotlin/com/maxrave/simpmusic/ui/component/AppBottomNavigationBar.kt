package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import com.maxrave.simpmusic.extension.greyScale
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.MixForYouDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.LocalIsYtmTheme
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.*
import kotlin.reflect.KClass

/**
 * The phone bottom bar with liquid glass off, laid out like YouTube Music's: a full-width flat bar
 * on the page colour, a hairline above it, three equal tabs — Home · Explore · Library — with the
 * label always shown and the selected tab marked by a filled pill behind its icon.
 *
 * Mix for you and Analytics no longer take tab slots (four or five tabs squeezed every label);
 * they live inside Library. [showAnalyticsTab] and [showMixForYouTab] are kept for the rail and
 * the glass bar, which still read them.
 */
@Composable
fun AppBottomNavigationBar(
    startDestination: Any = HomeDestination,
    navController: NavController,
    isTranslucentBackground: Boolean = false,
    showAnalyticsTab: Boolean = false,
    showMixForYouTab: Boolean = false,
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val tabs = listOf(BottomNavScreen.Home, BottomNavScreen.Search, BottomNavScreen.Library)
    var selectedIndex by rememberSaveable {
        mutableIntStateOf(
            when (startDestination) {
                is SearchDestination -> BottomNavScreen.Search.ordinal
                is LibraryDestination -> BottomNavScreen.Library.ordinal
                else -> BottomNavScreen.Home.ordinal
            },
        )
    }
    // Follow the back stack, so a deep link or a back press lands the highlight on the right tab.
    LaunchedEffect(currentBackStackEntry) {
        val hierarchy = currentBackStackEntry?.destination?.hierarchy ?: return@LaunchedEffect
        tabs.firstOrNull { tab -> hierarchy.any { it.hasRoute(tab.destination::class) } }?.let { selectedIndex = it.ordinal }
    }
    val selectTab: (BottomNavScreen) -> Unit = { screen ->
        if (selectedIndex == screen.ordinal &&
            currentBackStackEntry?.destination?.hierarchy?.any { it.hasRoute(screen.destination::class) } == true
        ) {
            reloadDestinationIfNeeded(screen.destination::class)
        } else {
            selectedIndex = screen.ordinal
            navController.navigate(screen.destination) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val isYtm = LocalIsYtmTheme.current
    val barColor = MaterialTheme.colorScheme.background.copy(alpha = if (isTranslucentBackground) 0.88f else 1f)
    Column(Modifier.fillMaxWidth().background(barColor)) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { screen ->
                val selected = selectedIndex == screen.ordinal
                val contentColor =
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                val pillWidth by animateDpAsState(if (selected) PillWidth else PillHeight, label = "navPill")
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { selectTab(screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = pillWidth, height = PillHeight)
                                .clip(RoundedCornerShape(PillHeight / 2))
                                // YouTube Music marks the tab by colour alone; the pill is for the other themes.
                                .background(if (selected && !isYtm) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides contentColor) {
                            screen.icon()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(screen.title),
                        style = typo().bodySmall,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val BarHeight = 68.dp
private val PillHeight = 32.dp
private val PillWidth = 60.dp

@Composable
fun AppNavigationRail(
    startDestination: Any = HomeDestination,
    navController: NavController,
    showAnalyticsTab: Boolean = false,
    showMixForYouTab: Boolean = false,
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    // See the note in AppBottomNavigationBar: `ordinal` is the tab's identity, not its position.
    val bottomNavScreens =
        listOfNotNull(
            BottomNavScreen.Home,
            BottomNavScreen.MixForYou.takeIf { showMixForYouTab },
            BottomNavScreen.Analytics.takeIf { showAnalyticsTab },
            BottomNavScreen.Library,
            BottomNavScreen.Search,
        )
    var selectedIndex by rememberSaveable {
        mutableIntStateOf(
            when (startDestination) {
                is HomeDestination -> BottomNavScreen.Home.ordinal
                is SearchDestination -> BottomNavScreen.Search.ordinal
                is LibraryDestination -> BottomNavScreen.Library.ordinal
                is AnalyticsDestination -> BottomNavScreen.Analytics.ordinal
                is MixForYouDestination -> BottomNavScreen.MixForYou.ordinal
                else -> BottomNavScreen.Home.ordinal // Default to Home if not recognized
            },
        )
    }
    // A tab can disappear from the list under the user: tracking gets turned off while Analytics is
    // selected, or the YouTube session ends while Mix for you is. Fall back to Home in both cases so
    // nothing is left highlighted.
    LaunchedEffect(showAnalyticsTab, showMixForYouTab) {
        if ((!showAnalyticsTab && selectedIndex == BottomNavScreen.Analytics.ordinal) ||
            (!showMixForYouTab && selectedIndex == BottomNavScreen.MixForYou.ordinal)
        ) {
            selectedIndex = BottomNavScreen.Home.ordinal
        }
    }
    NavigationRail {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.mono),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .height(32.dp)
                            .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        bottomNavScreens.forEach { screen ->
            NavigationRailItem(
                icon = screen.icon,
                label = {
                    Text(
                        stringResource(screen.title),
                        style =
                            if (selectedIndex == screen.ordinal) {
                                typo().bodySmall
                            } else {
                                typo().bodySmall.greyScale()
                            },
                    )
                },
                selected = selectedIndex == screen.ordinal,
                onClick = {
                    if (selectedIndex == screen.ordinal) {
                        if (currentBackStackEntry?.destination?.hierarchy?.any {
                                it.hasRoute(screen.destination::class)
                            } == true
                        ) {
                            reloadDestinationIfNeeded(
                                screen.destination::class,
                            )
                        } else {
                            navController.navigate(screen.destination)
                        }
                    } else {
                        selectedIndex = screen.ordinal
                        navController.navigate(screen.destination) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}
