package com.maxrave.simpmusic.ui.component.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.podcast.toTrack
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.Bookmark
import com.maxrave.simpmusic.ui.icon.BookmarkBorder
import com.maxrave.simpmusic.ui.icon.Info
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Podcasts
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.EpisodeDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.podcast_episode_details
import simpmusic.composeapp.generated.resources.podcast_go_to_podcast
import simpmusic.composeapp.generated.resources.podcast_hr_min_left
import simpmusic.composeapp.generated.resources.podcast_min_left
import simpmusic.composeapp.generated.resources.podcast_played
import simpmusic.composeapp.generated.resources.podcast_remove_from_later
import simpmusic.composeapp.generated.resources.podcast_save_for_later
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_share

fun playEpisodes(
    sharedViewModel: SharedViewModel,
    episodes: List<PodcastEpisode>,
    start: PodcastEpisode,
    podcastId: String? = start.podcastId,
    podcastTitle: String? = start.podcastTitle,
) {
    val list = episodes.ifEmpty { listOf(start) }
    val index = list.indexOfFirst { it.videoId == start.videoId }.takeIf { it >= 0 } ?: return
    val repository = KoinPlatform.getKoin().get<PodcastRepository>()
    sharedViewModel.viewModelScope.launch {
        repository.ensureEpisodes(listOf(list[index]))
        val tracks = list.map { it.toTrack() }
        sharedViewModel.setQueueData(
            QueueData.Data(
                listTracks = ArrayList(tracks),
                firstPlayedTrack = tracks[index],
                playlistId = podcastId,
                playlistName = podcastTitle,
                playlistType = PlaylistType.PODCAST,
                continuation = null,
            ),
        )
        sharedViewModel.loadMediaItem(tracks[index], Config.PLAYLIST_CLICK, index)
        if (list.size > 1) repository.ensureEpisodes(list)
    }
}

fun openEpisode(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    episode: PodcastEpisode,
) {
    val repository = KoinPlatform.getKoin().get<PodcastRepository>()
    sharedViewModel.viewModelScope.launch { repository.ensureEpisodes(listOf(episode)) }
    navController.navigate(EpisodeDestination(videoId = episode.videoId, podcastId = episode.podcastId))
}

fun episodeFromContent(content: Content): PodcastEpisode =
    PodcastEpisode(
        videoId = content.videoId.orEmpty(),
        title = content.title,
        podcastId = content.album?.id?.takeIf { it.startsWith("MPSP") },
        podcastTitle = content.album?.name,
        author = null,
        description = content.description,
        createdDay = null,
        durationString = null,
        thumbnails = content.thumbnails,
    )

fun episodeShareUrl(videoId: String): String = "https://music.youtube.com/watch?v=$videoId"

fun podcastShareUrl(podcastId: String): String = "https://music.youtube.com/playlist?list=${podcastId.removePrefix("MPSP")}"

@Composable
fun episodeStatus(
    entity: EpisodeEntity?,
    durationString: String?,
): String? {
    if (entity?.played == true) return stringResource(Res.string.podcast_played)
    if (entity != null && entity.positionMs > 0 && entity.durationMs > entity.positionMs) {
        val leftMinutes = ((entity.durationMs - entity.positionMs + 59_999) / 60_000).toInt()
        return if (leftMinutes >= 60) {
            stringResource(Res.string.podcast_hr_min_left, leftMinutes / 60, leftMinutes % 60)
        } else {
            stringResource(Res.string.podcast_min_left, leftMinutes)
        }
    }
    return durationString?.takeIf { it.isNotBlank() }
}

@Composable
fun EpisodeProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(YtmRed))
    }
}

@Composable
fun EpisodeArtwork(
    url: String?,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
fun EpisodeRow(
    episode: PodcastEpisode,
    entity: EpisodeEntity?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    showPodcast: Boolean = false,
) {
    val status = episodeStatus(entity, episode.durationString ?: entity?.durationString)
    val progress = entity?.progress ?: 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            EpisodeArtwork(episode.thumbnails.lastOrNull()?.url, 120.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = if (isCurrent) YtmRed else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOfNotNull(
                        if (showPodcast) episode.podcastTitle else null,
                        episode.createdDay,
                        status,
                    ).filter { it.isNotBlank() }.joinToString(" • ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress > 0f && entity?.played != true) {
                    Spacer(Modifier.height(6.dp))
                    EpisodeProgressBar(progress, Modifier.fillMaxWidth(0.6f))
                }
            }
            IconButton(onClick = onMore) {
                Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Ytm.TextSecondary)
            }
        }
        if (!episode.description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                episode.description.orEmpty(),
                style = typo().bodyMedium,
                color = Ytm.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SimpIcons.PlayArrow, contentDescription = stringResource(Res.string.ytm_play), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeMenuSheet(
    episode: PodcastEpisode,
    savedForLater: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleLater: () -> Unit,
    onOpenEpisode: (() -> Unit)?,
    onOpenPodcast: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ytm.Surface,
        dragHandle = null,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeArtwork(episode.thumbnails.lastOrNull()?.url, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = typo().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.podcastTitle?.let {
                    Text(it, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = Ytm.Divider)
        MenuRow(SimpIcons.PlayArrow, stringResource(Res.string.ytm_play)) {
            onDismiss()
            onPlay()
        }
        MenuRow(
            if (savedForLater) SimpIcons.Bookmark else SimpIcons.BookmarkBorder,
            stringResource(if (savedForLater) Res.string.podcast_remove_from_later else Res.string.podcast_save_for_later),
        ) {
            onDismiss()
            onToggleLater()
        }
        onOpenEpisode?.let { open ->
            MenuRow(SimpIcons.Info, stringResource(Res.string.podcast_episode_details)) {
                onDismiss()
                open()
            }
        }
        onOpenPodcast?.let { open ->
            MenuRow(SimpIcons.Podcasts, stringResource(Res.string.podcast_go_to_podcast)) {
                onDismiss()
                open()
            }
        }
        MenuRow(SimpIcons.Share, stringResource(Res.string.ytm_share)) {
            onDismiss()
            shareUrl(episode.title, episodeShareUrl(episode.videoId))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun EpisodeMenuFor(
    episode: PodcastEpisode,
    navController: NavController,
    sharedViewModel: SharedViewModel,
    onDismiss: () -> Unit,
    queue: List<PodcastEpisode> = listOf(episode),
    showOpenEpisode: Boolean = true,
    showOpenPodcast: Boolean = true,
) {
    val repository = koinInject<PodcastRepository>()
    val entity by remember(episode.videoId) { repository.observeEpisode(episode.videoId) }.collectAsStateWithLifecycle(null)
    val savedForLater = entity?.savedForLaterAt != null
    EpisodeMenuSheet(
        episode = episode,
        savedForLater = savedForLater,
        onDismiss = onDismiss,
        onPlay = { playEpisodes(sharedViewModel, queue, episode) },
        onToggleLater = { sharedViewModel.viewModelScope.launch { repository.setSavedForLater(episode, !savedForLater) } },
        onOpenEpisode = if (showOpenEpisode) ({ openEpisode(navController, sharedViewModel, episode) }) else null,
        onOpenPodcast =
            episode.podcastId?.takeIf { showOpenPodcast }?.let { id ->
                { navController.navigate(PodcastDestination(podcastId = id)) }
            },
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(24.dp))
        Text(label, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White)
    }
}
