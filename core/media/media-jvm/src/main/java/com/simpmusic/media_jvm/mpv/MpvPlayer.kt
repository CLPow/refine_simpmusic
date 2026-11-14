package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.Locale

private const val TAG = "MpvPlayer"

/**
 * Event callbacks for a single [MpvPlayer], shaped after vlcj's `MediaPlayerEventAdapter` so the
 * adapter's event-handling logic is a straight port.
 *
 * These are invoked from the player's own event-pump thread. Unlike VLC — whose callbacks come
 * from a native thread that deadlocks if you call `stop()`/`release()` on it — mpv's event
 * delivery is pull-based, so re-entering the API from here is safe. Implementations still
 * dispatch onto the service coroutine scope to keep the state machine serialized.
 */
open class MpvPlayerEventAdapter {
    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_EOF`. */
    open fun finished(player: MpvPlayer) {}

    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_ERROR`. */
    open fun error(player: MpvPlayer) {}

    /** `MPV_EVENT_PLAYBACK_RESTART` combined with observed `pause` == false. */
    open fun playing(player: MpvPlayer) {}

    /** Observed `pause` == true. */
    open fun paused(player: MpvPlayer) {}

    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_STOP`. */
    open fun stopped(player: MpvPlayer) {}

    /** Observed `time-pos`, converted to milliseconds. */
    open fun timeChanged(
        player: MpvPlayer,
        newTimeMs: Long,
    ) {}

    /** Observed `duration`, converted to milliseconds. */
    open fun lengthChanged(
        player: MpvPlayer,
        newLengthMs: Long,
    ) {}

    /** Observed `cache-buffering-state` (0-100). Same semantics as vlcj's `buffering(pct)`. */
    open fun buffering(
        player: MpvPlayer,
        newCache: Float,
    ) {}

    /** `MPV_EVENT_START_FILE`. */
    open fun opening(player: MpvPlayer) {}
}

/**
 * Thin wrapper around one libmpv handle — the mpv counterpart of `VlcPlayer`.
 *
 * One handle plays one media item at a time, exactly like a vlcj `MediaPlayer`, which lets the
 * adapter keep VLC's model of "a player instance per queue entry" (current / secondary /
 * precached) unchanged.
 *
 * ## Unit conversions are contained here
 * The public surface of this class speaks the SAME units as `VlcPlayer` so that the ported
 * business logic needs no edits:
 *  - [time] / [length] / [seekTo] are MILLISECONDS (mpv's `time-pos` / `duration` are seconds).
 *  - [setVolume] takes 0..100 (mpv's `volume` is natively 0..100, so this is 1:1; VLC accepted
 *    0..200 but the adapter only ever drove it to 100).
 */
class MpvPlayer private constructor(
    private val ctx: Pointer,
    private val lib: MpvLibrary,
) {
    companion object {
        /** Userdata tag for every `mpv_observe_property` registration; we dispatch by name. */
        private const val OBSERVE_USERDATA = 1L

        /**
         * `MPV_ERROR_OPTION_NOT_FOUND` from client.h — the option name is unknown to this build.
         *
         * Not an error for options that only ever disable something: a libmpv compiled without the
         * subsystem never had it to begin with.
         */
        private const val MPV_ERROR_OPTION_NOT_FOUND = -5

        /** Scratch buffer for get/set_property with a native format. One per calling thread. */
        private val scratch = ThreadLocal.withInitial { Memory(8) }

        /** `@label:` of the rubberband entry that carries the pitch shift. */
        private const val PITCH_LABEL = "simpPitch"

        /**
         * Create and initialize an audio-only libmpv handle (no video decoding, null video output).
         *
         * @param networkCacheSeconds mpv's `cache-secs`. VLC's `--network-caching` was expressed
         *   in milliseconds (10000 / 15000); mpv's equivalent is in seconds.
         * @return null if libmpv is unavailable or the handle could not be initialized.
         */
        fun create(
            networkCacheSeconds: Int = 10,
        ): MpvPlayer? {
            val lib = MpvLibrary.INSTANCE ?: return null
            val ctx = lib.mpv_create()
            if (ctx == null) {
                // Historically this meant a non-C LC_NUMERIC; MpvLibrary now forces that at load
                // time, so a null here is a genuine failure worth logging loudly.
                Logger.e(TAG, "mpv_create() returned null")
                return null
            }

            // Options must be set BEFORE mpv_initialize().
            fun option(
                name: String,
                value: String,
            ) {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) {
                    Logger.w(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                }
            }

            // Same as option(), but treats "no such option" as success.
            //
            // For options whose whole purpose is to switch a subsystem OFF, a libmpv built without
            // that subsystem is already in the desired state — warning about it would report the
            // goal being met as a problem. Any other error is still real and still logged.
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

            // VLC "--quiet" / "--no-video-title-show" / "--no-metadata-network-access" have no
            // direct mpv analogue; disabling the terminal covers all of their observable effect.
            //
            // MUST stay "no". With terminal=yes, mpv wires up terminal output — and this build of
            // libmpv ships the `sixel` feature (terminal image output), which pulled libsixel into
            // play and aborted the whole JVM with
            //   ../src/allocator.c:139: sixel_allocator_malloc: Assertion `allocator' failed.
            // Also covers what VLC's "--quiet" / "--no-video-title-show" did.
            option("terminal", "no")
            // We resolve stream URLs ourselves via StreamRepository, so mpv must never invoke its
            // ytdl_hook Lua script (seen firing as "Running hook: ytdl_hook/on_load"). Leaving it on
            // means mpv shells out to youtube-dl/yt-dlp behind our back on every loadfile.
            //
            // optional(): our own Linux slice is built with -Dlua=disabled, so ytdl_hook does not
            // exist there and the option is genuinely absent — an absence that is the desired state,
            // not a failure worth warning about on every handle. Platforms using a stock libmpv
            // (Windows, macOS) do have it, and there it still gets turned off.
            optionalOption("ytdl", "no")
            // Keep the core alive across end-of-file so one handle can be reloaded, and so EOF
            // surfaces as MPV_EVENT_END_FILE instead of MPV_EVENT_SHUTDOWN.
            option("idle", "yes")
            option("audio-client-name", "Refine Music")

