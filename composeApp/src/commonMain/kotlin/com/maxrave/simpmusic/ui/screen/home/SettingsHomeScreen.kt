package com.maxrave.simpmusic.ui.screen.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.maxrave.simpmusic.extension.copy
import com.maxrave.simpmusic.ui.component.RippleIconButton
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.ArrowForwardIos
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.Explore
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.Info
import com.maxrave.simpmusic.ui.icon.Lyrics
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Sync
import com.maxrave.simpmusic.ui.icon.Tune
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsCategoryDestination
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.settings
import simpmusic.composeapp.generated.resources.settings_cat_about
import simpmusic.composeapp.generated.resources.settings_cat_about_desc
import simpmusic.composeapp.generated.resources.settings_cat_appearance
import simpmusic.composeapp.generated.resources.settings_cat_appearance_desc
import simpmusic.composeapp.generated.resources.settings_cat_content
import simpmusic.composeapp.generated.resources.settings_cat_content_desc
import simpmusic.composeapp.generated.resources.settings_cat_integrations
import simpmusic.composeapp.generated.resources.settings_cat_integrations_desc
import simpmusic.composeapp.generated.resources.settings_cat_lyrics
import simpmusic.composeapp.generated.resources.settings_cat_lyrics_desc
import simpmusic.composeapp.generated.resources.settings_cat_playback
import simpmusic.composeapp.generated.resources.settings_cat_playback_desc
import simpmusic.composeapp.generated.resources.settings_cat_storage
import simpmusic.composeapp.generated.resources.settings_cat_storage_desc

/**
 * Settings, split into pages. The settings used to be one 4 000-line scroll of about a hundred
 * rows; each category now opens only its own sections of [SettingScreen], keyed by the section
 * item keys that screen already used.
 */
enum class SettingsCategory(
    val title: StringResource,
    val description: StringResource,
    val icon: () -> ImageVector,
    val sections: Set<String>,
) {
    APPEARANCE(Res.string.settings_cat_appearance, Res.string.settings_cat_appearance_desc, { SimpIcons.Tune }, setOf("user_interface")),
    PLAYBACK(
        Res.string.settings_cat_playback,
        Res.string.settings_cat_playback_desc,
        { SimpIcons.GraphicEq },
        setOf("audio", "playback", "crossfade_settings"),
    ),
    CONTENT(Res.string.settings_cat_content, Res.string.settings_cat_content_desc, { SimpIcons.Explore }, setOf("content", "listening_history", "proxy")),
    LYRICS(Res.string.settings_cat_lyrics, Res.string.settings_cat_lyrics_desc, { SimpIcons.Lyrics }, setOf("lyrics")),
    INTEGRATIONS(
        Res.string.settings_cat_integrations,
        Res.string.settings_cat_integrations_desc,
        { SimpIcons.Sync },
        setOf("AI", "spotify", "discord", "lastfm", "sponsor_block"),
    ),
    STORAGE(Res.string.settings_cat_storage, Res.string.settings_cat_storage_desc, { SimpIcons.DownloadForOffline }, setOf("storage", "backup")),
    ABOUT(Res.string.settings_cat_about, Res.string.settings_cat_about_desc, { SimpIcons.Info }, setOf("about_us")),
    ;

    companion object {
        fun fromKey(key: String): SettingsCategory = entries.firstOrNull { it.name == key } ?: APPEARANCE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    innerPadding: PaddingValues,
    navController: NavController,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(Res.string.settings), style = typo().titleMedium) },
            navigationIcon = {
                Box(Modifier.padding(horizontal = 5.dp)) {
                    RippleIconButton(SimpIcons.ArrowBackIosNew, Modifier.size(32.dp), true, tint = MaterialTheme.colorScheme.onSurface) {
                        navController.navigateUp()
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LazyColumn(
            contentPadding = innerPadding.copy(top = 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(SettingsCategory.entries, key = { it.name }) { category ->
                SettingsCategoryRow(category) {
                    navController.navigate(SettingsCategoryDestination(category.name))
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(category.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.title), style = typo().titleSmall)
            Text(stringResource(category.description), style = typo().bodySmall, maxLines = 2)
        }
        Icon(SimpIcons.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}
