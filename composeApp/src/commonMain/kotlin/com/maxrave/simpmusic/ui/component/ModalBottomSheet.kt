package com.maxrave.simpmusic.ui.component

import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.ui.icon.Favorite
import com.maxrave.simpmusic.ui.icon.ThumbUp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.copyToClipboard
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.expect.ui.photoPickerResult
import com.maxrave.simpmusic.extension.displayNameRes
import com.maxrave.simpmusic.extension.greyScale
import com.maxrave.simpmusic.ui.icon.AccessAlarm
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.AddCircleOutline
import com.maxrave.simpmusic.ui.icon.AddPhotoAlternate
import com.maxrave.simpmusic.ui.icon.Album
import com.maxrave.simpmusic.ui.icon.CheckCircle
import com.maxrave.simpmusic.ui.icon.ContentCopy
import com.maxrave.simpmusic.ui.icon.Delete
import com.maxrave.simpmusic.ui.icon.Done
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.DownloadForOfflineOutlined
import com.maxrave.simpmusic.ui.icon.Downloading
import com.maxrave.simpmusic.ui.icon.Edit
import com.maxrave.simpmusic.ui.icon.FavoriteBorder
import com.maxrave.simpmusic.ui.icon.KeyboardArrowDown
import com.maxrave.simpmusic.ui.icon.KeyboardDoubleArrowDown
import com.maxrave.simpmusic.ui.icon.KeyboardDoubleArrowUp
import com.maxrave.simpmusic.ui.icon.Lyrics
import com.maxrave.simpmusic.ui.icon.PeopleAlt
import com.maxrave.simpmusic.ui.icon.PlayCircle
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.QueueMusic
import com.maxrave.simpmusic.ui.icon.Remove
import com.maxrave.simpmusic.ui.icon.Sensors
import com.maxrave.simpmusic.ui.icon.Share
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Speed
import com.maxrave.simpmusic.ui.icon.Sync
import com.maxrave.simpmusic.ui.icon.SyncDisabled
import com.maxrave.simpmusic.ui.icon.Tune
import com.maxrave.simpmusic.ui.icon.Update
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.NowPlayingBottomSheetUIEvent
import com.maxrave.simpmusic.viewModel.NowPlayingBottomSheetViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.add_to_a_playlist
import simpmusic.composeapp.generated.resources.add_to_queue
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.baseline_favorite_24
import simpmusic.composeapp.generated.resources.better_lyrics
import simpmusic.composeapp.generated.resources.bitrate
import simpmusic.composeapp.generated.resources.bpm
import simpmusic.composeapp.generated.resources.can_not_be_empty
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.codec
import simpmusic.composeapp.generated.resources.copied_to_clipboard
import simpmusic.composeapp.generated.resources.delete
import simpmusic.composeapp.generated.resources.delete_playlist
import simpmusic.composeapp.generated.resources.delete_song_from_playlist
import simpmusic.composeapp.generated.resources.description
import simpmusic.composeapp.generated.resources.download
import simpmusic.composeapp.generated.resources.download_speed
import simpmusic.composeapp.generated.resources.download_this_song_video_file_to_your_device
import simpmusic.composeapp.generated.resources.downloaded
import simpmusic.composeapp.generated.resources.downloading
import simpmusic.composeapp.generated.resources.downloading_audio
import simpmusic.composeapp.generated.resources.downloading_video
import simpmusic.composeapp.generated.resources.edit_thumbnail
import simpmusic.composeapp.generated.resources.edit_title
import simpmusic.composeapp.generated.resources.endless_queue
import simpmusic.composeapp.generated.resources.error_occurred
import simpmusic.composeapp.generated.resources.extract_source
import simpmusic.composeapp.generated.resources.itag
import simpmusic.composeapp.generated.resources.key
import simpmusic.composeapp.generated.resources.like
import simpmusic.composeapp.generated.resources.like_and_dislike
import simpmusic.composeapp.generated.resources.liked
import simpmusic.composeapp.generated.resources.list_all_cookies_of_this_page
import simpmusic.composeapp.generated.resources.lrclib
import simpmusic.composeapp.generated.resources.main_lyrics_provider
import simpmusic.composeapp.generated.resources.merging_audio_and_video
import simpmusic.composeapp.generated.resources.mime_type
import simpmusic.composeapp.generated.resources.move_down
import simpmusic.composeapp.generated.resources.move_up
import simpmusic.composeapp.generated.resources.no_album
import simpmusic.composeapp.generated.resources.no_description
import simpmusic.composeapp.generated.resources.no_playlist_found
import simpmusic.composeapp.generated.resources.now_playing
import simpmusic.composeapp.generated.resources.now_playing_upper
import simpmusic.composeapp.generated.resources.ok
import simpmusic.composeapp.generated.resources.pitch
import simpmusic.composeapp.generated.resources.play_next
import simpmusic.composeapp.generated.resources.playback_speed
import simpmusic.composeapp.generated.resources.playback_speed_pitch
import simpmusic.composeapp.generated.resources.playback_speed_pitch_disabled
import simpmusic.composeapp.generated.resources.playlist_name_cannot_be_empty
import simpmusic.composeapp.generated.resources.plays
import simpmusic.composeapp.generated.resources.processing
import simpmusic.composeapp.generated.resources.queue
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.save
import simpmusic.composeapp.generated.resources.save_to_local_playlist
import simpmusic.composeapp.generated.resources.saved_to_local_playlist
import simpmusic.composeapp.generated.resources.scale
import simpmusic.composeapp.generated.resources.set
import simpmusic.composeapp.generated.resources.share
import simpmusic.composeapp.generated.resources.share_url
import simpmusic.composeapp.generated.resources.simpmusic_lyrics
import simpmusic.composeapp.generated.resources.sleep_minutes
import simpmusic.composeapp.generated.resources.sleep_timer
import simpmusic.composeapp.generated.resources.sleep_timer_end_of_song
import simpmusic.composeapp.generated.resources.sleep_timer_off
import simpmusic.composeapp.generated.resources.sleep_timer_set_error
import simpmusic.composeapp.generated.resources.sleep_timer_warning
import simpmusic.composeapp.generated.resources.sort_by
import simpmusic.composeapp.generated.resources.start_radio
import simpmusic.composeapp.generated.resources.sync
import simpmusic.composeapp.generated.resources.sync_first
import simpmusic.composeapp.generated.resources.synced
import simpmusic.composeapp.generated.resources.title
import simpmusic.composeapp.generated.resources.to_download_folder
import simpmusic.composeapp.generated.resources.unknown
import simpmusic.composeapp.generated.resources.update_playlist
import simpmusic.composeapp.generated.resources.warning
import simpmusic.composeapp.generated.resources.yes
import simpmusic.composeapp.generated.resources.your_discord_token
import simpmusic.composeapp.generated.resources.your_playlists
import simpmusic.composeapp.generated.resources.your_sp_dc_param_of_spotify_cookie
import simpmusic.composeapp.generated.resources.your_youtube_cookie
import simpmusic.composeapp.generated.resources.your_youtube_playlists
import simpmusic.composeapp.generated.resources.youtube_transcript
import simpmusic.composeapp.generated.resources.youtube_url
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.ThumbDown
import simpmusic.composeapp.generated.resources.ytm_add_to_liked
import simpmusic.composeapp.generated.resources.ytm_add_to_queue
import simpmusic.composeapp.generated.resources.ytm_dislike
import simpmusic.composeapp.generated.resources.ytm_download
import simpmusic.composeapp.generated.resources.ytm_downloading
import simpmusic.composeapp.generated.resources.ytm_go_to_album
import simpmusic.composeapp.generated.resources.ytm_go_to_artist
import simpmusic.composeapp.generated.resources.ytm_like
import simpmusic.composeapp.generated.resources.ytm_liked
import simpmusic.composeapp.generated.resources.ytm_play_next
import simpmusic.composeapp.generated.resources.ytm_remove
import simpmusic.composeapp.generated.resources.ytm_remove_download
import simpmusic.composeapp.generated.resources.ytm_remove_download_confirm
import simpmusic.composeapp.generated.resources.ytm_remove_download_confirm_body
import simpmusic.composeapp.generated.resources.ytm_remove_from_liked
import simpmusic.composeapp.generated.resources.ytm_remove_from_library
import simpmusic.composeapp.generated.resources.ytm_remove_from_queue
import simpmusic.composeapp.generated.resources.remove_from_playlist
import simpmusic.composeapp.generated.resources.ytm_save_to_playlist
import simpmusic.composeapp.generated.resources.ytm_share
import simpmusic.composeapp.generated.resources.ytm_sleep_timer
import simpmusic.composeapp.generated.resources.ytm_start_radio
import simpmusic.composeapp.generated.resources.ytm_saved
import androidx.compose.ui.semantics.Role
import simpmusic.composeapp.generated.resources.ytm_one_hour
import simpmusic.composeapp.generated.resources.ytm_minutes
import org.jetbrains.compose.resources.pluralStringResource

