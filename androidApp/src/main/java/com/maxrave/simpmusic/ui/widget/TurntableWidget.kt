package com.maxrave.simpmusic.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.glance.LocalContext

class TurntableWidget :
    GlanceAppWidget(),
    KoinComponent {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SQUARE, ROW, HEADER, FULL))

    private val sharedViewModel by inject<SharedViewModel>()
    private val songRepository by inject<SongRepository>()
    private val localPlayer by inject<MediaPlayerHandler>(named(Config.LOCAL_PLAYER))
    private val player by inject<MediaPlayerHandler>()

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        WidgetUpdater.ensureStarted(context)
        provideContent {
            GlanceTheme {
                val controllerState by sharedViewModel.controllerState.collectAsState()
                val screenData by sharedViewModel.nowPlayingScreenData.collectAsState()
                val size = LocalSize.current
                val layout =
                    when {
                        size.height < ROW_MAX_HEIGHT -> Layout.Row
                        size.width < WIDE_MIN_WIDTH -> Layout.Square
                        size.height < STRIP_MIN_HEIGHT -> Layout.Header
                        else -> Layout.Full
                    }

                val idle = screenData.nowPlayingTitle.isBlank()
                var lastPlayed by remember { mutableStateOf<SongEntity?>(null) }
                LaunchedEffect(idle) {
                    lastPlayed = if (idle) runCatching { songRepository.getRecentSong(1, 0).firstOrNull() }.getOrNull() else null
                }
                val fallback = lastPlayed?.takeIf { idle }
                val thumbnail = if (idle) fallback?.thumbnails else screenData.thumbnailURL

                var artwork by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(thumbnail, layout) {
                    val art = context.loadWidgetArtwork(thumbnail)
                    artwork = if (layout == Layout.Square) art?.let { runCatching { it.toCircle() }.getOrNull() } else art
                }

                var recent by remember { mutableStateOf<List<RecentTile>>(emptyList()) }
                LaunchedEffect(screenData.nowPlayingTitle, layout) {
                    if (layout != Layout.Full) return@LaunchedEffect
                    recent =
                        runCatching { songRepository.getRecentSong(RECENT_COUNT + 1, 0) }
                            .getOrDefault(emptyList())
                            .filter { it.title != screenData.nowPlayingTitle }
                            .take(RECENT_COUNT)
                            .map { RecentTile(it, context.loadWidgetArtwork(it.thumbnails, RECENT_ART_PX)) }
                }

                val state =
                    NowPlayingWidgetState(
                        title = if (idle) fallback?.title.orEmpty() else screenData.nowPlayingTitle,
                        artist = if (idle) fallback?.artistName?.joinToString(", ").orEmpty() else screenData.artistName,
                        artwork = artwork,
                        isPlaying = controllerState.isPlaying,
                        isLiked = controllerState.isLiked,
                    )
                val actions =
                    NowPlayingWidgetActions(
                        onPlayPause = {
                            if (player.nowPlayingState.value.isNotEmpty()) {
                                sharedViewModel.onUIEvent(UIEvent.PlayPause)
                            } else {
                                restoreScope.launch { localPlayer.restoreQueueAndPlay() }
                            }
                        },
                        onLike = { sharedViewModel.onUIEvent(UIEvent.ToggleLike) },
                        onPlaySong = { song -> sharedViewModel.loadMediaItemFromTrack(song.toTrack(), Config.SONG_CLICK) },
                    )
                when (layout) {
                    Layout.Row -> RowLayout(state, actions)
                    Layout.Square -> SquareLayout(state, actions)
                    Layout.Header -> WideLayout(state, actions, recent = null)
                    Layout.Full -> WideLayout(state, actions, recent = recent)
                }
            }
        }
    }

    private enum class Layout { Square, Row, Header, Full }

    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object {
        val SQUARE = DpSize(110.dp, 110.dp)
        val ROW = DpSize(250.dp, 56.dp)
        val HEADER = DpSize(250.dp, 110.dp)
        val FULL = DpSize(250.dp, 180.dp)
        val ROW_MAX_HEIGHT = 100.dp
        val WIDE_MIN_WIDTH = 200.dp
        val STRIP_MIN_HEIGHT = 170.dp
        const val RECENT_COUNT = 5
        const val RECENT_ART_PX = 112
    }
}

private data class NowPlayingWidgetState(
    val title: String,
    val artist: String,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
) {
    val isEmpty: Boolean get() = title.isBlank()
}

private class NowPlayingWidgetActions(
    val onPlayPause: () -> Unit,
    val onLike: () -> Unit,
    val onPlaySong: (SongEntity) -> Unit,
)

private class RecentTile(
    val song: SongEntity,
    val artwork: Bitmap?,
)

private val openApp = actionStartActivity<MainActivity>()

