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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kmpalette.rememberPaletteState
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.extension.getColorFromPalette
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The Now Playing widget. The class name is kept from its earlier "turntable" form so widgets
 * already placed on a home screen carry over through the update.
 *
 * Three layouts, picked by the size the launcher actually grants:
 * - small (about 2x2): the artwork, with play/pause floating on it;
 * - bar (about 4x1): artwork, title and artist, then previous / play / next;
 * - full (4x2 and up): artwork beside title, artist and the transport row with like.
 *
 * Title and artist are always the track's own — never a lyric line. There is no progress bar on
 * purpose: keeping one live would mean re-rendering the widget every second, which is exactly the
 * battery cost widgets exist to avoid.
 */
class TurntableWidget :
    GlanceAppWidget(),
    KoinComponent {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, BAR, FULL))

    private val sharedViewModel by inject<SharedViewModel>()

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

                var artwork by remember { mutableStateOf<Bitmap?>(null) }
                var palette by remember { mutableStateOf(Color(0xFF2A2A2A)) }
                val paletteState = rememberPaletteState()

                LaunchedEffect(screenData.thumbnailURL) {
                    artwork = context.loadWidgetArtwork(screenData.thumbnailURL)
                    artwork?.asImageBitmap()?.let { paletteState.generate(it) }
                }
                LaunchedEffect(paletteState.palette) {
                    paletteState.palette?.let { palette = it.getColorFromPalette() }
                }

                val state =
                    NowPlayingWidgetState(
                        title = screenData.nowPlayingTitle,
                        artist = screenData.artistName,
                        artwork = artwork,
                        isPlaying = controllerState.isPlaying,
                        isLiked = controllerState.isLiked,
                        surface = WidgetStyle.surfaceFor(palette),
                    )
                val actions =
                    NowPlayingWidgetActions(
                        onPlayPause = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                        onNext = { sharedViewModel.onUIEvent(UIEvent.Next) },
                        onPrevious = { sharedViewModel.onUIEvent(UIEvent.Previous) },
                        onLike = { sharedViewModel.onUIEvent(UIEvent.ToggleLike) },
                    )
                val size = LocalSize.current
                when {
                    size.height < BAR_MAX_HEIGHT -> BarLayout(state, actions)
                    size.width < FULL_MIN_WIDTH -> SmallLayout(state, actions)
                    else -> FullLayout(state, actions)
                }
            }
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val BAR = DpSize(250.dp, 60.dp)
        val FULL = DpSize(250.dp, 110.dp)
        val BAR_MAX_HEIGHT = 100.dp
        val FULL_MIN_WIDTH = 200.dp
    }
}

private data class NowPlayingWidgetState(
    val title: String,
    val artist: String,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val surface: Color,
) {
    val isEmpty: Boolean get() = title.isBlank()
}

private class NowPlayingWidgetActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onLike: () -> Unit,
)

private val openApp = actionStartActivity<MainActivity>()

@SuppressLint("RestrictedApi")
@Composable
private fun SmallLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(state.surface))
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Artwork(state, GlanceModifier.fillMaxSize())
        Box(GlanceModifier.padding(10.dp)) {
            PlayButton(state, actions, 44.dp)
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun BarLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(state.surface))
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(8.dp)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(state, GlanceModifier.size(44.dp).cornerRadius(8.dp))
        Spacer(GlanceModifier.width(10.dp))
        TitleBlock(state, GlanceModifier.defaultWeight())
        TransportButton(R.drawable.rounded_skip_previous_24, "Previous", actions.onPrevious)
        PlayButton(state, actions, 38.dp)
        TransportButton(R.drawable.rounded_skip_next_24, "Next", actions.onNext)
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun FullLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    val artSide = (LocalSize.current.height - 24.dp).coerceAtMost(140.dp)
    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(state.surface))
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(12.dp)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(state, GlanceModifier.size(artSide).cornerRadius(12.dp))
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            TitleBlock(state, GlanceModifier.fillMaxWidth())
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    if (state.isLiked) R.drawable.baseline_favorite_24 else R.drawable.baseline_favorite_border_24,
                    "Like",
                    actions.onLike,
                )
                Spacer(GlanceModifier.defaultWeight())
                TransportButton(R.drawable.rounded_skip_previous_24, "Previous", actions.onPrevious)
                Spacer(GlanceModifier.width(6.dp))
                PlayButton(state, actions, 44.dp)
                Spacer(GlanceModifier.width(6.dp))
                TransportButton(R.drawable.rounded_skip_next_24, "Next", actions.onNext)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun Artwork(
    state: NowPlayingWidgetState,
    modifier: GlanceModifier,
) {
    val art = state.artwork
    if (art != null) {
        Image(
            provider = ImageProvider(art),
            contentDescription = state.title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(ImageProvider(R.drawable.widget_shortcut_tile)), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.mono),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(WidgetStyle.OnSurfaceMuted)),
                modifier = GlanceModifier.size(28.dp),
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun TitleBlock(
    state: NowPlayingWidgetState,
    modifier: GlanceModifier,
) {
    Column(modifier = modifier) {
        Text(
            text = if (state.isEmpty) "Refine Music" else state.title,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(WidgetStyle.OnSurface), fontSize = WidgetStyle.TitleSize, fontWeight = FontWeight.Bold),
        )
        Text(
            text = if (state.isEmpty) "Tap to play" else state.artist,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(WidgetStyle.OnSurfaceMuted), fontSize = WidgetStyle.BodySize),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun PlayButton(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
    side: Dp,
) {
    Box(
        modifier =
            GlanceModifier
                .size(side)
                .background(ImageProvider(R.drawable.widget_media_fab))
                .clickable(rippleOverride = R.drawable.no_ripple) { actions.onPlayPause() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (state.isPlaying) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24),
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            colorFilter = ColorFilter.tint(ColorProvider(Color.Black)),
            modifier = GlanceModifier.size(side * 0.55f),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun TransportButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(ColorProvider(WidgetStyle.OnSurface)),
        modifier =
            GlanceModifier
                .size(36.dp)
                .padding(6.dp)
                .clickable(rippleOverride = R.drawable.no_ripple) { onClick() },
    )
}
