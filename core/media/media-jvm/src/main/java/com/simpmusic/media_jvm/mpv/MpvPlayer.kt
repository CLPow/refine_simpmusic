package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.Locale

private const val TAG = "MpvPlayer"

open class MpvPlayerEventAdapter {
    open fun finished(player: MpvPlayer) {}

    open fun error(player: MpvPlayer) {}

    open fun playing(player: MpvPlayer) {}

    open fun paused(player: MpvPlayer) {}

    open fun stopped(player: MpvPlayer) {}

    open fun timeChanged(
        player: MpvPlayer,
        newTimeMs: Long,
    ) {}

    open fun lengthChanged(
        player: MpvPlayer,
        newLengthMs: Long,
    ) {}

    open fun buffering(
        player: MpvPlayer,
        newCache: Float,
    ) {}

    open fun opening(player: MpvPlayer) {}
}

class MpvPlayer private constructor(
    private val ctx: Pointer,
    private val lib: MpvLibrary,
) {
    companion object {
        private const val OBSERVE_USERDATA = 1L

        // Not an error for options that only ever disable something: a libmpv compiled without the subsystem never had it to begin with.
        private const val MPV_ERROR_OPTION_NOT_FOUND = -5

        private val scratch = ThreadLocal.withInitial { Memory(8) }

        private const val PITCH_LABEL = "simpPitch"

        fun create(
            networkCacheSeconds: Int = 10,
        ): MpvPlayer? {
            val lib = MpvLibrary.INSTANCE ?: return null
            val ctx = lib.mpv_create()
            if (ctx == null) {
                Logger.e(TAG, "mpv_create() returned null")
                return null
            }

            fun option(
                name: String,
                value: String,
            ) {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) {
                    Logger.w(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                }
            }

            fun optionalOption(
                name: String,
                value: String,
            ) {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc == MPV_ERROR_OPTION_NOT_FOUND) {
                    Logger.d(TAG, "Option '$name' absent in this libmpv build — already off, nothing to do")
                } else if (rc < 0) {
                    Logger.w(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                }
            }

            option("terminal", "no")
            optionalOption("ytdl", "no")
            option("idle", "yes")
            option("audio-client-name", "Refine Music")

            if (com.sun.jna.Platform.isMac()) {
                option("ao", "avfoundation,")
            }

            option("cache", "yes")
            option("cache-secs", networkCacheSeconds.toString())

            option("demuxer-max-bytes", (32 * 1024 * 1024).toString())
            option("demuxer-max-back-bytes", (8 * 1024 * 1024).toString())

            option(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30",
            )

            fun requiredOption(
                name: String,
                value: String,
            ): Boolean {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) {
                    Logger.e(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                    return false
                }
                return true
            }

            option("vid", "no")
            val voPinned = requiredOption("vo", "null")
            if (!voPinned) {
                // Never hand an unpinned VO to mpv_initialize: auto-probing can pick a terminal-graphics driver that aborts the process.
                Logger.e(TAG, "Refusing to initialize mpv without a pinned video output")
                lib.mpv_terminate_destroy(ctx)
                return null
            }

            val rc = lib.mpv_initialize(ctx)
            if (rc < 0) {
                Logger.e(TAG, "mpv_initialize failed: ${lib.mpv_error_string(rc)}")
                lib.mpv_terminate_destroy(ctx)
                return null
            }

            return MpvPlayer(ctx, lib).also {
                it.start()
            }
        }
    }

    @Volatile
    var isReleased = false
        private set

    @Volatile
    private var eventListener: MpvPlayerEventAdapter? = null

    @Volatile
    private var pumpRunning = true

    private var pumpThread: Thread? = null

    @Volatile
    private var pausedFlag = true

    @Volatile
    private var playbackRestarted = false

    @Volatile
    private var lastEmittedPlaying: Boolean? = null

    private fun start() {
        // MPV_FORMAT_NONE means "notify me, don't carry a value"; we ask for real values instead.
        observe("time-pos", MpvFormat.DOUBLE)
        observe("duration", MpvFormat.DOUBLE)
        observe("pause", MpvFormat.FLAG)
        observe("cache-buffering-state", MpvFormat.INT64)

        pumpThread =
            Thread({ pumpLoop() }, "Mpv-Event-Pump").apply {
                isDaemon = true
                start()
            }
    }

    private fun observe(
        name: String,
        format: Int,
    ) {
        val rc = lib.mpv_observe_property(ctx, OBSERVE_USERDATA, name, format)
        if (rc < 0) {
            Logger.w(TAG, "mpv_observe_property($name) failed: ${lib.mpv_error_string(rc)}")
        }
    }

    fun setEventListener(listener: MpvPlayerEventAdapter?) {
        eventListener = listener
    }

    private fun pumpLoop() {
        while (pumpRunning) {
            val eventPtr =
                try {
                    lib.mpv_wait_event(ctx, 0.1)
                } catch (e: Throwable) {
                    Logger.e(TAG, "mpv_wait_event threw: ${e.message}")
                    return
                } ?: continue

            val event =
                try {
                    MpvEvent(eventPtr).apply { read() }
                } catch (e: Throwable) {
                    Logger.w(TAG, "Failed to read mpv_event: ${e.message}")
                    continue
                }

            if (event.event_id == MpvEventId.NONE) continue

            if (!pumpRunning) return

            try {
                dispatch(event)
            } catch (e: Throwable) {
                Logger.e(TAG, "Error dispatching mpv event ${event.event_id}: ${e.message}")
            }

            if (event.event_id == MpvEventId.SHUTDOWN) {
                pumpRunning = false
                return
            }
        }
    }

    private fun dispatch(event: MpvEvent) {
        val listener = eventListener
        when (event.event_id) {
            MpvEventId.START_FILE -> {
                playbackRestarted = false
                lastEmittedPlaying = null
                listener?.opening(this)
            }

            MpvEventId.END_FILE -> {
                playbackRestarted = false
                lastEmittedPlaying = null
                val dataPtr = event.data
                val reason =
                    if (dataPtr == null) {
                        MpvEndFileReason.QUIT
                    } else {
                        MpvEventEndFile(dataPtr).apply { read() }.reason
                    }
                when (reason) {
                    MpvEndFileReason.EOF -> listener?.finished(this)
                    MpvEndFileReason.ERROR -> listener?.error(this)
                    MpvEndFileReason.STOP -> listener?.stopped(this)
                    else -> Unit
                }
            }

            MpvEventId.AUDIO_RECONFIG -> {
                applyVolume()
            }

            MpvEventId.PLAYBACK_RESTART -> {
                playbackRestarted = true
                maybeEmitPlayState()
            }

            MpvEventId.PROPERTY_CHANGE -> {
                val dataPtr = event.data ?: return
                val prop = MpvEventProperty(dataPtr).apply { read() }
                val name = prop.name?.getString(0) ?: return
                val valuePtr = prop.data ?: return
                when (name) {
                    "time-pos" ->
                        if (prop.format == MpvFormat.DOUBLE) {
                            listener?.timeChanged(this, secondsToMs(valuePtr.getDouble(0)))
                        }

                    "duration" ->
                        if (prop.format == MpvFormat.DOUBLE) {
                            listener?.lengthChanged(this, secondsToMs(valuePtr.getDouble(0)))
                        }

                    "pause" ->
                        if (prop.format == MpvFormat.FLAG) {
                            pausedFlag = valuePtr.getInt(0) != 0
                            maybeEmitPlayState()
                        }

                    "cache-buffering-state" ->
                        if (prop.format == MpvFormat.INT64) {
                            listener?.buffering(this, valuePtr.getLong(0).toFloat())
                        }
                }
            }
        }
    }

    private fun maybeEmitPlayState() {
        if (!playbackRestarted) return
        val playing = !pausedFlag
        if (lastEmittedPlaying == playing) return
        lastEmittedPlaying = playing
        if (playing) eventListener?.playing(this) else eventListener?.paused(this)
    }

    fun loadFile(
        url: String,
        startPaused: Boolean,
    ) {
        if (isReleased) return
        setPropertyString("pause", if (startPaused) "yes" else "no")
        command("loadfile", url, "replace")
    }

    fun play() {
        if (isReleased) return
        setPropertyString("pause", "no")
    }

    fun pause() {
        if (isReleased) return
        setPropertyString("pause", "yes")
    }

    fun stop() {
        if (isReleased) return
        command("stop")
    }

    private val volumeLock = Any()

    @Volatile
    private var masterPercent = 100

    @Volatile
    private var fadePercent = 100

    @Volatile
    private var sleepPercent = 100

    fun setMasterVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            masterPercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    fun setFadeVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            fadePercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    fun setSleepFadeVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            sleepPercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    fun setVolumeLevels(
        master: Int,
        sleep: Int,
    ) {
        if (isReleased) return
        synchronized(volumeLock) {
            masterPercent = master.coerceIn(0, 100)
            sleepPercent = sleep.coerceIn(0, 100)
            applyVolume()
        }
    }

    private fun applyVolume() =
        synchronized(volumeLock) {
            val master = masterPercent * sleepPercent / 100.0
            if (setPropertyDouble("ao-volume", master, logFailure = false) >= 0) {
                setPropertyDouble("volume", fadePercent.toDouble())
            } else {
                setPropertyDouble("volume", master * fadePercent / 100.0)
            }
        }

    fun setMute(mute: Boolean) {
        if (isReleased) return
        setPropertyString("mute", if (mute) "yes" else "no")
    }

    fun setRate(rate: Float) {
        if (isReleased) return
        setPropertyDouble("speed", rate.toDouble())
    }

    @Volatile
    private var filterEntries: List<String> = emptyList()

    private fun applyAudioFilters(): Boolean = setPropertyString("af", filterEntries.joinToString(","))

    fun installPitchShift(): Boolean {
        if (isReleased) return false
        filterEntries = listOf("@$PITCH_LABEL:rubberband=pitch-scale=1.0")
        if (applyAudioFilters()) return true
        clearAudioFilters()
        return false
    }

    fun setPitchScale(scale: Float) {
        if (isReleased) return
        command("af-command", PITCH_LABEL, "set-pitch", mpvNumber(scale))
    }

    fun clearAudioFilters() {
        if (isReleased) return
        filterEntries = emptyList()
        applyAudioFilters()
    }

    fun setLooping(loop: Boolean) {
        if (isReleased) return
        setPropertyString("loop-file", if (loop) "inf" else "no")
    }

    fun seekTo(timeMs: Long) {
        if (isReleased) return
        setPropertyDouble("time-pos", timeMs / 1000.0)
    }

    val time: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("time-pos"))

    val length: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("duration"))

    // `mpv_wait_event` must not run against a handle that `mpv_terminate_destroy` is tearing down, so the pump is stopped and joined first.
    fun release() {
        if (isReleased) return
        isReleased = true
        eventListener = null
        pumpRunning = false
        try {
            lib.mpv_wakeup(ctx)
        } catch (e: Throwable) {
            Logger.w(TAG, "mpv_wakeup failed: ${e.message}")
        }

        val thread = pumpThread
        pumpThread = null

        Thread({
            try {
                thread?.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                lib.mpv_terminate_destroy(ctx)
            } catch (e: Throwable) {
                Logger.w(TAG, "Error destroying mpv handle: ${e.message}")
            }
        }, "Mpv-Release").apply { isDaemon = true }.start()
    }

    private fun command(vararg args: String): Boolean {
        val argv = arrayOfNulls<String>(args.size + 1)
        args.forEachIndexed { i, a -> argv[i] = a }
        val rc =
            try {
                lib.mpv_command(ctx, argv)
            } catch (e: Throwable) {
                Logger.e(TAG, "mpv_command(${args.firstOrNull()}) threw: ${e.message}")
                return false
            }
        if (rc < 0) {
            Logger.w(TAG, "mpv_command(${args.joinToString(" ")}) failed: ${lib.mpv_error_string(rc)}")
            return false
        }
        return true
    }

    private fun setPropertyString(
        name: String,
        value: String,
    ): Boolean {
        try {
            val rc = lib.mpv_set_property_string(ctx, name, value)
            if (rc < 0) {
                Logger.w(TAG, "set $name=$value failed: ${lib.mpv_error_string(rc)}")
                return false
            }
            return true
        } catch (e: Throwable) {
            Logger.e(TAG, "set $name threw: ${e.message}")
            return false
        }
    }

    private fun setPropertyDouble(
        name: String,
        value: Double,
        logFailure: Boolean = true,
    ): Int =
        try {
            val mem = scratch.get()
            mem.setDouble(0, value)
            val rc = lib.mpv_set_property(ctx, name, MpvFormat.DOUBLE, mem)
            if (rc < 0 && logFailure) {
                Logger.w(TAG, "set $name=$value failed: ${lib.mpv_error_string(rc)}")
            }
            rc
        } catch (e: Throwable) {
            Logger.e(TAG, "set $name threw: ${e.message}")
            -1
        }

    private fun getPropertyString(name: String): String? =
        try {
            lib.mpv_get_property_string(ctx, name)?.let { ptr ->
                try {
                    ptr.getString(0)
                } finally {
                    lib.mpv_free(ptr)
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "get $name threw: ${e.message}")
            null
        }

    private fun getPropertyDouble(name: String): Double =
        try {
            val mem = scratch.get()
            if (lib.mpv_get_property(ctx, name, MpvFormat.DOUBLE, mem) < 0) 0.0 else mem.getDouble(0)
        } catch (e: Throwable) {
            Logger.e(TAG, "get $name threw: ${e.message}")
            0.0
        }
}

private fun mpvNumber(value: Float): String = String.format(Locale.ROOT, "%.4f", value)

private fun secondsToMs(seconds: Double): Long =
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) 0L else (seconds * 1000.0).toLong()
