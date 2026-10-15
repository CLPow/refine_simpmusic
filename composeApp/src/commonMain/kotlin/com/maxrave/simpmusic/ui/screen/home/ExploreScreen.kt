package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.common.CHART_SUPPORTED_COUNTRY
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmChips
import com.maxrave.simpmusic.ui.component.ytm.YtmOutlinedPill
import com.maxrave.simpmusic.ui.component.ytm.YtmShelf
import com.maxrave.simpmusic.ui.component.ytm.YtmShelfHeader
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.icon.Album
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Tune
import com.maxrave.simpmusic.ui.icon.TrendingUp
import com.maxrave.simpmusic.ui.navigation.destination.home.ExploreSectionDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.ExploreViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * YouTube Music's Explore: the three big buttons (New releases, Charts, Moods & genres), then the
 * page's own shelves, with the moods as YouTube Music's striped tiles.
 */
@Composable
fun ExploreScreen(
    navController: NavController,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val moods by viewModel.moods.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "buttons") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExploreButton(SimpIcons.Album, "New releases") {
                        navController.navigate(ExploreSectionDestination(ExploreSectionDestination.NEW_RELEASES))
                    }
                    ExploreButton(SimpIcons.TrendingUp, "Charts") {
                        navController.navigate(ExploreSectionDestination(ExploreSectionDestination.CHARTS))
                    }
                    ExploreButton(SimpIcons.Tune, "Moods & genres") {
                        navController.navigate(ExploreSectionDestination(ExploreSectionDestination.MOODS))
                    }
                }
            }
            val moodItems = moods?.sections?.flatMap { it.items }.orEmpty()
            if (moodItems.isNotEmpty()) {
                item(key = "moods") {
                    YtmShelfHeader(
                        title = "Moods & genres",
                        actionLabel = "See all",
                        onAction = { navController.navigate(ExploreSectionDestination(ExploreSectionDestination.MOODS)) },
                    )
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp * 4 + 24.dp),
                    ) {
                        items(moodItems) { mood ->
                            MoodTile(mood, Modifier.width(160.dp)) {
                                navController.navigate(MoodDestination(params = mood.params))
                            }
                        }
                    }
                }
            }
            val list = shelves
            when {
                list == null && !failed -> item(key = "loading") { Loading() }
                list.isNullOrEmpty() && failed ->
                    item(key = "failed") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            YtmOutlinedPill("Retry") { viewModel.loadExplore() }
                        }
                    }
                else -> items(list.orEmpty(), key = { it.title }) { shelf -> YtmShelf(item = shelf, navController = navController) }
            }
        }
    }
}

/** One of the three large buttons at the top of Explore. */
@Composable
private fun ExploreButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ytm.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(20.dp))
        Text(label, style = typo().bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = Color.White)
    }
}

/** YouTube Music's mood tile: a dark tile with the genre's colour as a stripe down its left edge. */
@Composable
private fun MoodTile(
    mood: MoodItem,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Ytm.Surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(Color(mood.stripeColor)))
        Spacer(Modifier.width(12.dp))
        Text(
            mood.title,
            style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
    }
}

/** The pages behind Explore's three buttons. */
@Composable
fun ExploreSectionScreen(
    section: String,
    navController: NavController,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    LaunchedEffect(section) {
        when (section) {
            ExploreSectionDestination.NEW_RELEASES -> viewModel.loadNewReleases()
            ExploreSectionDestination.CHARTS -> viewModel.loadChart()
            else -> viewModel.loadMoods()
        }
    }
    val title =
        when (section) {
            ExploreSectionDestination.NEW_RELEASES -> "New releases"
            ExploreSectionDestination.CHARTS -> "Charts"
            else -> "Moods & genres"
        }
    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = title, onBack = { navController.navigateUp() })
        when (section) {
            ExploreSectionDestination.NEW_RELEASES -> NewReleasesPage(viewModel, navController)
            ExploreSectionDestination.CHARTS -> ChartsPage(viewModel, navController)
            else -> MoodsPage(viewModel, navController)
        }
    }
}

@Composable
private fun NewReleasesPage(
    viewModel: ExploreViewModel,
    navController: NavController,
) {
    val releases by viewModel.newReleases.collectAsStateWithLifecycle()
    val list = releases
    if (list == null) {
        Loading()
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.title }) { shelf -> YtmShelf(item = shelf, navController = navController) }
    }
}

@Composable
private fun ChartsPage(
    viewModel: ExploreViewModel,
    navController: NavController,
) {
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val region by viewModel.chartRegion.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "regions") {
            val codes = CHART_SUPPORTED_COUNTRY.items.toList()
            val names = CHART_SUPPORTED_COUNTRY.itemsData.toList()
            YtmChips(
                labels = names,
                selected = codes.indexOf(region).takeIf { it >= 0 },
                onSelect = { viewModel.loadChart(codes[it]) },
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        val data = chart
        if (data == null) {
            item(key = "loading") { Loading() }
            return@LazyColumn
        }
        data.listChartItem.forEach { group ->
            item(key = "group-${group.title}") {
                YtmShelfHeader(title = group.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(group.playlists) { playlist ->
                        Column(
                            Modifier
                                .width(152.dp)
                                .clickable { navController.navigate(PlaylistDestination(playlistId = playlist.id)) },
                        ) {
                            Thumb(playlist.thumbnails.lastOrNull()?.url, 152.dp, RoundedCornerShape(4.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                playlist.title,
                                style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(playlist.author, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
        val artists = data.artists.itemArtists
        if (artists.isNotEmpty()) {
            item(key = "artists-header") { YtmShelfHeader(title = "Top artists") }
            itemsIndexed(artists, key = { _, a -> a.browseId }) { _, artist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(ArtistDestination(channelId = artist.browseId)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        artist.rank,
                        style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.width(32.dp),
                    )
                    Thumb(artist.thumbnails.lastOrNull()?.url, 48.dp, CircleShape)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.title, style = typo().bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist.subscribers, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodsPage(
    viewModel: ExploreViewModel,
    navController: NavController,
) {
    val moods by viewModel.moods.collectAsStateWithLifecycle()
    val sections = moods?.sections
    if (sections == null) {
        Loading()
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "h-${section.title}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    section.title,
                    style = typo().headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
            }
            items(section.items, key = { "${section.title}-${it.params}" }) { mood ->
                MoodTile(mood, Modifier.fillMaxWidth()) { navController.navigate(MoodDestination(params = mood.params)) }
            }
        }
    }
}
