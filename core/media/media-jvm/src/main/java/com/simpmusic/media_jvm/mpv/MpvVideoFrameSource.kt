package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "MpvVideoFrameSource"

private const val FALLBACK_WIDTH = 320

private const val FALLBACK_HEIGHT = 180

class MpvVideoFrameSource {
    private val swFormat = "bgr0"

    private val bytesPerPixel = 4

    private val alignment = 64L

    private val lib: MpvLibrary? = MpvLibrary.INSTANCE

    @Volatile
    private var renderCtx: Pointer? = null

    private val surfaceLock = Any()

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var requestedWidth = 0

    @Volatile
    private var requestedHeight = 0

    private val _frames = MutableStateFlow<BufferedImage?>(null)

    val frames: StateFlow<BufferedImage?> = _frames.asStateFlow()

    private val frameSignal = Semaphore(0)

    @Volatile
    private var running = false

    private var renderThread: Thread? = null

    private val apiTypeMem =
        Memory(MPV_RENDER_API_TYPE_SW.toByteArray(Charsets.UTF_8).size + 1L).apply {
            setString(0, MPV_RENDER_API_TYPE_SW)
        }

    private val formatMem =
        Memory(swFormat.toByteArray(Charsets.UTF_8).size + 1L).apply {
            setString(0, swFormat)
        }

    private val updateCallback =
        MpvRenderUpdateFn {
            frameSignal.release()
        }

    @Suppress("unused")
    private class Surface(
        val width: Int,
        val height: Int,
        val bufferWidth: Int,
        val pixelBuffer: Pointer,
        val backing: Memory,
        val pixels: IntArray,
        val sizeMem: Memory,
        val strideMem: Memory,
        val renderParams: Memory,
    )

    fun setTargetSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        if (width == requestedWidth && height == requestedHeight) return
        requestedWidth = width
        requestedHeight = height
        frameSignal.release()
    }

    fun attach(ctx: Pointer): Boolean {
        val library = lib ?: return false
        if (renderCtx != null) return true

        val params = buildRenderParams(listOf(MpvRenderParamType.API_TYPE to apiTypeMem))
        val out = PointerByReference()
        val rc =
            try {
                library.mpv_render_context_create(out, ctx, params)
            } catch (e: Throwable) {
                Logger.e(TAG, "mpv_render_context_create threw: ${e.message}")
                return false
            }
        if (rc < 0) {
            Logger.e(TAG, "mpv_render_context_create failed: ${library.mpv_error_string(rc)}")
            return false
        }
        val created = out.value
        if (created == null) {
            Logger.e(TAG, "mpv_render_context_create returned a null context")
            return false
        }
        renderCtx = created

        library.mpv_render_context_set_update_callback(created, updateCallback, null)

        running = true
        renderThread =
            Thread({ renderLoop(library) }, "Mpv-Render-Thread").apply {
                isDaemon = true
                start()
            }
        Logger.d(TAG, "mpv software render context created")
        return true
    }

    fun detach() {
        val library = lib
        val ctx = renderCtx

        running = false
        frameSignal.release()

        val thread = renderThread
        renderThread = null
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        renderCtx = null
        if (library != null && ctx != null) {
            try {
                library.mpv_render_context_set_update_callback(ctx, null, null)
                library.mpv_render_context_free(ctx)
            } catch (e: Throwable) {
                Logger.w(TAG, "Error freeing render context: ${e.message}")
            }
        }
        synchronized(surfaceLock) { surface = null }
        _frames.value = null
    }

    private fun renderLoop(library: MpvLibrary) {
        while (running) {
            try {
                frameSignal.tryAcquire(100, TimeUnit.MILLISECONDS)
                frameSignal.drainPermits()
                if (!running) return

                val ctx = renderCtx ?: continue

                val resized = ensureSurface()
                val target = surface ?: continue

                val hasFrame = (library.mpv_render_context_update(ctx) and MpvRenderUpdateFlag.FRAME.toLong()) != 0L
                if (!hasFrame && !resized) continue

                val rc = library.mpv_render_context_render(ctx, target.renderParams)
                if (rc < 0) {
                    Logger.w(TAG, "mpv_render_context_render failed: ${library.mpv_error_string(rc)}")
                    continue
                }

                val copied =
                    synchronized(surfaceLock) {
                        if (surface === target) {
                            target.pixelBuffer.read(0L, target.pixels, 0, target.pixels.size)
                            true
                        } else {
                            false
                        }
                    }
                if (!copied) continue

                val snapshot = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_RGB)
                val snapshotPixels = (snapshot.raster.dataBuffer as DataBufferInt).data
                for (y in 0 until target.height) {
                    System.arraycopy(
                        target.pixels,
                        y * target.bufferWidth,
                        snapshotPixels,
                        y * target.width,
                        target.width,
                    )
                }
                _frames.value = snapshot
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Throwable) {
                Logger.e(TAG, "Render loop error: ${e.message}")
            }
        }
    }

    private fun ensureSurface(): Boolean {
        val w = requestedWidth.takeIf { it > 0 } ?: FALLBACK_WIDTH
        val h = requestedHeight.takeIf { it > 0 } ?: FALLBACK_HEIGHT

        val existing = surface
        if (existing != null && existing.width == w && existing.height == h) return false

        val minStride = w.toLong() * bytesPerPixel
        val strideBytes = ((minStride + alignment - 1) / alignment) * alignment
        val bufferWidth = (strideBytes / bytesPerPixel).toInt()
        val needed = strideBytes * h

        val backing = Memory(needed + alignment)
        val offset = (alignment - (Pointer.nativeValue(backing) % alignment)) % alignment
        val pixelBuffer = backing.share(offset, needed)

        val pixels = IntArray(bufferWidth * h)

        val sizeMem =
            Memory(8).apply {
                setInt(0, w)
                setInt(4, h)
            }
        val strideMem = Memory(8).apply { setLong(0, strideBytes) }

        val renderParams =
            buildRenderParams(
                listOf(
                    MpvRenderParamType.SW_SIZE to sizeMem,
                    MpvRenderParamType.SW_FORMAT to formatMem,
                    MpvRenderParamType.SW_STRIDE to strideMem,
                    MpvRenderParamType.SW_POINTER to pixelBuffer,
                ),
            )

        val next =
            Surface(
                width = w,
                height = h,
                bufferWidth = bufferWidth,
                pixelBuffer = pixelBuffer,
                backing = backing,
                pixels = pixels,
                sizeMem = sizeMem,
                strideMem = strideMem,
                renderParams = renderParams,
            )
        synchronized(surfaceLock) { surface = next }
        Logger.d(TAG, "Render surface allocated: ${w}x$h (stride=$strideBytes)")
        return true
    }
}
