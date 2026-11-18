package com.maxrave.simpmusic.ui.screen.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.toPodcastEpisode
import com.maxrave.simpmusic.ui.component.podcast.EpisodeMenuFor
import com.maxrave.simpmusic.ui.component.podcast.EpisodeRow
import com.maxrave.simpmusic.ui.component.podcast.openEpisode
import com.maxrave.simpmusic.ui.component.podcast.playEpisodes
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.EpisodesForLaterViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.podcast_episodes_for_later
import simpmusic.composeapp.generated.resources.podcast_later_empty

@Composable
fun EpisodesForLaterScreen(
    navController: NavController,
    viewModel: EpisodesForLaterViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val entities by viewModel.episodes.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    val episodes = remember(entities) { entities.map { it.toPodcastEpisode() } }
    var menuEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    val title = stringResource(Res.string.podcast_episodes_for_later)

    menuEpisode?.let { episode ->
        EpisodeMenuFor(
            episode = episode,
            navController = navController,
            sharedViewModel = sharedViewModel,
            onDismiss = { menuEpisode = null },
            queue = episodes,
        )
    }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(
            navController = navController,
            title = title,
            onBack = { navController.navigateUp() },
        )
        if (episodes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp)) {
                Text(stringResource(Res.string.podcast_later_empty), style = typo().bodyLarge, color = Ytm.TextSecondary)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(episodes, key = { it.videoId }) { episode ->
                val entity = entities.firstOrNull { it.videoId == episode.videoId }
                EpisodeRow(
                    episode = episode,
                    entity = entity,
                    isCurrent = episode.videoId == playingVideoId,
                    onClick = { openEpisode(navController, sharedViewModel, episode) },
                    onPlay = { playEpisodes(sharedViewModel, episodes, episode, null, title) },
                    onMore = { menuEpisode = episode },
                    showPodcast = true,
                )
            }
        }
    }
}
