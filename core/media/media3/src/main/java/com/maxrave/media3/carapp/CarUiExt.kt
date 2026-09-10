package com.maxrave.media3.carapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarIconSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.Row
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private const val DEFAULT_LIST_LIMIT = 100
private const val DEFAULT_GRID_LIMIT = 24
private const val ARTWORK_CORNER_RADIUS_FRACTION = 0.1f

/** Hosts offer no rounded-corner image API (only square/circle), so the corners are baked in. */
private fun Bitmap.withRoundedCorners(): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = minOf(width, height) * ARTWORK_CORNER_RADIUS_FRACTION
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)
    return output
}

/** Host row budget for list templates, with a sane fallback. */
internal fun CarContext.listContentLimit(): Int =
    runCatching {
        getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(DEFAULT_LIST_LIMIT)

/** Host tile budget for grid templates, with a sane fallback. */
internal fun CarContext.gridContentLimit(): Int =
    runCatching {
        getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
    }.getOrDefault(DEFAULT_GRID_LIMIT)

/**
 * Whether a browse level reads better as artwork tiles than as text rows.
 *
 * Collections — playlists, albums, artists — are picked out by their cover, which is what makes
 * the YouTube Music car surface scannable at a glance; a list of titles forces the driver to read.
 * Songs are the opposite: they are chosen by name, sit in a defined order, and a grid of near
 * identical album covers is slower to work through than one line each.
 *
 * The majority decides, so a shelf holding a few loose tracks among its albums still renders as a
 * grid rather than collapsing to text.
 */
internal fun List<MediaItem>.rendersAsGrid(): Boolean =
    isNotEmpty() && count { it.mediaMetadata.isBrowsable == true } * 2 >= size

/**
 * Stand-in tile image. GridItem demands an image at build time, so a tile whose artwork has not
 * arrived yet still needs something to draw — without this the whole grid would have to wait for
 * the slowest download before any of it appeared.
 */
internal fun CarContext.placeholderArtwork(): CarIcon =
    CarIcon
        .Builder(IconCompat.createWithResource(this, R.drawable.baseline_album_24))
        .build()

/**
 * Shared browse tile: large artwork, title, and one line of secondary text — the grid counterpart
 * of [mediaRow], carrying the same now-playing marker so the two surfaces agree.
 */
internal fun CarContext.mediaGridItem(
    item: MediaItem,
    artwork: CarIcon?,
    isPlaying: Boolean,
    onClick: () -> Unit,
): GridItem {
    val metadata = item.mediaMetadata
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
    val subtitle = (metadata.subtitle ?: metadata.artist)?.toString().orEmpty()
    return GridItem
        .Builder()
        .setTitle(title)
        .apply {
            when {
                isPlaying -> setText(nowPlayingText(subtitle))
                subtitle.isNotBlank() -> setText(subtitle)
            }
        }.setImage(artwork ?: placeholderArtwork(), GridItem.IMAGE_TYPE_LARGE)
        .setOnClickListener { onClick() }
        .build()
}

/**
 * Secondary-text line with a static equalizer glyph in front of [suffix],
 * marking the currently playing row. Static by design: animating it requires
 * re-sending the whole template every frame (~420KB of binder traffic per
 * tick), which floods the host and starves its input handling.
 */
internal fun CarContext.nowPlayingText(suffix: String): CharSequence =
    SpannableString(if (suffix.isBlank()) " " else "  $suffix").apply {
        setSpan(
            CarIconSpan.create(
                CarIcon
                    .Builder(IconCompat.createWithResource(this@nowPlayingText, R.drawable.ic_car_now_playing))
                    .build(),
                CarIconSpan.ALIGN_CENTER,
            ),
            0,
            1,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }

/**
 * Shared browse/search row: artwork as the start image, equalizer marker on
 * the playing row, chevron for browsable nodes.
 */
internal fun CarContext.mediaRow(
    item: MediaItem,
    artwork: CarIcon?,
    isPlaying: Boolean,
    onClick: () -> Unit,
): Row {
    val metadata = item.mediaMetadata
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
    return Row
        .Builder()
        .setTitle(title)
        .apply {
            val subtitle = metadata.subtitle?.toString().orEmpty()
            if (isPlaying) {
                addText(nowPlayingText(subtitle))
            } else if (subtitle.isNotBlank()) {
                addText(subtitle)
            }
            artwork?.let { setImage(it) }
            setBrowsable(metadata.isBrowsable == true)
        }.setOnClickListener { onClick() }
        .build()
}

/**
 * Fire-and-forget play through the session's browser. Runs on the future's
 * executor, not a screen scope: navigating right after pops the screen and
 * would cancel a coroutine before the command is sent.
 */
@UnstableApi
internal fun playViaBrowser(
    carContext: CarContext,
    browserFuture: ListenableFuture<MediaBrowser>,
    item: MediaItem,
    tag: String,
) {
    browserFuture.addListener({
        runCatching {
            val browser = browserFuture.get()
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
        }.onFailure {
            Logger.e(tag, "playItem(${item.mediaId}) failed: ${it.message}")
        }
    }, ContextCompat.getMainExecutor(carContext))
}

/**
 * Show now-playing without wiping the browse stack.
 *
 * Older code always did `popToRoot()` then `push(NowPlaying)`, which threw the driver back to
 * Home every time a track was picked from a playlist or search — the opposite of a smooth
 * A→B transition. This leaves an already-open player alone and otherwise pushes on top so
 * BACK returns to the list that started the song.
 */
internal fun openNowPlayingOrStay(
    carContext: CarContext,
    screenManager: ScreenManager,
) {
    if (screenManager.top is NowPlayingCarScreen) return
    screenManager.push(NowPlayingCarScreen(carContext))
}

/** Loads row artwork into [artwork], invalidating via [onLoaded] per image. */
@UnstableApi
internal fun loadArtworkInto(
    scope: CoroutineScope,
    bitmapLoader: CoilBitmapLoader,
    items: List<MediaItem>,
    limit: Int,
    artwork: MutableMap<String, CarIcon>,
    tag: String,
    onLoaded: () -> Unit,
) = loadArtworkUrlsInto(
    scope = scope,
    bitmapLoader = bitmapLoader,
    sources = items.map { it.mediaId to it.mediaMetadata.artworkUri?.toString() },
    limit = limit,
    artwork = artwork,
    tag = tag,
    onLoaded = onLoaded,
)

/**
 * Artwork loader keyed on plain id/url pairs, for screens whose items are not [MediaItem]s — the
 * queue holds domain tracks. Entries with no url are skipped rather than cached as misses, so an
 * item whose artwork arrives later still loads on the next pass.
 */
@UnstableApi
internal fun loadArtworkUrlsInto(
    scope: CoroutineScope,
    bitmapLoader: CoilBitmapLoader,
    sources: List<Pair<String, String?>>,
    limit: Int,
    artwork: MutableMap<String, CarIcon>,
    tag: String,
    onLoaded: () -> Unit,
) {
    sources.take(limit).forEach { (id, url) ->
        val uri = url?.takeIf { it.isNotBlank() }?.toUri() ?: return@forEach
        if (artwork.containsKey(id)) return@forEach
        scope.launch {
            try {
                val bitmap = bitmapLoader.loadBitmap(uri).await()
                artwork[id] =
                    CarIcon.Builder(IconCompat.createWithBitmap(bitmap.withRoundedCorners())).build()
                onLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "artwork($id) failed: ${e.message}")
            }
        }
    }
}