@SuppressLint("RestrictedApi")
@Composable
private fun SquareLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    val size = LocalSize.current
    val side = minOf(size.width, size.height) - 8.dp
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(openApp, rippleOverride = R.drawable.no_ripple),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = GlanceModifier.size(side)) {
            val art = state.artwork
            if (art != null) {
                Image(
                    provider = ImageProvider(art),
                    contentDescription = state.title,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(side / 2),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.mono),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                        modifier = GlanceModifier.size(side / 3),
                    )
                }
            }
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                LikeButton(state, actions, width = side * 0.26f, height = side * 0.26f, radius = side * 0.13f)
            }
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                PlayButton(state, actions, width = side * 0.33f, height = side * 0.33f, radius = side * 0.11f)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun RowLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(horizontal = 12.dp)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(state, 38.dp, 8.dp)
        Spacer(GlanceModifier.width(14.dp))
        TitleBlock(state, GlanceModifier.defaultWeight(), titleSize = 14, titleLines = 1)
        Spacer(GlanceModifier.width(8.dp))
        LikeButton(state, actions, width = 40.dp, height = 38.dp, radius = 12.dp)
        Spacer(GlanceModifier.width(4.dp))
        PlayButton(state, actions, width = 46.dp, height = 38.dp, radius = 12.dp)
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun WideLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
    recent: List<RecentTile>?,
) {
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(if (recent != null) GlanceTheme.colors.surfaceVariant else GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
    ) {
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .then(if (recent != null) GlanceModifier.height(92.dp) else GlanceModifier.defaultWeight())
                    .background(GlanceTheme.colors.widgetBackground)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(state, 60.dp, 16.dp)
            Spacer(GlanceModifier.width(16.dp))
            TitleBlock(state, GlanceModifier.defaultWeight(), titleSize = 16, titleLines = 2)
            Spacer(GlanceModifier.width(8.dp))
            LikeButton(state, actions, width = 48.dp, height = 60.dp, radius = 16.dp)
            Spacer(GlanceModifier.width(8.dp))
            PlayButton(state, actions, width = 60.dp, height = 60.dp, radius = 16.dp)
        }
        if (recent != null) {
            RecentStrip(recent, actions)
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun RecentStrip(
    recent: List<RecentTile>,
    actions: NowPlayingWidgetActions,
) {
    val tileSide = minOf(60.dp, (LocalSize.current.width - 16.dp) / 6)
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        repeat(5) { index ->
            val tile = recent.getOrNull(index)
            val art = tile?.artwork
            val base = GlanceModifier.size(tileSide).cornerRadius(16.dp)
            if (tile != null && art != null) {
                Image(
                    provider = ImageProvider(art),
                    contentDescription = tile.song.title,
                    contentScale = ContentScale.Crop,
                    modifier = base.clickable(rippleOverride = R.drawable.no_ripple) { actions.onPlaySong(tile.song) },
                )
            } else {
                Box(base.background(GlanceTheme.colors.widgetBackground)) {}
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun Cover(
    state: NowPlayingWidgetState,
    side: Dp,
    radius: Dp,
) {
    val art = state.artwork
    if (art != null) {
        Image(
            provider = ImageProvider(art),
            contentDescription = state.title,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(side).cornerRadius(radius),
        )
    } else {
        Box(
            modifier = GlanceModifier.size(side).cornerRadius(radius).background(GlanceTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.mono),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(side / 2),
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun TitleBlock(
    state: NowPlayingWidgetState,
    modifier: GlanceModifier,
    titleSize: Int,
    titleLines: Int,
) {
    Column(modifier = modifier) {
        Text(
            text = if (state.isEmpty) LocalContext.current.getString(R.string.app_name) else state.title,
            maxLines = titleLines,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = titleSize.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = if (state.isEmpty) LocalContext.current.getString(R.string.widget_tap_to_play) else state.artist,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun LikeButton(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
    width: Dp,
    height: Dp,
    radius: Dp,
) {
    Box(
        modifier =
            GlanceModifier
                .size(width, height)
                .cornerRadius(radius)
                .background(GlanceTheme.colors.tertiaryContainer)
                .clickable(rippleOverride = R.drawable.no_ripple) { actions.onLike() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (state.isLiked) R.drawable.widget_thumb_up_filled_24 else R.drawable.widget_thumb_up_24),
            contentDescription = LocalContext.current.getString(if (state.isLiked) R.string.widget_liked else R.string.widget_like),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onTertiaryContainer),
            modifier = GlanceModifier.size(minOf(width, height) * 0.45f),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun PlayButton(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
    width: Dp,
    height: Dp,
    radius: Dp,
) {
    Box(
        modifier =
            GlanceModifier
                .size(width, height)
                .cornerRadius(radius)
                .background(GlanceTheme.colors.primary)
                .clickable(rippleOverride = R.drawable.no_ripple) { actions.onPlayPause() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (state.isPlaying) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24),
            contentDescription = LocalContext.current.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(minOf(width, height) * 0.5f),
        )
    }
}