            // macOS only: keep mpv off ao_coreaudio, which leaks a process-wide CoreAudio
            // listener pointing at a freed `struct ao` and takes the whole JVM down the next
            // time the user plugs in headphones.
            //
            // ao_coreaudio.c init() registers a listener on the SYSTEM audio object, passing
            // the ao itself as clientData:
            //   AudioObjectAddPropertyListener(kAudioObjectSystemObject, &addr, hotplug_cb, (void *)ao)
            // but its error label is bare — `coreaudio_error: return CONTROL_ERROR;` — so an
            // init that fails any later step (ca_init_chmap, init_audiounit) leaves that
            // listener registered. ao.c then does `goto fail` -> ao_uninit(), and buffer.c's
            // ao_uninit() only calls driver->uninit() when `driver_initialized` is set — a flag
            // ao.c sets only AFTER a successful init. So unregister_hotplug_cb() never runs,
            // while talloc_free(ao) does. The orphaned listener outlives the handle for the rest
            // of the process, and the next device change calls hotplug_cb -> MP_VERBOSE(ao, ...)
            // -> mp_msg(ao->log) on freed memory: EXC_BAD_ACCESS on the HALC_ProxyNotification
            // queue. Still present in mpv master as of 0.41.0.
            //
            // One handle per media item, plus two live handles during a crossfade, means a
            // single failed audio init anywhere in the session arms this. Avoiding the driver is
            // the only fix that does not require patching and rebuilding libmpv ourselves.
            //
            // ao_avfoundation registers no property listeners at all, so the bug cannot occur
            // there. Accepted trade-offs: delayed mute (mpv#15014) and audio desync when
            // playback speed changes (mpv#14483). The trailing comma keeps mpv's normal
            // auto-probe as a fallback so a failure here means degraded audio, not silence.
            if (com.sun.jna.Platform.isMac()) {
                option("ao", "avfoundation,")
            }