// ─────────────────────────────────────────────────────────────────────────────
// Sentinel value used by SleepTimerBottomSheet to signal "end of current song"
// Handle this in NowPlayingBottomSheetViewModel / SharedViewModel:
//   if (minutes == END_OF_SONG_SENTINEL) → stop after current track finishes
// ─────────────────────────────────────────────────────────────────────────────
const val END_OF_SONG_SENTINEL = Int.MAX_VALUE

private enum class QueueItemAction {
    UP,
    DOWN,
    DELETE,
}

/**
 * The menu of a song in Up next, in YouTube Music's style. It acts on the song, not on its row
 * number: the queue can change while the menu is open (a radio paging in, a removal), and an
 * index captured at opening could then move or remove the wrong song, or point past the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueItemBottomSheet(
    onDismiss: () -> Unit,
    videoId: String,
    indexHint: Int,
    musicServiceHandler: MediaPlayerHandler = koinInject<MediaPlayerHandler>(),
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queue by musicServiceHandler.queueData.collectAsStateWithLifecycle()
    val tracks = queue?.data?.listTracks.orEmpty()

    /** Where the song is now: the occurrence nearest to where it was when the menu opened. */
    fun currentIndex(): Int {
        val list = musicServiceHandler.queueData.value?.data?.listTracks.orEmpty()
        return list.indices.filter { list[it].videoId == videoId }.minByOrNull { kotlin.math.abs(it - indexHint) } ?: -1
    }
    val index = remember(tracks) { currentIndex() }
    val track = tracks.getOrNull(index)
    fun close() {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (track != null) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(track.thumbnails?.lastOrNull()?.url, 48.dp, RoundedCornerShape(4.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = typo().bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artists?.toListName()?.connectArtists().orEmpty(), style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Ytm.Divider)
            }
            if (index > 0) {
                YtmMenuRow(SimpIcons.KeyboardDoubleArrowUp, stringResource(Res.string.move_up)) {
                    val i = currentIndex()
                    if (i > 0) coroutineScope.launch { musicServiceHandler.moveItemUp(i) }
                    close()
                }
            }
            if (index in 0 until tracks.size - 1) {
                YtmMenuRow(SimpIcons.KeyboardDoubleArrowDown, stringResource(Res.string.move_down)) {
                    val i = currentIndex()
                    val size = musicServiceHandler.queueData.value?.data?.listTracks?.size ?: 0
                    if (i in 0 until size - 1) coroutineScope.launch { musicServiceHandler.moveItemDown(i) }
                    close()
                }
            }
            if (index >= 0) {
                YtmMenuRow(SimpIcons.Delete, stringResource(Res.string.ytm_remove_from_queue)) {
                    val i = currentIndex()
                    if (i >= 0) musicServiceHandler.removeMediaItem(i)
                    close()
                }
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingBottomSheet(
    onDismiss: () -> Unit,
    navController: NavController,
    song: SongEntity?,
    // One view model per song: a shared one opened the menu for song B showing song A, and
    // taps in that moment acted on A.
    viewModel: NowPlayingBottomSheetViewModel = koinViewModel(key = "song-menu-" + (song?.videoId ?: "now-playing")),
    setSleepTimerEnable: Boolean = false,
    changeMainLyricsProviderEnable: Boolean = false,
    onNavigateToOtherScreen: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onLibraryDelete: (() -> Unit)? = null,
    dataStoreManager: DataStoreManager = koinInject<DataStoreManager>(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val modelBottomSheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )
    val hideModalBottomSheet: () -> Unit =
        {
            coroutineScope.launch {
                modelBottomSheetState.hide()
                onDismiss()
            }
        }

    var addToAPlaylist by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf(false) }
    var sleepTimer by remember { mutableStateOf(false) }
    var sleepTimerWarning by remember { mutableStateOf(false) }
    var isBottomSheetVisible by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveDownload by remember { mutableStateOf(false) }
    val radioLabel = stringResource(Res.string.radio)

    LaunchedEffect(uiState) {
        if (uiState.songUIState.videoId.isNotEmpty() && !isBottomSheetVisible) {
            isBottomSheetVisible = true
        }
    }

    LaunchedEffect(key1 = song) {
        viewModel.setSongEntity(song)
    }

    if (addToAPlaylist) {
        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = true,
            listLocalPlaylist = uiState.listLocalPlaylist,
            listYouTubePlaylist = uiState.listYouTubePlaylist,
            onDismiss = { addToAPlaylist = false },
            onClick = {
                viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.AddToPlaylist(it.id))
            },
            onYTPlaylistClick = {
                viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.AddToYouTubePlaylist(it.browseId))
            },
            videoId = uiState.songUIState.videoId,
        )
    }
    if (artist) {
        ArtistModalBottomSheet(
            isBottomSheetVisible = true,
            artists = uiState.songUIState.listArtists,
            navController = navController,
            // Going to the artist closes this menu too; on desktop it otherwise stayed open over
            // the artist's page.
            onNavigateToOtherScreen = {
                artist = false
                onNavigateToOtherScreen()
                onDismiss()
            },
            onDismiss = { artist = false },
        )
    }
    if (confirmRemoveDownload) {
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { confirmRemoveDownload = false },
            title = { Text(stringResource(Res.string.ytm_remove_download_confirm), color = Color.White) },
            text = { Text(stringResource(Res.string.ytm_remove_download_confirm_body), color = Ytm.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownload = false
                    viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.Download)
                }) { Text(stringResource(Res.string.ytm_remove), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownload = false }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
        )
    }

    if (sleepTimer) {
        SleepTimerBottomSheet(onDismiss = { sleepTimer = false }) { minutes: Int ->
            if (setSleepTimerEnable) {
                viewModel.onUIEvent(
                    NowPlayingBottomSheetUIEvent.SetSleepTimer(
                        cancel = false,
                        minutes = minutes,
                    ),
                )
            }
        }
    }

    if (sleepTimerWarning) {
        AlertDialog(
            containerColor = rememberSurfaceDarkColors().container,
            onDismissRequest = { sleepTimerWarning = false },
            confirmButton = {
                TextButton(onClick = {
                    sleepTimerWarning = false
                    viewModel.onUIEvent(
                        NowPlayingBottomSheetUIEvent.SetSleepTimer(
                            cancel = true,
                        ),
                    )
                }) {
                    Text(text = stringResource(Res.string.yes), style = typo().labelSmall)
                }
            },
            dismissButton = {
                TextButton(onClick = { sleepTimerWarning = false }) {
                    Text(text = stringResource(Res.string.cancel), style = typo().labelSmall)
                }
            },
            title = {
                Text(text = stringResource(Res.string.warning), style = typo().labelSmall)
            },
            text = {
                Text(text = stringResource(Res.string.sleep_timer_warning), style = typo().bodyMedium)
            },
        )
    }

    if (isBottomSheetVisible) {
        // YouTube Music's song menu: the song at the top, three big actions (Play next, Save to
        // playlist, Share), then the list.
        val song = uiState.songUIState
        val liked = song.liked
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = modelBottomSheetState,
            containerColor = Ytm.Surface,
            contentColor = Color.White,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = .5f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(LocalPlatformContext.current)
                                .data(song.thumbnails)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .diskCacheKey(song.thumbnails)
                                .crossfade(200)
                                .build(),
                        placeholder = rememberHolderPainter(),
                        error = rememberHolderPainter(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = typo().bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.listArtists.toListName().connectArtists(),
                            style = typo().bodyMedium,
                            color = Ytm.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // YouTube Music's thumbs beside the song: the account's rating when signed in.
                    IconButton(onClick = {
                        viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.Dislike)
                        hideModalBottomSheet()
                    }) {
                        Icon(SimpIcons.ThumbDown, contentDescription = stringResource(Res.string.ytm_dislike), tint = Ytm.TextSecondary)
                    }
                    IconButton(
                        onClick = { viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.ToggleLike) },
                        modifier = Modifier.semantics { selected = liked },
                    ) {
                        Icon(
                            SimpIcons.ThumbUp,
                            contentDescription = stringResource(if (liked) Res.string.ytm_liked else Res.string.ytm_like),
                            tint = if (liked) Color.White else Ytm.TextSecondary,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    YtmMenuTile(SimpIcons.PlayCircle, stringResource(Res.string.ytm_play_next), Modifier.weight(1f)) {
                        viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.PlayNext)
                        hideModalBottomSheet()
                    }
                    YtmMenuTile(SimpIcons.PlaylistAdd, stringResource(Res.string.ytm_save_to_playlist), Modifier.weight(1f)) {
                        viewModel.resetPlaylists()
                        addToAPlaylist = true
                    }
                    YtmMenuTile(SimpIcons.Share, stringResource(Res.string.ytm_share), Modifier.weight(1f)) {
                        viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.Share)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (onDelete != null) {
                    YtmMenuRow(SimpIcons.Delete, stringResource(Res.string.remove_from_playlist)) {
                        hideModalBottomSheet()
                        onDelete.invoke()
                    }
                }
                if (onLibraryDelete != null) {
                    YtmMenuRow(SimpIcons.Delete, stringResource(Res.string.ytm_remove_from_library)) {
                        hideModalBottomSheet()
                        onLibraryDelete.invoke()
                    }
                }
                YtmMenuRow(SimpIcons.Sensors, stringResource(Res.string.ytm_start_radio)) {
                    viewModel.onUIEvent(
                        NowPlayingBottomSheetUIEvent.StartRadio(
                            videoId = song.videoId,
                            name = "\"${song.title}\" $radioLabel",
                        ),
                    )
                    hideModalBottomSheet()
                }
                YtmMenuRow(SimpIcons.QueueMusic, stringResource(Res.string.ytm_add_to_queue)) {
                    viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.AddToQueue)
                    hideModalBottomSheet()
                }
                YtmMenuRow(
                    if (liked) SimpIcons.Favorite else SimpIcons.FavoriteBorder,
                    stringResource(if (liked) Res.string.ytm_remove_from_liked else Res.string.ytm_add_to_liked),
                ) {
                    viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.ToggleLike)
                }
                YtmMenuRow(
                    when (song.downloadState) {
                        DownloadState.STATE_DOWNLOADED -> SimpIcons.DownloadForOffline
                        DownloadState.STATE_DOWNLOADING, DownloadState.STATE_PREPARING -> SimpIcons.Downloading
                        else -> SimpIcons.DownloadForOfflineOutlined
                    },
                    stringResource(
                        when (song.downloadState) {
                            DownloadState.STATE_DOWNLOADED -> Res.string.ytm_remove_download
                            DownloadState.STATE_DOWNLOADING, DownloadState.STATE_PREPARING -> Res.string.ytm_downloading
                            else -> Res.string.ytm_download
                        },
                    ),
                ) {
                    // Removing a download deletes the file, so it asks first.
                    if (song.downloadState == DownloadState.STATE_DOWNLOADED) {
                        confirmRemoveDownload = true
                    } else {
                        viewModel.onUIEvent(NowPlayingBottomSheetUIEvent.Download)
                    }
                }
                if (song.album?.id != null) {
                    YtmMenuRow(SimpIcons.Album, stringResource(Res.string.ytm_go_to_album)) {
                        song.album?.id?.let { id ->
                            hideModalBottomSheet()
                            onNavigateToOtherScreen()
                            navController.navigate(AlbumDestination(browseId = id))
                        }
                    }
                }
                if (song.listArtists.isNotEmpty()) {
                    YtmMenuRow(SimpIcons.PeopleAlt, stringResource(Res.string.ytm_go_to_artist)) {
                        artist = true
                    }
                }
                if (setSleepTimerEnable) {
                    val sleepTimerState = uiState.sleepTimer
                    // timeRemaining > 0 → countdown, -1 → end of song, 0 → off
                    val isEndOfSong = sleepTimerState.timeRemaining == -1
                    val isRunning = sleepTimerState.timeRemaining > 0 || isEndOfSong
                    YtmMenuRow(
                        SimpIcons.AccessAlarm,
                        when {
                            isEndOfSong -> stringResource(Res.string.sleep_timer_end_of_song)
                            isRunning -> stringResource(Res.string.sleep_timer, sleepTimerState.timeRemaining.toString())
                            else -> stringResource(Res.string.ytm_sleep_timer)
                        },
                    ) {
                        if (isRunning) sleepTimerWarning = true else sleepTimer = true
                    }
                }
                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** One of the three big actions at the top of YouTube Music's song menu. */
