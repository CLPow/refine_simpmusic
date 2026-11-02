package com.maxrave.simpmusic.ui.navigation.graph

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.maxrave.simpmusic.ui.navigation.destination.home.RecentlySongsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsCategoryDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.screen.home.RecentlySongsScreen
import com.maxrave.simpmusic.ui.screen.home.SettingScreen
import com.maxrave.simpmusic.ui.screen.home.SettingsCategory
import com.maxrave.simpmusic.ui.screen.home.SettingsHomeScreen

fun NavGraphBuilder.homeScreenGraph(
    innerPadding: PaddingValues,
    navController: NavController,
) {
    composable<RecentlySongsDestination> {
        RecentlySongsScreen(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
    composable<SettingsDestination> {
        SettingsHomeScreen(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
    composable<SettingsCategoryDestination> { entry ->
        SettingScreen(
            navController = navController,
            innerPadding = innerPadding,
            category = SettingsCategory.fromKey(entry.toRoute<SettingsCategoryDestination>().category),
        )
    }
}
