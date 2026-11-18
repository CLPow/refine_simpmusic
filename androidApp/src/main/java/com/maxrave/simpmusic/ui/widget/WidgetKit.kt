package com.maxrave.simpmusic.ui.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import coil3.SingletonImageLoader
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean

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
                .drop(1)
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

internal object WidgetStyle {
    private val Base = Color(0xFF121212)

    fun surfaceFor(palette: Color): Color = lerp(palette, Base, 0.62f)

    val OnSurface = Color.White
    val OnSurfaceMuted = Color.White.copy(alpha = 0.7f)

    val TitleSize = 15.sp
    val BodySize = 12.sp
    val LabelSize = 11.sp
}

internal suspend fun Context.loadWidgetArtwork(
    url: String?,
    px: Int = WIDGET_ART_PX,
): Bitmap? {
    if (url.isNullOrBlank()) return null
    return WidgetArtCache.get(this, url, px)
}

private object WidgetArtCache {
    private const val MAX_ENTRIES = 12
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true)

    suspend fun get(
        context: Context,
        url: String,
        px: Int,
    ): Bitmap? =
        mutex.withLock {
            val key = "$px@$url"
            entries[key]?.let { return@withLock it }
            val bitmap = runCatching { context.decodeWidgetArtwork(url, px) }.getOrNull() ?: return@withLock null
            entries[key] = bitmap
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            bitmap
        }
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
    return (SingletonImageLoader.get(this).execute(request) as? SuccessResult)?.image?.toBitmap()
}

private const val WIDGET_ART_PX = 240
