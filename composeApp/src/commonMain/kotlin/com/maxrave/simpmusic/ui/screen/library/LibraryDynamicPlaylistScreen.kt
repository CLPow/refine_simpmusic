package com.maxrave.simpmusic.ui.screen.library

import com.maxrave.simpmusic.ui.component.selection.SongSelectionOverlay
import com.maxrave.simpmusic.ui.component.selection.rememberSongSelectionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmOutlinedPill
import com.maxrave.simpmusic.ui.component.ytm.YtmSongRow
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.component.ytm.playAll
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.LibraryDynamicPlaylistViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.maxrave.simpmusic.ui.component.YtmRoundAction
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Shuffle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.ytm_downloads
import simpmusic.composeapp.generated.resources.ytm_library_empty_downloads
import simpmusic.composeapp.generated.resources.ytm_library_empty_songs
import simpmusic.composeapp.generated.resources.ytm_liked_music
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_shuffle

sealed class LibraryDynamicPlaylistType {
    data object Favorite : LibraryDynamicPlaylistType()

    data object Downloaded : LibraryDynamicPlaylistType()

    fun toStringParams(): String =
        when (this) {
            Favorite -> "favorite"
            Downloaded -> "downloaded"
        }

    companion object {
        fun toType(input: String): LibraryDynamicPlaylistType = if (input == "downloaded") Downloaded else Favorite
    }
}

@Composable
fun LibraryDynamicPlaylistScreen(
    navController: NavController,
    type: LibraryDynamicPlaylistType,
    viewModel: LibraryDynamicPlaylistViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val songs = if (type == LibraryDynamicPlaylistType.Downloaded) downloaded else liked
    val title = stringResource(if (type == LibraryDynamicPlaylistType.Downloaded) Res.string.ytm_downloads else Res.string.ytm_liked_music)
    var menuSong by remember { mutableStateOf<SongEntity?>(null) }
    menuSong?.let { NowPlayingBottomSheet(onDismiss = { menuSong = null }, navController = navController, song = it) }

    val selection = rememberSongSelectionState()
    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        if (selection.isActive) {
            SongSelectionOverlay(selection, songs.orEmpty().map { it.toTrack() })
        } else {
            YtmTopBar(navController = navController, title = title, onBack = { navController.navigateUp() })
        }
        val list = songs
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF6B4CDA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (type == LibraryDynamicPlaylistType.Downloaded) SimpIcons.DownloadForOffline else SimpIcons.ThumbUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(88.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(title, style = typo().titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = Color.White)
                    Text(pluralStringResource(Res.plurals.n_song, list.size, list.size), style = typo().bodyMedium, color = Ytm.TextSecondary)
                    if (list.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        val playLabel = stringResource(Res.string.ytm_play)
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            YtmRoundAction(
                                SimpIcons.Shuffle,
                                onClick = { playAll(list.map { it.toTrack() }, title, sharedViewModel, shuffle = true) },
                                label = stringResource(Res.string.ytm_shuffle),
                            )
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable(role = Role.Button, onClickLabel = playLabel) { playAll(list.map { it.toTrack() }, title, sharedViewModel) }
                                    .semantics { contentDescription = playLabel },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(SimpIcons.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(36.dp))
                            }
                        }
                    } else {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(if (type == LibraryDynamicPlaylistType.Downloaded) Res.string.ytm_library_empty_downloads else Res.string.ytm_library_empty_songs),
                            style = typo().bodyLarge,
                            color = Ytm.TextSecondary,
                        )
                    }
                }
            }
            items(list, key = { it.videoId }) { song ->
                YtmSongRow(
                    title = song.title,
                    subtitle = song.artistName?.joinToString(", ").orEmpty(),
                    thumbnail = song.thumbnails,
                    onClick = {
                        if (selection.isActive) {
                            selection.toggle(song.videoId)
                        } else {
                            playAll(list.map { it.toTrack() }, title, sharedViewModel, startIndex = list.indexOf(song).coerceAtLeast(0))
                        }
                    },
                    onMore = { menuSong = song },
                    onLongClick = { selection.start(song.videoId) },
                    onSwipeQueue = { sharedViewModel.addSongToQueue(song.toTrack()) },
                    selected = if (selection.isActive) selection.isSelected(song.videoId) else null,
                )
            }
        }
    }
}
