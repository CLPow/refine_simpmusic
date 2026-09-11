package com.maxrave.simpmusic.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import androidx.glance.unit.ColorProvider
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import coil3.toBitmap
import com.kmpalette.rememberPaletteState
import com.maxrave.common.Config
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.extension.getColorFromPalette
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * A compact 2x2 widget: the artwork fills the tile with previous / play / next on a translucent
 * bar over its lower edge.
 *
 * The bar and the disc behind play are drawable backgrounds, not shapes painted into the
 * artwork. Two reasons: a drawable keeps its alpha through RemoteViews where a colour
 * background would come out opaque, and it scales with the layout — painting into the bitmap
 * tied it to the artwork's own scale, so resizing the widget pulled it away from the buttons.
 */
class TurntableWidget :
    GlanceAppWidget(),
    KoinComponent {
    // Exact, not the default Single: with Single the composition is laid out against the
    // XML min sizes and the launcher then squeezes that rendering into whatever cells it
    // actually granted — non-uniformly on grids whose cells have a different aspect, which
    // is how every square in these widgets came out a rectangle on some launchers. Exact
    // re-composes per granted size, so the layout works with the truth.
    override val sizeMode: SizeMode = SizeMode.Exact

    private val sharedViewModel by inject<SharedViewModel>()
    private val serviceScope by inject<CoroutineScope>(named(Config.SERVICE_SCOPE))

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        serviceScope.launch {
            val controllerJob =
                launch {
                    sharedViewModel.controllerState.collectLatest { updateWidget(context) }
                }
            val nowPlayingJob =
                launch {
                    sharedViewModel.nowPlayingScreenData.collectLatest { updateWidget(context) }
                }
            controllerJob.join()
            nowPlayingJob.join()
        }

        provideContent {
            GlanceTheme {
                val controllerState by sharedViewModel.controllerState.collectAsState()
                val screenDataState by sharedViewModel.nowPlayingScreenData.collectAsState()

                val paletteState = rememberPaletteState()
                var artwork by remember { mutableStateOf<Bitmap?>(null) }

                // Kept apart from [artwork] on purpose: the palette has to come from the
                // untouched cover. Running it on a cropped or round-cornered copy reads the
                // transparent corners as part of the image and lands on a different colour.
                var rawArtwork by remember { mutableStateOf<Bitmap?>(null) }
                var bgColor by remember { mutableStateOf(Color.Black) }

                val thumbUrl by remember { derivedStateOf { screenDataState.thumbnailURL } }

                LaunchedEffect(bgColor) { updateWidget(context) }

                LaunchedEffect(rawArtwork) {
                    rawArtwork?.asImageBitmap()?.let { paletteState.generate(it) }
                }

                LaunchedEffect(Unit) {
                    snapshotFlow { paletteState.palette }
                        .distinctUntilChanged()
                        .collectLatest { bgColor = it.getColorFromPalette() }
                }

                LaunchedEffect(thumbUrl) {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(thumbUrl)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            // Shared cache key across the widgets so they all read the identical
                            // decoded bitmap; a separate key can hand back a differently scaled
                            // copy, which is enough for the palette to pick another colour.
                            .diskCacheKey(thumbUrl + "BIGGER")
                            .placeholder(R.drawable.holder)
                            .error(R.drawable.holder)
                            .allowHardware(false)
                            .build()
                    val loaded =
                        (ImageLoader(context).execute(request) as? SuccessResult)
                            ?.image
                            ?.toBitmap()
                    rawArtwork = loaded
                    artwork = loaded
                }

                Box(
                    modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .background(ColorProvider(bgColor))
                            .padding(6.dp)
                            .clickable(actionStartActivity<MainActivity>(), rippleOverride = R.drawable.no_ripple),
                ) {
                    artwork?.let {
                        Image(
                            provider = ImageProvider(it),
                            contentDescription = screenDataState.nowPlayingTitle,
                            contentScale = ContentScale.Crop,
                            modifier =
                                GlanceModifier
                                    .fillMaxSize()
                                    .cornerRadius(android.R.dimen.system_app_widget_background_radius),
                        )
                    }
                    // Bottom stack: scrim + text + transport. Scrim keeps type legible on light covers.
                    Column(
                        modifier =
                            GlanceModifier
                                .fillMaxWidth()
                                .background(ImageProvider(R.drawable.widget_scrim_bottom))
                                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 10.dp),
                    ) {
                        Text(
                            text = screenDataState.nowPlayingTitle.ifBlank { "—" },
                            maxLines = 1,
                            style =
                                TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                        )
                        Text(
                            text = screenDataState.artistName.ifBlank { " " },
                            maxLines = 1,
                            style =
                                TextStyle(
                                    color = ColorProvider(Color.White.copy(alpha = 0.75f)),
                                    fontSize = 11.sp,
                                ),
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.rounded_skip_previous_24),
                                contentDescription = "Previous",
                                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                                modifier =
                                    GlanceModifier
                                        .size(30.dp)
                                        .padding(3.dp)
                                        .clickable(rippleOverride = R.drawable.no_ripple) {
                                            sharedViewModel.onUIEvent(UIEvent.Previous)
                                        },
                            )
                            Spacer(GlanceModifier.width(14.dp))
                            Box(
                                modifier =
                                    GlanceModifier
                                        .size(40.dp)
                                        .background(ImageProvider(R.drawable.widget_media_fab)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    provider =
                                        if (controllerState.isPlaying) {
                                            ImageProvider(R.drawable.rounded_pause_24)
                                        } else {
                                            ImageProvider(R.drawable.rounded_play_arrow_24)
                                        },
                                    contentDescription = if (controllerState.isPlaying) "Pause" else "Play",
                                    colorFilter = ColorFilter.tint(ColorProvider(Color.Black)),
                                    modifier =
                                        GlanceModifier
                                            .size(22.dp)
                                            .clickable(rippleOverride = R.drawable.no_ripple) {
                                                sharedViewModel.onUIEvent(UIEvent.PlayPause)
                                            },
                                )
                            }
                            Spacer(GlanceModifier.width(14.dp))
                            Image(
                                provider = ImageProvider(R.drawable.rounded_skip_next_24),
                                contentDescription = "Next",
                                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                                modifier =
                                    GlanceModifier
                                        .size(30.dp)
                                        .padding(3.dp)
                                        .clickable(rippleOverride = R.drawable.no_ripple) {
                                            sharedViewModel.onUIEvent(UIEvent.Next)
                                        },
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateWidget(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(this@TurntableWidget.javaClass).forEach { glanceId ->
            this@TurntableWidget.update(context, glanceId)
        }
    }
}