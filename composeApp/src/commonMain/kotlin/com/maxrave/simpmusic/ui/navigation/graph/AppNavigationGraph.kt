package com.maxrave.simpmusic.ui.navigation.graph

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.screen.home.HomeScreen
import com.maxrave.simpmusic.ui.screen.library.LibraryScreen
import com.maxrave.simpmusic.ui.screen.other.SearchScreen

@Composable
@ExperimentalMaterial3Api
@ExperimentalFoundationApi
fun AppNavigationGraph(
    innerPadding: PaddingValues,
    navController: NavHostController,
    startDestination: Any = HomeDestination,
    hideNavBar: () -> Unit = { },
    showNavBar: (shouldShowNowPlayingSheet: Boolean) -> Unit = { },
    showNowPlayingSheet: () -> Unit = {},
    onScrolling: (onTop: Boolean) -> Unit = {},
) {
    NavHost(
        navController,
        startDestination = startDestination,
        // Movement follows where things are. Between tabs the page slides the way the tab sits
        // in the bar (Home -> Library comes in from the right, back the other way); a page opened
        // on top slides in from the right and goes back out to the right. Every move used to slide
        // in from the left whatever its direction.
        enterTransition = {
            val from = tabIndexOf(initialState.destination)
            val to = tabIndexOf(targetState.destination)
            val sign = if (from != null && to != null && to < from) -1 else 1
            fadeIn(tween(TAB_MS)) + slideInHorizontally(tween(TAB_MS)) { sign * it / 3 }
        },
        exitTransition = {
            val from = tabIndexOf(initialState.destination)
            val to = tabIndexOf(targetState.destination)
            val sign = if (from != null && to != null && to < from) 1 else -1
            fadeOut(tween(TAB_MS)) + slideOutHorizontally(tween(TAB_MS)) { sign * it / 3 }
        },
        popEnterTransition = {
            fadeIn(tween(TAB_MS)) + slideInHorizontally(tween(TAB_MS)) { -it / 3 }
        },
        popExitTransition = {
            fadeOut(tween(TAB_MS)) + slideOutHorizontally(tween(TAB_MS)) { it / 3 }
        },
    ) {
        // Bottom bar destinations
        composable<HomeDestination> {
            HomeScreen(
                onScrolling = onScrolling,
                navController = navController,
            )
        }
        composable<SearchDestination> {
            SearchScreen(
                navController = navController,
            )
        }
        composable<LibraryDestination> {
            LibraryScreen(
                navController = navController,
            )
        }
        // Home screen graph
        homeScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
        )
        // Library screen graph
        libraryScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
        )
        // List screen graph
        listScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
        )
        // Login screen graph
        loginScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
            hideBottomBar = hideNavBar,
            showBottomBar = {
                showNavBar(false)
            },
        )
    }
}

private const val TAB_MS = 250

/** Position of [destination] in the bottom bar (Home, Search, Library), or null for other pages. */
private fun tabIndexOf(destination: NavDestination): Int? =
    when {
        destination.hasRoute(HomeDestination::class) -> 0
        destination.hasRoute(SearchDestination::class) -> 1
        destination.hasRoute(LibraryDestination::class) -> 2
        else -> null
    }