@Composable
private fun YtmMenuTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = typo().bodySmall.copy(fontSize = 12.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of YouTube Music's song menu: icon, then label. */
@Composable
private fun YtmMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Text(label, style = typo().bodyLarge.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    text: StringResource?,
    textString: String? = null,
    textColor: Color? = null,
    iconColor: Color = Color.Unspecified,
    enable: Boolean = true,
    onClick: () -> Unit,
) {
    val c = rememberSurfaceDarkColors()
    val resolvedIconColor = if (iconColor == Color.Unspecified) c.content else iconColor
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.CenterVertically)
                .then(
                    if (enable) Modifier.clickable { onClick.invoke() } else Modifier.greyScale(),
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Image(
                imageVector = icon,
                contentDescription = if (text != null) stringResource(text) else textString ?: "",
                modifier =
                    Modifier
                        .wrapContentSize(Alignment.Center)
                        .padding(12.dp),
                colorFilter =
                    if (enable) {
                        ColorFilter.tint(resolvedIconColor)
                    } else {
                        ColorFilter.tint(c.disabled)
                    },
            )
            Text(
                text = if (text != null) stringResource(text) else textString ?: "",
                style = typo().labelSmall,
                color = if (enable) textColor ?: c.content else c.disabled,
                modifier =
                    Modifier
                        .padding(start = 10.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
fun HeartCheckBox(
    size: Int = 24,
    checked: Boolean,
    tint: Color = rememberSurfaceDarkColors().content,
    onStateChange: (() -> Unit)? = null,
) {
    val burstState = rememberHeartBurstState()
    Box(
        modifier =
            Modifier
                .size(size.dp)
                // Before .clip: the burst draws outside the button bounds and the circle clip
                // would trim it to the heart's own circle.
                .heartBurst(burstState)
                .clip(CircleShape)
                .clickable {
                    // Judged at TAP time: tapping an unchecked heart is a like. Firing from the
                    // tap — not from watching `checked` — is what keeps a track change onto an
                    // already-liked song from celebrating a like nobody gave.
                    if (!checked) burstState.fire()
                    onStateChange?.invoke()
                },
    ) {
        Crossfade(targetState = checked, modifier = Modifier.fillMaxSize()) {
            if (it) {
                Image(
                    painter = painterResource(Res.drawable.baseline_favorite_24),
                    contentDescription = "Favorite checked",
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Image(
                    imageVector = SimpIcons.FavoriteBorder,
                    contentDescription = "Favorite unchecked",
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    colorFilter = ColorFilter.tint(tint),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REDESIGNED SleepTimerBottomSheet
// Quick presets (5, 10, 15, 30, 45 min, 1 hour) + End of Song + Custom input
// Passes END_OF_SONG_SENTINEL (Int.MAX_VALUE) for "End of Song" option.
// ─────────────────────────────────────────────────────────────────────────────
/**
 * YouTube Music's sleep timer: a plain list of durations and "End of song"; a tap starts it.
 * [onSetTimer] gets minutes, or [END_OF_SONG_SENTINEL].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    onDismiss: () -> Unit,
    onSetTimer: (minutes: Int) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Text(
            stringResource(Res.string.ytm_sleep_timer),
            style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        val choose: (Int) -> Unit = { minutes ->
            onSetTimer(minutes)
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
        listOf(5, 10, 15, 30, 45).forEach { minutes ->
            SleepTimerRow(pluralStringResource(Res.plurals.ytm_minutes, minutes, minutes)) { choose(minutes) }
        }
        SleepTimerRow(stringResource(Res.string.ytm_one_hour)) { choose(60) }
        SleepTimerRow(stringResource(Res.string.sleep_timer_end_of_song)) { choose(END_OF_SONG_SENTINEL) }
        Spacer(Modifier.height(16.dp).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun SleepTimerRow(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = typo().bodyLarge.copy(fontSize = 15.sp),
        color = Color.White,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * YouTube Music's "Save to playlist": the account's and this device's playlists in one list, each
 * with its cover. A playlist that already has the song is marked and cannot be picked again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistModalBottomSheet(
    isBottomSheetVisible: Boolean,
    listLocalPlaylist: List<LocalPlaylistEntity>,
    listYouTubePlaylist: List<PlaylistsResult>,
    videoId: String? = null,
    onClick: (LocalPlaylistEntity) -> Unit,
    onYTPlaylistClick: (PlaylistsResult) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isBottomSheetVisible) return
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val hide: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    val youTube = listYouTubePlaylist.filter { it.browseId != "VLLM" && it.browseId != "LM" }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Text(
            stringResource(Res.string.ytm_save_to_playlist),
            style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        if (listLocalPlaylist.isEmpty() && youTube.isEmpty()) {
            Text(
                stringResource(Res.string.no_playlist_found),
                style = typo().bodyLarge,
                color = Ytm.TextSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(listLocalPlaylist, key = { "l-" + it.id }) { playlist ->
                val added = videoId != null && playlist.tracks?.contains(videoId) == true
                SavePlaylistRow(playlist.thumbnail, playlist.title, added) {
                    onClick(playlist)
                    hide()
                }
            }
            if (youTube.isNotEmpty()) {
                item(key = "yt-title") {
                    Text(
                        stringResource(Res.string.your_youtube_playlists),
                        style = typo().bodyMedium,
                        color = Ytm.TextSecondary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(youTube, key = { "y-" + it.browseId }) { playlist ->
                SavePlaylistRow(playlist.thumbnails.lastOrNull()?.url, playlist.title, added = false) {
                    onYTPlaylistClick(playlist)
                    hide()
                }
            }
            item { Spacer(Modifier.height(16.dp).windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }
}

@Composable
private fun SavePlaylistRow(
    thumbnail: String?,
    title: String,
    added: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !added, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbnail, 48.dp, RoundedCornerShape(4.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = typo().bodyLarge.copy(fontSize = 15.sp),
            color = if (added) Ytm.TextSecondary else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (added) {
            Icon(SimpIcons.Done, contentDescription = stringResource(Res.string.ytm_saved), tint = Color.White)
        }
    }
}

/** YouTube Music's artist picker, for "Go to artist" on a song with several artists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistModalBottomSheet(
    isBottomSheetVisible: Boolean,
    artists: List<Artist>,
    navController: NavController,
    onNavigateToOtherScreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isBottomSheetVisible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Text(
            stringResource(Res.string.ytm_go_to_artist),
            style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        artists.forEach { artist ->
            val id = artist.id
            YtmMenuRow(SimpIcons.PeopleAlt, artist.name) {
                if (!id.isNullOrBlank()) {
                    onNavigateToOtherScreen()
                    onDismiss()
                    navController.navigate(ArtistDestination(id))
                }
            }
        }
        Spacer(Modifier.height(16.dp).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
@ExperimentalMaterial3Api
fun PlaylistBottomSheet(
    onDismiss: () -> Unit,
    playlistId: String,
    playlistName: String,
    isYourYouTubePlaylist: Boolean,
    onEditTitle: (newTitle: String) -> Unit = {},
    onSaveToLocal: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    localPlaylistRepository: LocalPlaylistRepository = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    var isSavedToLocal by remember { mutableStateOf(false) }
    val modelBottomSheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hideModalBottomSheet: () -> Unit =
        {
            coroutineScope.launch {
                modelBottomSheetState.hide()
                onDismiss()
            }
        }
    var showEditTitle by remember { mutableStateOf(false) }
    if (showEditTitle) {
        var newTitle by remember { mutableStateOf(playlistName) }
        val showEditTitleSheetState =
            rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val hideEditTitleBottomSheet: () -> Unit =
            {
                coroutineScope.launch {
                    showEditTitleSheetState.hide()
                    onDismiss()
                }
            }
        ModalBottomSheet(
            onDismissRequest = { showEditTitle = false },
            sheetState = showEditTitleSheetState,
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = .5f),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Card(
                        modifier = Modifier.width(60.dp).height(4.dp),
                        colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                        shape = RoundedCornerShape(50),
                    ) {}
                    Spacer(modifier = Modifier.height(5.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { s -> newTitle = s },
                        label = { Text(text = stringResource(Res.string.title)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    val playlistNameError = stringResource(Res.string.playlist_name_cannot_be_empty)
                    TextButton(
                        onClick = {
                            if (newTitle.isBlank()) {
                                showToast(playlistNameError, ToastGravity.Bottom)
                            } else {
                                onEditTitle(newTitle)
                                hideEditTitleBottomSheet()
                                hideModalBottomSheet()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = stringResource(Res.string.save))
                    }
                    EndOfModalBottomSheet()
                }
            }
        }
    }

    LaunchedEffect(true) {
        localPlaylistRepository.getAllLocalPlaylists().collect {
            isSavedToLocal = it.any { playlist -> playlist.youtubePlaylistId == playlistId }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modelBottomSheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(5.dp))
                Card(
                    modifier = Modifier.width(60.dp).height(4.dp),
                    colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                    shape = RoundedCornerShape(50),
                ) {}
                Spacer(modifier = Modifier.height(5.dp))
                if (onAddToQueue != null) {
                    ActionButton(
                        icon = SimpIcons.QueueMusic,
                        text = Res.string.add_to_queue,
                    ) {
                        onAddToQueue()
                        hideModalBottomSheet()
                    }
                }
                if (isYourYouTubePlaylist) {
                    ActionButton(icon = SimpIcons.Edit, text = Res.string.edit_title) {
                        showEditTitle = true
                    }
                    ActionButton(
                        icon =
                            if (isSavedToLocal) {
                                SimpIcons.SyncDisabled
                            } else {
                                SimpIcons.Sync
                            },
                        text =
                            if (isSavedToLocal) {
                                Res.string.saved_to_local_playlist
                            } else {
                                Res.string.save_to_local_playlist
                            },
                        enable = !isSavedToLocal,
                    ) {
                        onSaveToLocal.invoke()
                        hideModalBottomSheet()
                    }
                }
                val shareTitle = stringResource(Res.string.share)
                ActionButton(icon = SimpIcons.Share, text = Res.string.share) {
                    val url = "https://music.youtube.com/playlist?list=${playlistId.replaceFirst("VL", "")}"
                    shareUrl(shareTitle, url)
                }
                EndOfModalBottomSheet()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistBottomSheet(
    isBottomSheetVisible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    ytPlaylistId: String? = null,
    onEditTitle: (newTitle: String) -> Unit,
    onEditThumbnail: (newThumbnailUri: String) -> Unit,
    onAddToQueue: () -> Unit,
    onSync: () -> Unit,
    onUpdatePlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var showEditTitle by remember { mutableStateOf(false) }
    val modelBottomSheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hideModalBottomSheet: () -> Unit =
        {
            coroutineScope.launch {
                modelBottomSheetState.hide()
                onDismiss()
            }
        }
    val resultLauncher =
        photoPickerResult {
            it?.let { onEditThumbnail(it) }
        }
    if (showEditTitle) {
        var newTitle by remember { mutableStateOf(title) }
        val showEditTitleSheetState =
            rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val hideEditTitleBottomSheet: () -> Unit =
            {
                coroutineScope.launch {
                    showEditTitleSheetState.hide()
                    onDismiss()
                }
            }
        ModalBottomSheet(
            onDismissRequest = { showEditTitle = false },
            sheetState = showEditTitleSheetState,
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = .5f),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Card(
                        modifier = Modifier.width(60.dp).height(4.dp),
                        colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                        shape = RoundedCornerShape(50),
                    ) {}
                    Spacer(modifier = Modifier.height(5.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { s -> newTitle = s },
                        label = { Text(text = stringResource(Res.string.title)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    val playlistNameError = stringResource(Res.string.playlist_name_cannot_be_empty)
                    TextButton(
                        onClick = {
                            if (newTitle.isBlank()) {
                                showToast(playlistNameError, ToastGravity.Bottom)
                            } else {
                                onEditTitle(newTitle)
                                hideEditTitleBottomSheet()
                                hideModalBottomSheet()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = stringResource(Res.string.save))
                    }
                    EndOfModalBottomSheet()
                }
            }
        }
    }
    if (isBottomSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = modelBottomSheetState,
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = .5f),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Card(
                        modifier = Modifier.width(60.dp).height(4.dp),
                        colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                        shape = RoundedCornerShape(50),
                    ) {}
                    Spacer(modifier = Modifier.height(5.dp))
                    ActionButton(icon = SimpIcons.Edit, text = Res.string.edit_title) {
                        showEditTitle = true
                    }
                    ActionButton(icon = SimpIcons.AddPhotoAlternate, text = Res.string.edit_thumbnail) {
                        resultLauncher.launch()
                    }
                    ActionButton(icon = SimpIcons.QueueMusic, text = Res.string.add_to_queue) {
                        onAddToQueue()
                    }
                    ActionButton(
                        icon =
                            if (ytPlaylistId != null) {
                                SimpIcons.SyncDisabled
                            } else {
                                SimpIcons.Sync
                            },
                        text =
                            if (ytPlaylistId != null) {
                                Res.string.synced
                            } else {
                                Res.string.sync
                            },
                    ) {
                        onSync()
                    }
                    ActionButton(
                        icon = SimpIcons.Update,
                        text = Res.string.update_playlist,
                        enable = (ytPlaylistId != null),
                    ) {
                        onUpdatePlaylist()
                    }
                    ActionButton(icon = SimpIcons.Delete, text = Res.string.delete_playlist) {
                        onDelete()
                        hideModalBottomSheet()
                    }
                    val shareTitle = stringResource(Res.string.share_url)
                    ActionButton(
                        icon = SimpIcons.Share,
                        text = if (ytPlaylistId != null) Res.string.share else Res.string.sync_first,
                        enable = (ytPlaylistId != null),
                    ) {
                        val url = "https://music.youtube.com/playlist?list=${ytPlaylistId?.replaceFirst("VL", "")}"
                        shareUrl(shareTitle, url)
                    }
                    EndOfModalBottomSheet()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortPlaylistBottomSheet(
    selectedState: FilterState,
    onDismiss: () -> Unit,
    onSortChanged: (FilterState) -> Unit,
) {
    val modelBottomSheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filterOptions =
        remember {
            listOf(
                FilterState.CustomOrder,
                FilterState.NewerFirst,
                FilterState.OlderFirst,
                FilterState.Title,
            )
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modelBottomSheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(5.dp))
                Card(
                    modifier = Modifier.width(60.dp).height(4.dp),
                    colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                    shape = RoundedCornerShape(50),
                ) {}
                Text(
                    stringResource(Res.string.sort_by),
                    style = typo().labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, top = 16.dp, bottom = 24.dp)
                            .align(Alignment.Start),
                )
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(filterOptions, key = { it.hashCode() }) { filterOption ->
                        val isSelected = filterOption == selectedState
                        Row(
                            Modifier
                                .padding(vertical = 4.dp)
                                .clickable {
                                    onSortChanged(filterOption)
                                    onDismiss()
                                }.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(filterOption.displayNameRes()),
                                style = typo().labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) seed else rememberSurfaceDarkColors().content,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (isSelected) {
                                Image(
                                    imageVector = SimpIcons.Done,
                                    contentDescription = "Selected",
                                    colorFilter = ColorFilter.tint(seed),
                                    modifier = Modifier.size(32.dp),
                                )
                            } else {
                                Spacer(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
                EndOfModalBottomSheet()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevLogInBottomSheet(
    onDismiss: () -> Unit,
    type: DevLogInType,
    onDone: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val modelBottomSheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var value by rememberSaveable { mutableStateOf("") }
    val processing = stringResource(Res.string.processing)
    val cannotBeEmpty = stringResource(Res.string.can_not_be_empty)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modelBottomSheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().container),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(5.dp))
                Card(
                    modifier = Modifier.width(60.dp).height(4.dp),
                    colors = CardDefaults.cardColors().copy(containerColor = rememberSurfaceDarkColors().handle),
                    shape = RoundedCornerShape(50),
                ) {}
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = stringResource(type.title), style = typo().labelSmall)
                Spacer(modifier = Modifier.height(5.dp))
                OutlinedTextField(
                    value = value,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    onValueChange = { value = it },
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(5.dp))
                TextButton(
                    onClick = {
                        if (value.isNotEmpty() && value.isNotBlank()) {
                            showToast(processing, ToastGravity.Bottom)
                            onDismiss()
                            onDone(value)
                        } else {
                            showToast(cannotBeEmpty, ToastGravity.Bottom)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(text = stringResource(Res.string.set), style = typo().labelSmall)
                }
                Spacer(modifier = Modifier.height(5.dp))
                EndOfModalBottomSheet()
            }
        }
    }
}

@Composable
fun EndOfModalBottomSheet() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding()
                        .value
                        .toInt()
                        .dp + 8.dp,
                ),
    ) {}
}

/** What the developer sign-in sheet asks for. Only YouTube is left; Spotify and Discord were removed with those features. */
sealed class DevLogInType(
    val title: StringResource,
) {
    data object YouTube : DevLogInType(Res.string.your_youtube_cookie)
}