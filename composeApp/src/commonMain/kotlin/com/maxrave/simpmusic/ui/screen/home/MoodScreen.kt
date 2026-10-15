package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmShelfHeader
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.MoodViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** A mood or genre page from Explore: its title, then YouTube's shelves for it as cards. */
@Composable
fun MoodScreen(
    navController: NavController,
    viewModel: MoodViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    params: String?,
) {
    val moodData by viewModel.moodsMomentObject.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    LaunchedEffect(params) { if (params != null) viewModel.getMood(params) }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = moodData?.header.orEmpty(), onBack = { navController.navigateUp() })
        if (loading || moodData == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(moodData?.items.orEmpty(), key = { it.header }) { shelf ->
                YtmShelfHeader(title = shelf.header)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.contents) { content ->
                        Column(
                            Modifier.width(152.dp).clickable {
                                val videoId = content.videoId
                                if (videoId != null) {
                                    val track =
                                        Track(
                                            album = null,
                                            artists = null,
                                            duration = "",
                                            durationSeconds = 0,
                                            isAvailable = true,
                                            isExplicit = false,
                                            likeStatus = "INDIFFERENT",
                                            thumbnails = content.thumbnails,
                                            title = content.title,
                                            videoId = videoId,
                                            videoType = "",
                                            category = null,
                                            feedbackTokens = null,
                                            resultType = null,
                                            year = "",
                                        )
                                    sharedViewModel.setQueueData(
                                        QueueData.Data(
                                            listTracks = arrayListOf(track),
                                            firstPlayedTrack = track,
                                            playlistId = "RDAMVM$videoId",
                                            playlistName = "\"${content.title}\" Radio",
                                            playlistType = PlaylistType.RADIO,
                                            continuation = null,
                                        ),
                                    )
                                    sharedViewModel.loadMediaItem(track, Config.SONG_CLICK)
                                } else {
                                    navController.navigate(PlaylistDestination(playlistId = content.playlistBrowseId))
                                }
                            },
                        ) {
                            Thumb(content.thumbnails?.lastOrNull()?.url, 152.dp, RoundedCornerShape(4.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                content.title,
                                style = typo().bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(content.subtitle, style = typo().bodyMedium.copy(fontSize = 13.sp), color = Ytm.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
