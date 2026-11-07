package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.paging.compose.collectAsLazyPagingItems
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.RecentlySongsViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_history

/**
 * YouTube Music's History: the account's YouTube history grouped Today, Yesterday, This week…
 * When signed out there is no account history, so the songs played on this device stand in.
 * A tap plays the song's radio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlySongsScreen(
    innerPadding: PaddingValues,
    navController: NavController,
    viewModel: RecentlySongsViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val history by viewModel.youTubeHistory.collectAsStateWithLifecycle()
    val local = viewModel.recentlySongs.collectAsLazyPagingItems()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    menuTrack?.let { NowPlayingBottomSheet(onDismiss = { menuTrack = null }, navController = navController, song = it.toSongEntity()) }

    fun play(track: Track) {
        sharedViewModel.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${track.videoId}",
                playlistName = "\"${track.title}\" Radio",
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
    }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = stringResource(Res.string.ytm_history), onBack = { navController.navigateUp() })
        PullToRefreshBox(
            isRefreshing = refreshing && history == null,
            onRefresh = {
                refreshing = true
                viewModel.refresh()
                local.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                signedIn == true && history == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                signedIn == true ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        history.orEmpty().forEach { (group, tracks) ->
                            item(key = "g-$group") { GroupTitle(group) }
                            items(tracks, key = { "$group-${it.videoId}" }) { track ->
                                YtmSongRow(
                                    title = track.title,
                                    subtitle = listOfNotNull(track.artists?.toListName()?.connectArtists(), track.album?.name).filter { it.isNotBlank() }.joinToString(" • "),
                                    thumbnail = track.thumbnails?.lastOrNull()?.url,
                                    onClick = { play(track) },
                                    onMore = { menuTrack = track },
                                    onSwipeQueue = { sharedViewModel.addSongToQueue(track) },
                                )
                            }
                        }
                    }
                else ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "device") { GroupTitle("On this device") }
                        items(count = local.itemCount, key = { index -> "l-$index" }) { index ->
                            val song = local[index] as? SongEntity ?: return@items
                            YtmSongRow(
                                title = song.title,
                                subtitle = listOfNotNull(song.artistName?.connectArtists(), song.albumName).filter { it.isNotBlank() }.joinToString(" • "),
                                thumbnail = song.thumbnails,
                                onClick = { play(song.toTrack()) },
                                onMore = { menuTrack = song.toTrack() },
                                onSwipeQueue = { sharedViewModel.addSongToQueue(song.toTrack()) },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = typo().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}
