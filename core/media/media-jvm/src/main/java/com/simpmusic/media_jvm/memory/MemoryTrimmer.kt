package com.simpmusic.media_jvm.memory

import com.maxrave.logger.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MemoryTrimmer"

object MemoryTrimmer {
    private const val MIN_INTERVAL_MS = 60_000L

    private const val HEAP_OPTIMIZE_RESOURCES = 3

    private const val HEAP_OPTIMIZE_RESOURCES_CURRENT_VERSION = 1

    private val lastTrimAt = AtomicLong(0)

    private val unsupported = AtomicBoolean(false)

    private interface GlibC : Library {
        fun malloc_trim(pad: Long): Int
    }

    private interface Kernel32 : Library {
        fun HeapSetInformation(
            heapHandle: Pointer?,
            informationClass: Int,
            information: Structure?,
            informationLength: Long,
        ): Boolean
    }

    @Structure.FieldOrder("Version", "Flags")
    class HeapOptimizeResourcesInformation : Structure() {
        @JvmField
        var Version: Int = HEAP_OPTIMIZE_RESOURCES_CURRENT_VERSION

        @JvmField
        var Flags: Int = 0
    }

    private val glibc: GlibC? by lazy {
        runCatching { Native.load("c", GlibC::class.java) }
            .onFailure { Logger.d(TAG, "glibc malloc_trim unavailable: ${it.message}") }
            .getOrNull()
    }

    private val kernel32: Kernel32? by lazy {
        runCatching { Native.load("kernel32", Kernel32::class.java) }
            .onFailure { Logger.d(TAG, "kernel32 HeapSetInformation unavailable: ${it.message}") }
            .getOrNull()
    }

    fun trim(reason: String) {
        if (unsupported.get()) return

        val now = System.currentTimeMillis()
        val previous = lastTrimAt.get()
        if (previous != 0L && now - previous < MIN_INTERVAL_MS) return
        if (!lastTrimAt.compareAndSet(previous, now)) return

        val started = System.nanoTime()
        val released =
            try {
                trimNative()
            } catch (e: Throwable) {
                Logger.d(TAG, "Trim unavailable on this platform: ${e.message}")
                unsupported.set(true)
                return
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        Logger.d(TAG, "Trim ($reason): $released, took %.1f ms".format(elapsedMs))
    }

    fun resetThrottle() {
        lastTrimAt.set(0)
    }

    private fun trimNative(): String {
        return when {
            com.sun.jna.Platform.isWindows() -> {
                val lib = kernel32 ?: return "kernel32 unavailable"
                val info = HeapOptimizeResourcesInformation()
                info.write()
                val ok =
                    lib.HeapSetInformation(
                        null,
                        HEAP_OPTIMIZE_RESOURCES,
                        info,
                        info.size().toLong(),
                    )
                if (!ok) unsupported.set(true)
                if (ok) "heap caches decommitted" else "not supported (needs Windows 8.1+)"
            }

            com.sun.jna.Platform.isMac() -> {
                // Never trim on macOS — the null-zone call reaches Metal's and QuartzCore's zones too and can crash a CATransaction commit.
                unsupported.set(true)
                "disabled on macOS"
            }

            else -> {
                val lib = glibc ?: return "glibc unavailable"
                if (lib.malloc_trim(0L) == 1) "pages returned" else "nothing to release"
            }
        }
    }
}