            // VLC "--network-caching=10000" / ":network-caching=15000".
            option("cache", "yes")
            option("cache-secs", networkCacheSeconds.toString())

            // cache-secs alone is only a target; the hard ceiling is demuxer-max-bytes, whose
            // default is 150 MB forward plus a back-buffer. Two handles exist at once during a
            // crossfade, so the default lets the demuxer alone account for several hundred MB of
            // resident memory. 32 MB covers cache-secs of audio comfortably — a 320 kbps stream
            // is 2.4 MB per minute — and mpv simply refills more often if it ever runs short.
            option("demuxer-max-bytes", (32 * 1024 * 1024).toString())
            option("demuxer-max-back-bytes", (8 * 1024 * 1024).toString())

            // VLC ":http-reconnect".
            option(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30",
            )

            // ALWAYS pin the video output explicitly.
            //
            // mpv picks the VO during mpv_initialize(). Leaving it unset lets mpv auto-probe, and
            // in a headless JVM process it can land on a terminal-graphics driver — which is
            // exactly what happened: it selected `sixel`, and libsixel aborted the whole JVM with
            //   ../src/allocator.c:139: sixel_allocator_malloc: Assertion `allocator' failed.
            // (SIGABRT, exit 134) during ordinary music playback. Setting it afterwards does NOT
            // retroactively change a VO that mpv_initialize already chose.

            // Unlike option(), a failure here is fatal rather than a warning — see below.
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

