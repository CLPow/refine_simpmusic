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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.maxrave.domain.utils.toTrack
import com.maxrave.domain.extension.now
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.TrendingUp
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.RecentlySongsDestination
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
import simpmusic.composeapp.generated.resources.booming_listen_now
import simpmusic.composeapp.generated.resources.booming_recommended_artists
import simpmusic.composeapp.generated.resources.booming_shuffle_your_music
import simpmusic.composeapp.generated.resources.good_afternoon
import simpmusic.composeapp.generated.resources.good_evening
import simpmusic.composeapp.generated.resources.good_morning
import simpmusic.composeapp.generated.resources.good_night

/**
 * Booming Music–style home (mardous/BoomingMusic screenshot):
 * bold wordmark with indigo accent, circular quick actions, Shuffle card + cover mosaic,
 * Listen now carousel, Recommended artists row.
 *
 * Data is the existing home shelves — layout and chrome are the change.
 */
@Composable
fun BoomingHomeScreen(
    homeData: List<HomeItem>,
    newRelease: List<HomeItem>,
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

    val mosaic: List<Content> =
        homeData
            .flatMap { it.contents.filterNotNull() }
            .filter { it.thumbnails.isNotEmpty() }
            .take(9)

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(surface)
                .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.app_name),
                        style = typo().headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                        color = onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Music",
                        style = typo().headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                        color = primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
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
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    QuickAction(SimpIcons.Search, onSurface) { navController.navigate(SearchDestination) }
                    QuickAction(SimpIcons.TrendingUp, onSurface) { navController.navigate(AnalyticsDestination) }
                    QuickAction(SimpIcons.History, onSurface) { navController.navigate(RecentlySongsDestination) }
                    QuickAction(SimpIcons.LibraryMusic, onSurface) { navController.navigate(RecentlySongsDestination) }
                }
            }
        }

        item {
            SectionTitle(stringResource(Res.string.booming_for_you))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1.05f)
                            .height(210.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.verticalGradient(listOf(primary, MaterialTheme.colorScheme.primaryContainer)))
                            .clickable { sharedViewModel.onUIEvent(UIEvent.Shuffle) },
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
                    modifier = Modifier.weight(1f).height(210.dp),
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

        item {
            SectionTitle(stringResource(Res.string.booming_listen_now))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val cards = (newRelease + homeData).flatMap { it.contents.filterNotNull() }.filter { it.title.isNotBlank() }
                items(cards) { content ->
                    ListenNowCard(content = content, onClick = { openHomeContent(it, navController, sharedViewModel) })
                }
            }
        }

        item {
            SectionTitle(stringResource(Res.string.booming_recommended_artists))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val artists =
                    homeData
                        .flatMap { it.contents.filterNotNull() }
                        .flatMap { it.artists.orEmpty() }
                        .distinctBy { it.id }
                        .take(12)
                items(artists) { artist ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .width(88.dp)
                                .clickable { artist.id?.let { navController.navigate(ArtistDestination(channelId = it)) } },
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = artist.name.firstOrNull()?.uppercase() ?: "?",
                                style = typo().titleLarge,
                                color = primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(artist.name, style = typo().bodySmall, color = muted, maxLines = 1)
                    }
                }
            }
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
            navController.navigate(
                PlaylistDestination(playlistId = browseId ?: playlistId!!),
            )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
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
private fun ListenNowCard(
    content: Content,
    onClick: (Content) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onClick(content) },
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.1f)) {
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
        Column(modifier = Modifier.padding(12.dp)) {
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
