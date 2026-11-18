package com.maxrave.simpmusic.ui.screen.podcast

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.ui.component.YtmCollectionTopBar
import com.maxrave.simpmusic.ui.component.YtmRoundAction
import com.maxrave.simpmusic.ui.component.podcast.EpisodeMenuFor
import com.maxrave.simpmusic.ui.component.podcast.EpisodeRow
import com.maxrave.simpmusic.ui.component.podcast.openEpisode
import com.maxrave.simpmusic.ui.component.podcast.playEpisodes
import com.maxrave.simpmusic.ui.component.podcast.podcastShareUrl
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.Bookmark
import com.maxrave.simpmusic.ui.icon.BookmarkBorder
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.PodcastLoadState
import com.maxrave.simpmusic.viewModel.PodcastViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.podcast_episodes
import simpmusic.composeapp.generated.resources.podcast_play_latest
import simpmusic.composeapp.generated.resources.podcast_podcast
import simpmusic.composeapp.generated.resources.podcast_show_less
import simpmusic.composeapp.generated.resources.podcast_show_more
import simpmusic.composeapp.generated.resources.podcast_unavailable
import simpmusic.composeapp.generated.resources.ytm_back
import simpmusic.composeapp.generated.resources.ytm_save_to_library
import simpmusic.composeapp.generated.resources.ytm_saved_to_library
import simpmusic.composeapp.generated.resources.ytm_share

@Composable
fun PodcastScreen(
    podcastId: String,
    navController: NavController,
    viewModel: PodcastViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    var menuEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(podcastId) { viewModel.load(podcastId) }

    val show = uiState.show
    val episodes = show?.episodes.orEmpty()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, show?.continuation) {
        if (nearEnd && show?.continuation != null) viewModel.loadMore()
    }

    fun play(episode: PodcastEpisode) = playEpisodes(sharedViewModel, episodes, episode, show?.id, show?.title)

    menuEpisode?.let { episode ->
        EpisodeMenuFor(
            episode = episode,
            navController = navController,
            sharedViewModel = sharedViewModel,
            onDismiss = { menuEpisode = null },
            queue = episodes,
            showOpenPodcast = false,
        )
    }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        when (uiState.loadState) {
            PodcastLoadState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }

            PodcastLoadState.Error ->
                YtmErrorPage(
                    onBack = { navController.navigateUp() },
                    message = stringResource(Res.string.podcast_unavailable),
                ) { viewModel.retry() }

            PodcastLoadState.Success ->
                if (show != null) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                        item(key = "header") {
                            PodcastHeader(
                                thumbnail = show.thumbnails.lastOrNull()?.url,
                                title = show.title,
                                author = show.author?.name,
                                description = show.description,
                                saved = saved,
                                onBack = { navController.navigateUp() },
                                onAuthor =
                                    show.author?.id?.takeIf { it.startsWith("UC") }?.let { id ->
                                        { navController.navigate(ArtistDestination(channelId = id)) }
                                    },
                                onToggleSaved = viewModel::toggleSaved,
                                onPlayLatest = { episodes.firstOrNull()?.let(::play) },
                                onShare = { shareUrl(show.title, podcastShareUrl(show.id)) },
                            )
                        }
                        item(key = "episodes-title") {
                            Text(
                                stringResource(Res.string.podcast_episodes),
                                style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(episodes, key = { index, episode -> "$index-${episode.videoId}" }) { _, episode ->
                            EpisodeRow(
                                episode = episode,
                                entity = progress[episode.videoId],
                                isCurrent = episode.videoId == playingVideoId,
                                onClick = { openEpisode(navController, sharedViewModel, episode) },
                                onPlay = { play(episode) },
                                onMore = { menuEpisode = episode },
                            )
                        }
                        if (uiState.loadingMore) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                    YtmCollectionTopBar(listState, show.title, onBack = { navController.navigateUp() }, onMore = { shareUrl(show.title, podcastShareUrl(show.id)) })
                }
        }
    }
}

@Composable
private fun PodcastHeader(
    thumbnail: String?,
    title: String,
    author: String?,
    description: String?,
    saved: Boolean,
    onBack: () -> Unit,
    onAuthor: (() -> Unit)?,
    onToggleSaved: () -> Unit,
    onPlayLatest: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Thumb(thumbnail, 240.dp, RoundedCornerShape(8.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = typo().titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!author.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                author,
                style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).then(if (onAuthor != null) Modifier.clickable(onClick = onAuthor) else Modifier),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.podcast_podcast), style = typo().bodyMedium, color = Ytm.TextSecondary)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YtmRoundAction(
                if (saved) SimpIcons.Bookmark else SimpIcons.BookmarkBorder,
                onClick = onToggleSaved,
                label = stringResource(if (saved) Res.string.ytm_saved_to_library else Res.string.ytm_save_to_library),
            )
            Row(
                Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(role = Role.Button, onClick = onPlayLatest)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SimpIcons.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(Res.string.podcast_play_latest),
                    style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color.Black,
                )
            }
            YtmRoundAction(SimpIcons.Share, onClick = onShare, label = stringResource(Res.string.ytm_share))
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .animateContentSize(),
            ) {
                Text(
                    description,
                    style = typo().bodyMedium,
                    color = Ytm.TextSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (expanded) Res.string.podcast_show_less else Res.string.podcast_show_more),
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
