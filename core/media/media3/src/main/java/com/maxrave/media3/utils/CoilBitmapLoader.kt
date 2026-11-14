package com.maxrave.media3.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.TypedValue
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SizeLimitedBitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlin.math.roundToInt

@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        coroutineScope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("Could not decode image data")
        }

    /**
     * Never fails. A failed artwork used to leave the session without a bitmap, and Android Auto
     * (and some lock screens) then kept showing the PREVIOUS song's cover next to the new title.
     * So a failure walks [artworkFallbacks] and ends at the app icon, which at least replaces it.
     */
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        coroutineScope.future(Dispatchers.IO) {
            for (candidate in artworkFallbacks(uri.toString())) {
                fetch(candidate)?.let { return@future it }
                Logger.w(TAG, "Artwork failed: $candidate")
            }
            placeholder()
        }

    private suspend fun fetch(url: String): Bitmap? =
        try {
            val result =
                context.imageLoader.execute(
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build(),
                )
            if (result is ErrorResult) null else result.image?.toBitmap()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }

    @Volatile
    private var placeholderBitmap: Bitmap? = null

    private fun placeholder(): Bitmap =
        placeholderBitmap ?: run {
            val size = 320
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF212121.toInt())
            try {
                val icon = context.packageManager.getApplicationIcon(context.applicationInfo)
                val inset = size / 4
                icon.setBounds(inset, inset, size - inset, size - inset)
                icon.draw(canvas)
            } catch (e: Exception) {
                Logger.w(TAG, "No app icon for the artwork placeholder: ${e.message}")
            }
            bitmap.also { placeholderBitmap = it }
        }

    private companion object {
        const val TAG = "CoilBitmapLoader"
    }
}

private val YTIMG_VIDEO_ID = Regex("""^https?://i\d?\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""")
private val GOOGLE_SIZE = Regex("""=(?:w\d+-h\d+|s\d+)[^/]*$""")

/**
 * The URLs to try, in order, for one artwork [url]: the URL itself, then smaller versions of it.
 * `maxresdefault.jpg` does not exist for many videos while `hqdefault.jpg` always does, and a
 * googleusercontent size suffix can be asked for at a size YouTube always serves.
 */
internal fun artworkFallbacks(url: String): List<String> {
    if (url.isBlank() || url == "null") return emptyList()
    val out = linkedSetOf(url)
    YTIMG_VIDEO_ID.find(url)?.groupValues?.get(1)?.let { id ->
        out += "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        out += "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }
    if (GOOGLE_SIZE.containsMatchIn(url)) {
        out += GOOGLE_SIZE.replace(url, "=w226-h226-l90-rj")
    }
    return out.toList()
}

/**
 * The dp the platform sizes metadata artwork by: `config_mediaMetadataBitmapMaxSize`, 320dp in
 * AOSP's `core/res/res/values/config.xml`.
 */
private const val ARTWORK_LIMIT_DP = 320f

/**
 * [loader] capped at the artwork size the platform's own `MediaSession` will accept.
 *
 * The framework resolves that limit against the APP's resources (AOSP `MediaSession.java`:
 * `mMaxBitmapSize = context.getResources().getDimensionPixelSize(config_mediaMetadataBitmapMaxSize)`,
 * identical on android14/15/16), while Media3 resolves the same dp against `Resources.getSystem()`.
 * On a ROM that gives the app a density of its own the two differ, `setMetadata` then rescales the
 * shared bitmap itself, and some ROMs recycle the source in that path — the next metadata update
 * dies with "cannot use a recycled source in createBitmap" (#2500, and #1248 / #1276 before it).
 *
 * Capping here with the framework's own number means the framework never has to touch the bitmap.
 * The number comes from the ROM itself ([platformArtworkLimitPx]) rather than from a hardcoded dp,
 * so a ROM carrying a different value is covered too.
 *
 * Any failure returns [loader] untouched, i.e. exactly today's behaviour.
 */
@UnstableApi
fun sizeLimitedForSession(
    context: Context,
    loader: BitmapLoader,
): BitmapLoader =
    try {
        val limit = platformArtworkLimitPx(context)
        // Media3 wraps this again with makeShared = true, so sharing here would copy the pixels twice.
        if (limit > 0) SizeLimitedBitmapLoader(loader, limit, /* makeShared = */ false) else loader
    } catch (e: Throwable) {
        Logger.w("CoilBitmapLoader", "Artwork size cap unavailable, using the plain loader: ${e.message}")
        loader
    }

/**
 * The value the ROM itself carries for `config_mediaMetadataBitmapMaxSize`, resolved against the
 * APP's resources — the same number `MediaSession.setMetadata` compares artwork against.
 *
 * It is an internal platform resource with no public constant, so a name lookup is the only way to
 * read it; media3-session's own `MediaSessionImpl.getMediaMetadataBitmapMaxSize()` does exactly
 * this, under the same lint suppression. A ROM that renamed or dropped it falls back to AOSP's own
 * [ARTWORK_LIMIT_DP].
 */
@SuppressLint("DiscouragedApi")
private fun platformArtworkLimitPx(context: Context): Int {
    val resources = context.resources
    val id = resources.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
    if (id != 0) {
        val fromRom = resources.getDimensionPixelSize(id)
        if (fromRom > 0) return fromRom
    }
    return TypedValue
        .applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            ARTWORK_LIMIT_DP,
            resources.displayMetrics,
        ).roundToInt()
}