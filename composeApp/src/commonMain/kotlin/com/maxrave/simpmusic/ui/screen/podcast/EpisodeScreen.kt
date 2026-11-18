package com.maxrave.simpmusic.ui.screen.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.mediaservice.PodcastPlayback
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.ui.component.YtmRoundAction
import com.maxrave.simpmusic.ui.component.podcast.EpisodeArtwork
import com.maxrave.simpmusic.ui.component.podcast.EpisodeProgressBar
import com.maxrave.simpmusic.ui.component.podcast.episodeShareUrl
import com.maxrave.simpmusic.ui.component.podcast.episodeStatus
import com.maxrave.simpmusic.ui.component.podcast.playEpisodes
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.Bookmark
import com.maxrave.simpmusic.ui.icon.BookmarkBorder
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.EpisodeViewModel
import com.maxrave.simpmusic.viewModel.PodcastLoadState
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.podcast_episode
import simpmusic.composeapp.generated.resources.podcast_episode_unavailable
import simpmusic.composeapp.generated.resources.podcast_remove_from_later
import simpmusic.composeapp.generated.resources.podcast_resume
import simpmusic.composeapp.generated.resources.podcast_save_for_later
import simpmusic.composeapp.generated.resources.ytm_back
import simpmusic.composeapp.generated.resources.ytm_pause
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_share

@Composable
fun EpisodeScreen(
    videoId: String,
    podcastId: String?,
    navController: NavController,
    viewModel: EpisodeViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entity by viewModel.entity.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()

    LaunchedEffect(videoId) { viewModel.load(videoId) }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        when (uiState.loadState) {
            PodcastLoadState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }

            PodcastLoadState.Error ->
                YtmErrorPage(
                    onBack = { navController.navigateUp() },
                    message = stringResource(Res.string.podcast_episode_unavailable),
                ) { viewModel.retry() }

            PodcastLoadState.Success -> {
                val episode = uiState.episode ?: return@Box
                val showId = episode.podcastId ?: podcastId
                val isCurrent = playingVideoId == videoId
                val isPlaying = isCurrent && controllerState.isPlaying
                val savedForLater = entity?.savedForLaterAt != null
                val canResume =
                    entity?.let { PodcastPlayback.shouldResume(it.positionMs, it.durationMs) } == true
                val status = episodeStatus(entity, episode.durationString ?: entity?.durationString)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(bottom = 32.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                    EpisodeArtwork(
                        episode.thumbnails.lastOrNull()?.url,
                        320.dp,
                        Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        episode.title,
                        style = typo().titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    episode.podcastTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            title,
                            style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .then(
                                        if (showId != null) {
                                            Modifier.clickable { navController.navigate(PodcastDestination(podcastId = showId)) }
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                    }
                    val meta = listOfNotNull(stringResource(Res.string.podcast_episode), episode.createdDay, status).filter { it.isNotBlank() }.joinToString(" • ")
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = typo().bodyMedium, color = Ytm.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                    val progress = entity?.progress ?: 0f
                    if (progress > 0f && entity?.played != true) {
                        Spacer(Modifier.height(8.dp))
                        EpisodeProgressBar(progress, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable(role = Role.Button) {
                                    if (isCurrent) {
                                        sharedViewModel.onUIEvent(UIEvent.PlayPause)
                                    } else {
                                        playEpisodes(sharedViewModel, listOf(episode), episode, showId, episode.podcastTitle)
                                    }
                                }.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    when {
                                        isPlaying -> Res.string.ytm_pause
                                        canResume -> Res.string.podcast_resume
                                        else -> Res.string.ytm_play
                                    },
                                ),
                                style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = Color.Black,
                            )
                        }
                        YtmRoundAction(
                            if (savedForLater) SimpIcons.Bookmark else SimpIcons.BookmarkBorder,
                            onClick = { viewModel.setSavedForLater(!savedForLater) },
                            label = stringResource(if (savedForLater) Res.string.podcast_remove_from_later else Res.string.podcast_save_for_later),
                        )
                        YtmRoundAction(
                            SimpIcons.Share,
                            onClick = { shareUrl(episode.title, episodeShareUrl(episode.videoId)) },
                            label = stringResource(Res.string.ytm_share),
                        )
                    }
                    if (!episode.description.isNullOrBlank()) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            episode.description.orEmpty(),
                            style = typo().bodyMedium.copy(fontSize = 14.sp),
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