            // VLC ":no-video".
            option("vid", "no")
            val voPinned = requiredOption("vo", "null")
            if (!voPinned) {
                // Never hand an unpinned VO to mpv_initialize: auto-probing is what selected the
                // terminal-graphics driver that aborted the process. Failing to create a player is
                // recoverable; a SIGABRT is not.
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

    // ---- play/pause edge detection (see maybeEmitPlayState) ----

    @Volatile
    private var pausedFlag = true

    @Volatile
    private var playbackRestarted = false

    @Volatile
    private var lastEmittedPlaying: Boolean? = null

    // ================= setup =================

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

    // ================= event pump =================

    /**
     * Owns this handle's `mpv_wait_event` loop.
     *
     * mpv's threading rules forbid calling any API function from `mpv_set_wakeup_callback`, so no
     * business logic runs there — this dedicated pull-based thread replaces VLC's push callbacks
     * entirely. The short timeout lets [release] shut the loop down promptly.
     */
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

            // Release has begun: stop dispatching. Handlers like AUDIO_RECONFIG -> applyVolume
            // call back INTO libmpv from this thread, and Mpv-Release only destroys the core
            // after joining this loop — so no new native call may start once the flag drops.
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
                    // QUIT / REDIRECT carry no meaning for this backend.
                    else -> Unit
                }
            }

            MpvEventId.AUDIO_RECONFIG -> {
                // The audio output was just (re)created, and `ao-volume` only becomes settable once
                // it exists. An earlier setMasterVolume/setFadeVolume therefore may have fallen back
                // to the software volume — which would then multiply against the mixer level the
                // previous track left behind. Re-apply now that the real target is reachable.
                applyVolume()
            }

            MpvEventId.PLAYBACK_RESTART -> {
                // Also fires after every seek, so it can't stand in for vlcj's playing() on its
                // own — it only marks "output has (re)started"; the pause flag decides the rest.
                playbackRestarted = true
                maybeEmitPlayState()
            }

            MpvEventId.PROPERTY_CHANGE -> {
                val dataPtr = event.data ?: return
                val prop = MpvEventProperty(dataPtr).apply { read() }
                val name = prop.name?.getString(0) ?: return
                // format == MPV_FORMAT_NONE (and data == null) means the value was unavailable.
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

    /**
     * Emit vlcj-equivalent `playing()` / `paused()` edges.
     *
     * mpv has no single "now playing" event: `MPV_EVENT_PLAYBACK_RESTART` fires on seeks too, and
     * the `pause` property flips before output actually resumes. Requiring both, and deduplicating
     * on the last emitted value, reproduces VLC's one-shot semantics.
     */
    private fun maybeEmitPlayState() {
        if (!playbackRestarted) return
        val playing = !pausedFlag
        if (lastEmittedPlaying == playing) return
        lastEmittedPlaying = playing
        if (playing) eventListener?.playing(this) else eventListener?.paused(this)
    }

    // ================= transport =================

    /**
     * Load [url] and begin playback, or load it held at the first frame when [startPaused].
     *
     * mpv collapses vlcj's `media().play()`, `media().startPaused()` and `media().prepare()` into
     * one `loadfile` — the `pause` property decides which of the three it behaves as.
     *
     * Deliberately uses the 3-argument form `loadfile <url> replace`, which is unambiguous on
     * every mpv version. The trailing parameters are NOT stable across releases:
     *  - 0.37.0 (our target) documents `loadfile <url> [<flags> [<options>]]` — the third
     *    argument is the per-file OPTION STRING.
     *  - 0.38.0 inserted `<index>` ahead of it: `loadfile <url> [<flags> [<index> [<options>]]]`.
     *
     * So anything past `<flags>` would mean different things on different builds. Per-file options
     * are unnecessary here regardless: one handle serves exactly one media item, so they are set
     * on the handle itself in [create] — the same role VLC's `:option` media arguments played.
     */
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

    // Volume is split in three so each owner can move its own level without disturbing the others:
    // `masterPercent` is the pipeline volume (the slider), `fadePercent` is this handle's own
    // crossfade ramp, and `sleepPercent` is the sleep timer's fade-out. [applyVolume] decides how
    // each reaches mpv.
    //
    // Three threads write these: the crossfade ramp on the player thread, the volume slider from
    // the UI thread, and the event pump when the audio output is reconfigured. Reading all three
    // and issuing the resulting property writes is therefore done under [volumeLock] — volatile
    // alone would stop torn reads but not stop two threads interleaving their pairs of mpv calls
    // and leaving the device on a combination neither of them intended.
    private val volumeLock = Any()

    @Volatile
    private var masterPercent = 100

    @Volatile
    private var fadePercent = 100

    @Volatile
    private var sleepPercent = 100

    /**
     * The pipeline volume — what the volume slider controls. Must NOT be touched while a crossfade
     * is running; ramp with [setFadeVolume] instead.
     *
     * @param volume 0..100, matching `VlcPlayer.setVolume`. mpv's `volume` is natively 0..100.
     */
    fun setMasterVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            masterPercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    /**
     * This handle's crossfade ramp, 0..100 where 100 means "no attenuation". Per-player by design:
     * every handle registers under the same `audio-client-name`, so they share one `ao-volume` and
     * only the software volume can fade one track against another.
     */
    fun setFadeVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            fadePercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    /**
     * The sleep timer's fade-out, 0..100 where 100 means "no attenuation".
     *
     * Rides on the master rather than on [setFadeVolume] for two reasons: the crossfade ramp is
     * reset to 100 every time a handle becomes current, which would wipe a fade still in progress,
     * and the master reaches the device mixer, so it takes effect immediately instead of trailing
     * the audio-output buffer by a second or two — a ramp measured in seconds cannot afford that lag.
     */
    fun setSleepFadeVolume(volume: Int) {
        if (isReleased) return
        synchronized(volumeLock) {
            sleepPercent = volume.coerceIn(0, 100)
            applyVolume()
        }
    }

    /**
     * Set the master and the sleep fade together, for a handle that is being brought up to the
     * levels already in force elsewhere.
     *
     * Doing it in two calls would publish the new master against the *old* sleep fade first, and
     * since `ao-volume` is shared across handles that intermediate value is audible on whatever is
     * playing — a full-volume blip in the middle of a fade.
     */
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

    /**
     * `volume` is mpv's *software* volume: applied inside the filter chain, so audio already queued
     * in the audio-output buffer keeps playing at the previous level — audible as a couple of
     * seconds of lag after the slider is released. `ao-volume` drives the audio device's own mixer
     * and takes effect immediately, so the master and the sleep fade ride on that and the software
     * volume is left to carry the crossfade ramp alone (mpv applies the two independently — they
     * multiply). When the audio output exposes no mixer control, all three collapse into the
     * software volume so the user's level is still honoured.
     */
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

    // ================= Pitch shift =================

    /** Audio filter entries currently installed. */
    @Volatile
    private var filterEntries: List<String> = emptyList()

    private fun applyAudioFilters(): Boolean = setPropertyString("af", filterEntries.joinToString(","))

    /**
     * Arm `rubberband` at its neutral `pitch-scale=1.0` so [setPitchScale] can drive it.
     *
     * The chain is written as an `af` property string, v0.37.0 options.rst:1941, with an `@label:`
     * prefix (vf.rst:15) so `af-command` can address the entry later. librubberband is an
     * OPTIONAL build-time dependency of libmpv, so mpv may reject it.
     *
     * @return whether mpv accepted the filter.
     */
    fun installPitchShift(): Boolean {
        if (isReleased) return false
        // v0.37.0 af.rst:196 ``pitch-scale=<amount>`` — *"Sets the pitch scaling factor.
        // Frequencies are multiplied by this value. (default: 1.0)"*.
        filterEntries = listOf("@$PITCH_LABEL:rubberband=pitch-scale=1.0")
        if (applyAudioFilters()) return true
        clearAudioFilters()
        return false
    }

    /**
     * Shift pitch by [scale] (1.0 = unchanged), independently of playback speed.
     *
     * This needs `rubberband` in the chain, which is why [installPitchShift] must have succeeded. mpv's `speed` property alone can never do this: with
     * `--audio-pitch-correction` (default yes, v0.37.0 options.rst:1870) a speed change is
     * pitch-CORRECTED, and with it disabled pitch would be welded to tempo — Android needs the two
     * to move independently, the way Media3's `PlaybackParameters(speed, pitch)` does.
     *
     * With rubberband present it also absorbs the tempo change: mpv hands `SET_SPEED` to the last
     * user filter that accepts it and then stops (f_output_chain.c:464 *"make sure only 1 filter
     * changes speed"*), so rubberband — not the built-in `scaletempo2` — does the time stretch,
     * and [setRate] keeps behaving exactly as before.
     *
     * v0.37.0 af.rst:223 ``set-pitch`` — *"Set the <pitch-scale> argument dynamically … Note that
     * speed is controlled using the standard ``speed`` property, not ``af-command``."*
     */
    fun setPitchScale(scale: Float) {
        if (isReleased) return
        command("af-command", PITCH_LABEL, "set-pitch", mpvNumber(scale))
    }

    /**
     * Drop every audio filter, returning the handle to untouched output.
     *
     * This is also what resets pitch: removing `rubberband` removes the pitch shift with it, so
     * callers only have to restore [setRate] afterwards.
     */
    fun clearAudioFilters() {
        if (isReleased) return
        filterEntries = emptyList()
        applyAudioFilters()
    }

    /**
     * Repeat the current file indefinitely.
     *
     * mpv's `loop-file` property (v0.37.0 options.rst: `--loop-file=<N|inf|no>`, *"inf means
     * forever"*). Preferred over re-issuing `loadfile` when end-of-file arrives — the VLC-era
     * approach — because looping natively replays from the demuxer cache instead of refetching
     * the whole URL on every repeat.
     */
    fun setLooping(loop: Boolean) {
        if (isReleased) return
        setPropertyString("loop-file", if (loop) "inf" else "no")
    }

    /** @param timeMs milliseconds, matching `VlcPlayer.seekTo`. mpv's `time-pos` is seconds. */
    fun seekTo(timeMs: Long) {
        if (isReleased) return
        setPropertyDouble("time-pos", timeMs / 1000.0)
    }

    /** Current position in milliseconds, or 0. Mirrors `VlcPlayer.time`. */
    val time: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("time-pos"))

    /** Duration in milliseconds, or 0. Mirrors `VlcPlayer.length`. */
    val length: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("duration"))

    // ================= teardown =================

    /**
     * Stop the pump thread, then destroy the handle.
     *
     * `mpv_wait_event` must not run against a handle that `mpv_terminate_destroy` is tearing down,
     * so the pump is stopped and joined first. The sequence runs off-thread because
     * `mpv_terminate_destroy` blocks until the core is gone and would otherwise stall the single
     * service thread that drives playback.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        eventListener = null
        pumpRunning = false
        // Kick a concurrent mpv_wait_event awake so the pump sees pumpRunning=false now
        // instead of after its 100ms poll timeout.
        try {
            lib.mpv_wakeup(ctx)
        } catch (e: Throwable) {
            Logger.w(TAG, "mpv_wakeup failed: ${e.message}")
        }

        val thread = pumpThread
        pumpThread = null

        Thread({
            // Join WITHOUT a timeout. The pump may still be inside a JNA call against this
            // handle (e.g. AUDIO_RECONFIG -> applyVolume -> mpv_set_property during the EOF
            // audio teardown), and destroying the core underneath that call is a
            // use-after-free — the SIGSEGV in mpv_set_property on Mpv-Event-Pump. While the
            // core is still alive every such call returns normally and the loop then exits
            // on pumpRunning=false, so this join is bounded in practice; the old join(1000)
            // gave up exactly when the core was busy tearing down the audio output and
            // destroyed it mid-call.
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

    // ================= native helpers =================

    /** @return true if mpv accepted the command. */
    private fun command(vararg args: String): Boolean {
        // mpv_command takes a NULL-terminated char** — JNA does not append the terminator.
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

    /** @return true if mpv accepted the value. */
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

    /** @return mpv's return code; negative means the property could not be set. */
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

    /** @return the property as text, or null when unavailable. The buffer is mpv's and is freed here. */
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

    /** @return the property value, or 0.0 when unavailable (e.g. nothing loaded yet). */
    private fun getPropertyDouble(name: String): Double =
        try {
            val mem = scratch.get()
            if (lib.mpv_get_property(ctx, name, MpvFormat.DOUBLE, mem) < 0) 0.0 else mem.getDouble(0)
        } catch (e: Throwable) {
            Logger.e(TAG, "get $name threw: ${e.message}")
            0.0
        }
}

/**
 * Render a number the way mpv's parsers expect it: `.` as the decimal separator, nothing else.
 *
 * [Locale.ROOT] is load-bearing rather than pedantic. The JVM default locale here is `vi_VN`, whose
 * `String.format` emits `0,9800` — and `af_rubberband.c`'s `set-pitch` handler runs
 * `strtod(arg, &endptr); if (*endptr) return false;`, so a comma would silently reject every pitch
 * update. (`MpvLibrary` already forces the NATIVE `LC_NUMERIC` to C for the same class of reason;
 * that does nothing for numbers formatted on the Java side.)
 */
private fun mpvNumber(value: Float): String = String.format(Locale.ROOT, "%.4f", value)

/** mpv reports times in seconds; the whole player stack above speaks milliseconds. */
private fun secondsToMs(seconds: Double): Long =
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) 0L else (seconds * 1000.0).toLong()
