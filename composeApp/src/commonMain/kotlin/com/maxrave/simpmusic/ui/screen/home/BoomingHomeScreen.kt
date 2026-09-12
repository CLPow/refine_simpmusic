package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.common.Config
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.extension.now
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Settings
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.TrendingUp
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.RecentlySongsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.app_name
import simpmusic.composeapp.generated.resources.booming_for_you
import simpmusic.composeapp.generated.resources.booming_recommended_artists
import simpmusic.composeapp.generated.resources.booming_shuffle_your_music
import simpmusic.composeapp.generated.resources.good_afternoon
import simpmusic.composeapp.generated.resources.good_evening
import simpmusic.composeapp.generated.resources.good_morning
import simpmusic.composeapp.generated.resources.good_night
import simpmusic.composeapp.generated.resources.settings

/**
 * Booming Music–style chrome on the **same YouTube Music shelves** as classic Home.
 *
 * The feed is rendered once, in order, under each shelf's real title (On repeat, Listen again,
 * Quick picks, Recaps, …). No second pass that re-slices the same contents into invented rows.
 */
@Composable
fun BoomingHomeScreen(
    homeData: List<HomeItem>,
    newRelease: List<HomeItem>,
    chart: Chart?,
    sharedViewModel: SharedViewModel,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hour = now().time.hour

    // Mosaic art only for the Shuffle card’s side — not a second list of the same tracks.
    val mosaic =
        homeData
            .flatMap { it.contents.filterNotNull() }
            .filter { it.thumbnails.isNotEmpty() }
            .take(4)

    val chartArtists = chart?.artists?.itemArtists?.take(10).orEmpty()

    // Drop a New-releases shelf that only repeats what homeData already carries.
    val homeTitles = homeData.map { it.title.trim() }.toSet()
    val extraNewRelease =
        newRelease.filter { shelf ->
            shelf.contents.filterNotNull().isNotEmpty() &&
                shelf.title.trim() !in homeTitles
        }

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(surface)
                .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item(key = "booming-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = stringResource(Res.string.app_name),
                        style = typo().headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                        color = primary,
                    )
                    Text(
                        text =
                            when (hour) {
                                in 6..12 -> stringResource(Res.string.good_morning)
                                in 13..17 -> stringResource(Res.string.good_afternoon)
                                in 18..23 -> stringResource(Res.string.good_evening)
                                else -> stringResource(Res.string.good_night)
                            },
                        style = typo().bodyMedium,
                        color = muted,
                    )
                }
                IconButton(onClick = { navController.navigate(SettingsDestination) }) {
                    Icon(
                        imageVector = SimpIcons.Settings,
                        contentDescription = stringResource(Res.string.settings),
                        tint = onSurface,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QuickAction(SimpIcons.Search, onSurface) { navController.navigate(SearchDestination) }
                QuickAction(SimpIcons.TrendingUp, onSurface) { navController.navigate(AnalyticsDestination) }
                QuickAction(SimpIcons.History, onSurface) { navController.navigate(RecentlySongsDestination) }
            }
        }

        // Shuffle + small mosaic (Booming “For you” card) — chrome only, not a duplicate feed
        item(key = "booming-shuffle") {
            SectionTitle(stringResource(Res.string.booming_for_you))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1.05f)
                            .height(180.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(primary, MaterialTheme.colorScheme.primaryContainer),
                                ),
                            ).clickable { sharedViewModel.onUIEvent(UIEvent.Shuffle) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = SimpIcons.PlayArrow,
                            contentDescription = null,
                            tint = onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(Res.string.booming_shuffle_your_music),
                            style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = onPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(2) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(2) { col ->
                                val content = mosaic.getOrNull(row * 2 + col)
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .clickable {
                                                content?.let { openHomeContent(it, navController, sharedViewModel) }
                                            },
                                ) {
                                    content?.thumbnails?.lastOrNull()?.url?.let { url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Every YouTube Music shelf, once, in feed order, under its real title
        items(
            items = homeData,
            key = { shelf -> "home-" + (shelf.title + shelf.hashCode()) },
        ) { shelf ->
            val rows = shelf.contents.filterNotNull()
            if (rows.isEmpty()) return@items
            SectionTitle(shelf.title.ifBlank { stringResource(Res.string.booming_for_you) })
            ContentRow(rows) { openHomeContent(it, navController, sharedViewModel) }
        }

        // New-releases shelves not already present under another title
        items(
            items = extraNewRelease,
            key = { shelf -> "new-" + shelf.title + shelf.hashCode() },
        ) { shelf ->
            val rows = shelf.contents.filterNotNull()
            if (rows.isEmpty()) return@items
            SectionTitle(shelf.title)
            ContentRow(rows) { openHomeContent(it, navController, sharedViewModel) }
        }

        // Chart artists (photos from the chart payload)
        if (chartArtists.isNotEmpty()) {
            item(key = "booming-artists") {
                SectionTitle(stringResource(Res.string.booming_recommended_artists))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(chartArtists) { artist ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier
                                    .width(88.dp)
                                    .clickable {
                                        navController.navigate(ArtistDestination(channelId = artist.browseId))
                                    },
                        ) {
                            ArtistAvatar(
                                url = artist.thumbnails?.lastOrNull()?.url,
                                name = artist.title,
                                fallbackColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(artist.title, style = typo().bodySmall, color = muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentRow(
    contents: List<Content>,
    onClick: (Content) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(contents) { content ->
            ListenNowCard(content = content, onClick = onClick)
        }
    }
}

private fun openHomeContent(
    content: Content,
    navController: NavController,
    sharedViewModel: SharedViewModel,
) {
    val videoId = content.videoId
    val browseId = content.browseId
    val playlistId = content.playlistId
    when {
        videoId != null -> {
            val track = content.toTrack()
            sharedViewModel.loadMediaItemFromTrack(track, Config.SONG_CLICK)
        }
        browseId != null && browseId.startsWith("MPRE") ->
            navController.navigate(AlbumDestination(browseId))
        playlistId != null || browseId != null ->
            navController.navigate(PlaylistDestination(playlistId = browseId ?: playlistId!!))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ArtistAvatar(
    url: String?,
    name: String,
    fallbackColor: Color,
) {
    Box(
        modifier =
            Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(fallbackColor),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = typo().titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ListenNowCard(
    content: Content,
    onClick: (Content) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(160.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onClick(content) },
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val url = content.thumbnails.lastOrNull()?.url
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = content.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = content.title,
                style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val sub = content.artists?.firstOrNull()?.name.orEmpty()
            if (sub.isNotBlank()) {
                Text(sub, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
