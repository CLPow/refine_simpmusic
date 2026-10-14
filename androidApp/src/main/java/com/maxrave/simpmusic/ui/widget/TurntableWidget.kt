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
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The Now Playing widget, in YouTube Music's widget form. The class name is kept from its
 * earlier "turntable" form so widgets already on a home screen carry over through the update.
 *
 * Like YouTube Music's own widget it is drawn in Material You colours (the wallpaper palette on
 * Android 12+, through [GlanceTheme]), with the play control as a filled circle in the primary
 * container colour. Three layouts, picked from the size the launcher grants:
 * - 2×2: the cover fills the tile; title, artist and play sit on a scrim along its bottom;
 * - 4×1: cover · title and artist · previous, play, next;
 * - 4×2: the cover beside the title, artist and app mark, with like · previous · play · next below.
 *
 * No progress bar: a live one means re-rendering the widget every second, the battery cost
 * widgets are meant to avoid (YouTube Music's widget has none either).
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
                LaunchedEffect(screenData.thumbnailURL) {
                    artwork = context.loadWidgetArtwork(screenData.thumbnailURL)
                }

                val state =
                    NowPlayingWidgetState(
                        title = screenData.nowPlayingTitle,
                        artist = screenData.artistName,
                        artwork = artwork,
                        isPlaying = controllerState.isPlaying,
                        isLiked = controllerState.isLiked,
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
                    size.width < FULL_MIN_WIDTH -> SquareLayout(state, actions)
                    else -> FullLayout(state, actions)
                }
            }
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val BAR = DpSize(250.dp, 56.dp)
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

/** 2×2 — the cover is the widget. */
@SuppressLint("RestrictedApi")
@Composable
private fun SquareLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        contentAlignment = Alignment.BottomStart,
    ) {
        Artwork(state, GlanceModifier.fillMaxSize())
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .background(ImageProvider(R.drawable.widget_scrim_bottom))
                    .padding(start = 12.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    text = if (state.isEmpty) "Refine Music" else state.title,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = if (state.isEmpty) "Tap to play" else state.artist,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.75f)), fontSize = 11.sp),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
            PlayButton(state, actions, 40.dp)
        }
    }
}

/** 4×1 — one row, YouTube Music's slim widget. */
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
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(state, GlanceModifier.size(44.dp).cornerRadius(10.dp))
        Spacer(GlanceModifier.width(10.dp))
        TitleBlock(state, GlanceModifier.defaultWeight(), titleSize = 14)
        ControlIcon(R.drawable.rounded_skip_previous_24, "Previous", actions.onPrevious)
        PlayButton(state, actions, 40.dp)
        ControlIcon(R.drawable.rounded_skip_next_24, "Next", actions.onNext)
    }
}

/** 4×2 — cover on the left, everything else beside it. */
@SuppressLint("RestrictedApi")
@Composable
private fun FullLayout(
    state: NowPlayingWidgetState,
    actions: NowPlayingWidgetActions,
) {
    val artSide = (LocalSize.current.height - 24.dp).coerceAtMost(150.dp)
    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(12.dp)
                .clickable(openApp, rippleOverride = R.drawable.no_ripple),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(state, GlanceModifier.size(artSide).cornerRadius(16.dp))
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                TitleBlock(state, GlanceModifier.defaultWeight(), titleSize = 16)
                Image(
                    provider = ImageProvider(R.drawable.mono),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(20.dp),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlIcon(
                    if (state.isLiked) R.drawable.baseline_favorite_24 else R.drawable.baseline_favorite_border_24,
                    "Like",
                    actions.onLike,
                )
                Spacer(GlanceModifier.defaultWeight())
                ControlIcon(R.drawable.rounded_skip_previous_24, "Previous", actions.onPrevious)
                Spacer(GlanceModifier.width(4.dp))
                PlayButton(state, actions, 48.dp)
                Spacer(GlanceModifier.width(4.dp))
                ControlIcon(R.drawable.rounded_skip_next_24, "Next", actions.onNext)
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
        Box(modifier = modifier.background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.mono),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
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
    titleSize: Int,
) {
    Column(modifier = modifier) {
        Text(
            text = if (state.isEmpty) "Refine Music" else state.title,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = titleSize.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = if (state.isEmpty) "Tap to play" else state.artist,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

/** The filled circle YouTube Music's widget uses for play/pause, in the primary container colour. */
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
                .background(
                    ImageProvider(R.drawable.widget_media_fab),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer),
                ).clickable(rippleOverride = R.drawable.no_ripple) { actions.onPlayPause() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (state.isPlaying) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24),
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            modifier = GlanceModifier.size(side * 0.55f),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun ControlIcon(
    icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        modifier =
            GlanceModifier
                .size(40.dp)
                .padding(8.dp)
                .clickable(rippleOverride = R.drawable.no_ripple) { onClick() },
    )
}
