package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmShelfHeader
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.Sensors
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.MoreAlbumsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.ArtistScreenState
import com.maxrave.simpmusic.viewModel.ArtistViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.lazy.itemsIndexed
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.featured_inArtist
import simpmusic.composeapp.generated.resources.ytm_shuffle
import simpmusic.composeapp.generated.resources.ytm_top_songs
import simpmusic.composeapp.generated.resources.ytm_show_all
import simpmusic.composeapp.generated.resources.ytm_singles_eps
import simpmusic.composeapp.generated.resources.ytm_fans_also_like
import simpmusic.composeapp.generated.resources.ytm_about_artist
import simpmusic.composeapp.generated.resources.ytm_back
import simpmusic.composeapp.generated.resources.ytm_subscribed
import simpmusic.composeapp.generated.resources.ytm_subscribe
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.ytm_single

/**
 * YouTube Music's artist page: the artist's picture across the top, fading into the page, the
 * name in large type over its foot, the audience line, then Shuffle, Radio and Subscribe. Below:
 * Top songs, Albums, Singles & EPs, Featured on, Fans might also like and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    channelId: String,
    viewModel: ArtistViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    navController: NavController,
) {
    val state by viewModel.artistScreenState.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val playingVideoId by viewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val albumLabel = stringResource(Res.string.album)
    val singleLabel = stringResource(Res.string.ytm_single)

    LaunchedEffect(channelId) { if (channelId != state.data.channelId) viewModel.browseArtist(channelId) }
    menuTrack?.let { NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = it.toSongEntity()) }

    Box(Modifier.fillMaxSize().background(Ytm.Background)) {
        when (val s = state) {
            is ArtistScreenState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            is ArtistScreenState.Error ->
                YtmErrorPage(onBack = { navController.navigateUp() }) { viewModel.browseArtist(channelId) }
            is ArtistScreenState.Success -> {
                val data = s.data
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item(key = "hero") {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.1f)) {
                            AsyncImage(
                                model = data.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.35f),
                                        0.3f to Color.Transparent,
                                        0.75f to Ytm.Background.copy(alpha = 0.6f),
                                        1f to Ytm.Background,
                                    ),
                                ),
                            )
                            Text(
                                data.title.orEmpty(),
                                style = typo().displaySmall.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "actions") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            val audience = listOfNotNull(data.playCount, data.subscribers).firstOrNull { it.isNotBlank() }
                            if (audience != null) {
                                Text(audience, style = typo().bodyMedium, color = Ytm.TextSecondary)
                                Spacer(Modifier.height(16.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                data.shuffleParam?.let { endpoint ->
                                    ArtistPill(SimpIcons.Shuffle, stringResource(Res.string.ytm_shuffle), filled = true) { viewModel.onShuffleClick(endpoint) }
                                }
                                data.radioParam?.let { endpoint ->
                                    ArtistPill(SimpIcons.Sensors, stringResource(Res.string.radio), filled = false) { viewModel.onRadioClick(endpoint) }
                                }
                                Spacer(Modifier.weight(1f))
                                SubscribeButton(followed) {
                                    data.channelId?.let { viewModel.updateFollowed(if (followed) 0 else 1, it) }
                                }
                            }
                        }
                    }
                    if (data.popularSongs.isNotEmpty()) {
                        item(key = "songs-h") {
                            YtmShelfHeader(
                                title = stringResource(Res.string.ytm_top_songs),
                                actionLabel = if (data.listSongParam != null) stringResource(Res.string.ytm_show_all) else null,
                                onAction = data.listSongParam?.let { id -> { navController.navigate(PlaylistDestination(id)) } },
                            )
                        }
                        val topSongs = data.popularSongs.take(5)
                        itemsIndexed(topSongs, key = { index, song -> "song-$index-${song.videoId}" }) { index, song ->
                            YtmSongRow(
                                title = song.title,
                                subtitle = listOfNotNull(song.artists?.joinToString(", ") { it.name }, song.album?.name).filter { it.isNotBlank() }.joinToString(" • "),
                                thumbnail = song.thumbnails?.lastOrNull()?.url,
                                isCurrent = song.videoId == playingVideoId,
                                onSwipeQueue = { sharedViewModel.addSongToQueue(song) },
                                onClick = {
                                    // YouTube Music plays the artist's top songs as a list, from
                                    // the tapped one, not a one-song radio.
                                    viewModel.setQueueData(
                                        QueueData.Data(
                                            listTracks = ArrayList(data.popularSongs),
                                            firstPlayedTrack = song,
                                            playlistId = data.listSongParam,
                                            playlistName = data.title.orEmpty(),
                                            playlistType = PlaylistType.PLAYLIST,
                                            continuation = null,
                                        ),
                                    )
                                    viewModel.loadMediaItem(song, type = Config.PLAYLIST_CLICK, index = index)
                                },
                                onMore = { menuTrack = song },
                            )
                        }
                    }
                    data.albums?.results?.takeIf { it.isNotEmpty() }?.let { albums ->
                        item(key = "albums") {
                            YtmShelfHeader(
                                title = stringResource(Res.string.albums),
                                actionLabel = stringResource(Res.string.more),
                                onAction = data.channelId?.let { id ->
                                    { navController.navigate(MoreAlbumsDestination(id = "MPAD$id", type = MoreAlbumsDestination.ALBUM_TYPE)) }
                                },
                            )
                            CardRow(albums.map { CardItem(it.title, listOf(albumLabel, it.year).filter { y -> y.isNotBlank() }.joinToString(" • "), it.thumbnails.lastOrNull()?.url) { navController.navigate(AlbumDestination(it.browseId)) } })
                        }
                    }
                    data.singles?.results?.takeIf { it.isNotEmpty() }?.let { singles ->
                        item(key = "singles") {
                            YtmShelfHeader(
                                title = stringResource(Res.string.ytm_singles_eps),
                                actionLabel = stringResource(Res.string.more),
                                onAction = data.channelId?.let { id ->
                                    { navController.navigate(MoreAlbumsDestination(id = "MPAD$id", type = MoreAlbumsDestination.SINGLE_TYPE)) }
                                },
                            )
                            CardRow(singles.map { CardItem(it.title, listOf(singleLabel, it.year).filter { y -> y.isNotBlank() }.joinToString(" • "), it.thumbnails.lastOrNull()?.url) { navController.navigate(AlbumDestination(it.browseId)) } })
                        }
                    }
                    if (data.featuredOn.isNotEmpty()) {
                        item(key = "featured") {
                            YtmShelfHeader(title = stringResource(Res.string.featured_inArtist))
                            CardRow(data.featuredOn.map { CardItem(it.title, it.author, it.thumbnails.lastOrNull()?.url) { navController.navigate(PlaylistDestination(it.id)) } })
                        }
                    }
                    data.related?.results?.takeIf { it.isNotEmpty() }?.let { related ->
                        item(key = "related") {
                            YtmShelfHeader(title = stringResource(Res.string.ytm_fans_also_like))
                            CardRow(
                                related.map { CardItem(it.title, it.subscribers, it.thumbnails.lastOrNull()?.url, round = true) { navController.navigate(ArtistDestination(it.browseId)) } },
                            )
                        }
                    }
                    if (!data.description.isNullOrBlank()) {
                        item(key = "about") {
                            YtmShelfHeader(title = stringResource(Res.string.ytm_about_artist))
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                data.description.orEmpty(),
                                style = typo().bodyMedium,
                                color = Ytm.TextSecondary,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp).clickable { expanded = !expanded },
                            )
                        }
                    }
                }
                // The back button floats over the picture, as in YouTube Music.
                IconButton(
                    onClick = { navController.navigateUp() },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp),
                ) {
                    Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

private class CardItem(
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val round: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun CardRow(cards: List<CardItem>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards) { card ->
            Column(
                Modifier.width(152.dp).clickable(onClick = card.onClick),
                horizontalAlignment = if (card.round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Thumb(card.thumbnail, 152.dp, if (card.round) CircleShape else RoundedCornerShape(4.dp))
                Spacer(Modifier.height(8.dp))
                Text(card.title, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.subtitle.isNotBlank()) {
                    Text(card.subtitle, style = typo().bodyMedium.copy(fontSize = 13.sp), color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ArtistPill(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (filled) Color.Black else Color.White
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium), color = tint)
    }
}

/** YouTube Music's Subscribe button: red while not subscribed, grey "Subscribed" after. */
@Composable
private fun SubscribeButton(
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 96.dp)
            .clip(CircleShape)
            .background(if (subscribed) Color.White.copy(alpha = 0.12f) else YtmRed)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (subscribed) Res.string.ytm_subscribed else Res.string.ytm_subscribe),
            style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
        )
    }
}
