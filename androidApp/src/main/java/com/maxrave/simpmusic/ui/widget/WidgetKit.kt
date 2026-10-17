package com.maxrave.simpmusic.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps every Refine widget in step with playback — one collector for the whole process.
 *
 * The widgets used to start their own collectors in `provideGlance`, on the shared service scope.
 * `provideGlance` runs again for every new Glance session (sessions close after a short idle), so
 * each one added another collector that was never cancelled, and every collector called
 * `update()` on every change. After a day of use that was dozens of loops re-rendering the same
 * widgets. Now `provideGlance` only renders; this object owns the one subscription, on its own
 * scope that no service teardown can cancel, and debounces bursts (a track change emits several
 * fields in a row) into a single update.
 */
object WidgetUpdater : KoinComponent {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sharedViewModel by inject<SharedViewModel>()

    private data class PlaybackSignature(
        val title: String,
        val artist: String,
        val thumbnail: String?,
        val isPlaying: Boolean,
        val isLiked: Boolean,
        val hasNext: Boolean,
        val hasPrevious: Boolean,
    )

    @OptIn(FlowPreview::class)
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            combine(sharedViewModel.nowPlayingScreenData, sharedViewModel.controllerState) { screen, controller ->
                PlaybackSignature(
                    title = screen.nowPlayingTitle,
                    artist = screen.artistName,
                    thumbnail = screen.thumbnailURL,
                    isPlaying = controller.isPlaying,
                    isLiked = controller.isLiked,
                    hasNext = controller.isNextAvailable,
                    hasPrevious = controller.isPreviousAvailable,
                )
            }.distinctUntilChanged()
                .drop(1) // the first value is what the widgets were just rendered with
                .debounce(UPDATE_DEBOUNCE_MS)
                .collect { signature ->
                    try {
                        TurntableWidget().updateAll(appContext)
                    } catch (e: Exception) {
                        Logger.w("WidgetUpdater", "Widget update failed: ${e.message}")
                    }
                }
        }
    }


    private const val UPDATE_DEBOUNCE_MS = 400L
}

/** Shared look for every Refine widget. */
internal object WidgetStyle {
    /** Near-black base the artwork colour is blended into — the mini player's recipe. */
    private val Base = Color(0xFF121212)

    /** A dark surface tinted by the artwork, so white text always reads on it. */
    fun surfaceFor(palette: Color): Color = lerp(palette, Base, 0.62f)

    val OnSurface = Color.White
    val OnSurfaceMuted = Color.White.copy(alpha = 0.7f)

    val TitleSize = 15.sp
    val BodySize = 12.sp
    val LabelSize = 11.sp
}

/**
 * Decodes an artwork for a widget. Software bitmaps only: RemoteViews cannot carry hardware ones.
 * The "BIGGER" disk key is shared with the player so every surface reads the identical decode,
 * which keeps the palette — and so the widget colour — consistent across them.
 */
internal suspend fun Context.loadWidgetArtwork(
    url: String?,
    px: Int = WIDGET_ART_PX,
): Bitmap? {
    if (url.isNullOrBlank()) return null
    // Never throws: an exception here surfaces inside the widget's composition, and Glance then
    // replaces the whole widget with "Can't show content". A cover that fails is just no cover.
    return runCatching { decodeWidgetArtwork(url, px) }.getOrNull()
}

private suspend fun Context.decodeWidgetArtwork(
    url: String,
    px: Int,
): Bitmap? {
    val request =
        ImageRequest
            .Builder(this)
            .data(url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCacheKey(url + "BIGGER")
            .allowHardware(false)
            .size(px, px)
            .build()
    return (ImageLoader(this).execute(request) as? SuccessResult)?.image?.toBitmap()
}

/**
 * Every bitmap in a widget travels in one binder transaction, capped near 1 MB. The 4x2 layout
 * carries the cover and five recent covers; at 360 px and 160 px that came to about 1 MB and a
 * large enough set failed the update outright. 240 px is still sharp at widget size.
 */
private const val WIDGET_ART_PX = 240

/**
 * The bitmap cut to a circle, centre-cropped. Done on the bitmap rather than with a corner radius
 * because Glance only clips views from Android 12, and the 2×2 widget's round cover is its shape.
 */
internal fun Bitmap.toCircle(): Bitmap {
    val side = minOf(width, height)
    val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, (side - width) / 2f, (side - height) / 2f, paint)
    return out
}
