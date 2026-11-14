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

private const val ARTWORK_LIMIT_DP = 320f

// Capping here with the framework's own number means the framework never has to touch the bitmap.
@UnstableApi
fun sizeLimitedForSession(
    context: Context,
    loader: BitmapLoader,
): BitmapLoader =
    try {
        val limit = platformArtworkLimitPx(context)
        if (limit > 0) SizeLimitedBitmapLoader(loader, limit,  false) else loader
    } catch (e: Throwable) {
        Logger.w("CoilBitmapLoader", "Artwork size cap unavailable, using the plain loader: ${e.message}")
        loader
    }

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