package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

private const val TAG = "MpvLibrary"

/**
 * JNA binding for libmpv's C client API (`mpv/client.h`).
 *
 * ## Bound against libmpv 0.37.0 / client API 2.2
 * Every signature, enum value and struct layout in this file was read literally off
 * `/usr/include/mpv/client.h` as shipped by Ubuntu `libmpv-dev 0.37.0-1ubuntu4`
 * (`MPV_CLIENT_API_VERSION == MPV_MAKE_VERSION(2, 2)`, runtime `libmpv.so.2.2.0`), and every
 * imported symbol was confirmed present in that binary's dynamic symbol table.
 *
 * **If the bundled/target libmpv version ever changes, re-verify the struct layouts here.**
 * JNA reads these structs by raw offset — a field added, removed or reordered upstream is
 * silent memory corruption, not a clean link error. (The layouts happen to be byte-identical
 * between 0.37.0 and current master, but that is not a guarantee going forward.)
 *
 * ## Threading contract
 * From client.h: a single `mpv_handle` may be used from multiple threads, but [mpv_wait_event]
 * must only ever be called from ONE thread at a time. This project dedicates one pump thread
 * per handle (see [MpvPlayer]).
 */
@Suppress("FunctionNaming", "FunctionParameterNaming")
interface MpvLibrary : Library {
    // ---- lifecycle ----

    /** `mpv_handle *mpv_create(void);` */
    fun mpv_create(): Pointer?

    /** `int mpv_initialize(mpv_handle *ctx);` */
    fun mpv_initialize(ctx: Pointer): Int

    /** `void mpv_terminate_destroy(mpv_handle *ctx);` */
    fun mpv_terminate_destroy(ctx: Pointer)

    // ---- options / properties ----

    /** `int mpv_set_option_string(mpv_handle *ctx, const char *name, const char *data);` */
    fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    /** `int mpv_set_property(mpv_handle *ctx, const char *name, mpv_format format, void *data);` */
    fun mpv_set_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int

    /** `int mpv_set_property_string(mpv_handle *ctx, const char *name, const char *data);` */
    fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    /** `int mpv_get_property(mpv_handle *ctx, const char *name, mpv_format format, void *data);` */
    fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int

    /**
     * `char *mpv_get_property_string(mpv_handle *ctx, const char *name);`
     *
     * The returned buffer is owned by the caller and MUST be released with [mpv_free].
     * Returned as [Pointer] (not String) precisely so the free can happen.
     */
    fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer?

    /**
     * `int mpv_observe_property(mpv_handle *mpv, uint64_t reply_userdata,
     *                           const char *name, mpv_format format);`
     */
    fun mpv_observe_property(
        mpv: Pointer,
        reply_userdata: Long,
        name: String,
        format: Int,
    ): Int

    // ---- commands ----

    /**
     * `int mpv_command(mpv_handle *ctx, const char **args);`
     *
     * [args] must be NULL-terminated: the caller passes a trailing `null` element.
     * JNA does not append one.
     */
    fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int

    // ---- events ----

    /**
     * `mpv_event *mpv_wait_event(mpv_handle *ctx, double timeout);`
     *
     * Returned as a raw [Pointer] rather than a mapped Structure: mpv reuses the same
     * memory for every event of a handle, and we only want to read the fields we mapped.
     */
    fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer?

    /**
     * `void mpv_wakeup(mpv_handle *ctx);`
     *
     * Makes a concurrent (or the next) [mpv_wait_event] call return immediately with
     * MPV_EVENT_NONE. Used by release so the pump thread notices shutdown without
     * waiting out its poll timeout.
     */
    fun mpv_wakeup(ctx: Pointer)

    // ---- misc ----

    /** `void mpv_free(void *data);` */
    fun mpv_free(data: Pointer)

    /** `const char *mpv_error_string(int error);` */
    fun mpv_error_string(error: Int): String?

    /** `unsigned long mpv_client_api_version(void);` */
    fun mpv_client_api_version(): Long

    companion object {
        /** `MPV_MAKE_VERSION(2, 2)` — the client API this file is written against. */
        private const val EXPECTED_API_VERSION = (2L shl 16) or 2L

        /**
         * Candidate SONAMEs, in load order.
         *
         * Plain "mpv" is first and resolves `libmpv.so` / `libmpv.dylib` / `mpv.dll`. The rest are
         * fallbacks for the common case where only the runtime package is installed: on Linux the
         * bare `libmpv.so` symlink ships with `libmpv-dev`, while the runtime alone gives just
         * `libmpv.so.2`. Windows mpv ships `libmpv-2.dll`.
         */
        private val CANDIDATE_NAMES =
            listOf(
                "mpv",
                "libmpv.so.2",
                "libmpv.so.1",
                "libmpv.2.dylib",
                "libmpv-2",
                "mpv-2",
                "mpv-1",
            )

        /**
         * Lazily loaded singleton. Null when libmpv is not present on the system, so callers
         * can degrade instead of dying with an UnsatisfiedLinkError at class-init time.
         */
        val INSTANCE: MpvLibrary? by lazy { loadLibrary() }

        /** Minimal libc binding, used only to fix the locale libmpv refuses to run under. */
        private interface LibC : com.sun.jna.Library {
            fun setlocale(
                category: Int,
                locale: String?,
            ): String?
        }

        /**
         * libmpv REFUSES to start under a non-C LC_NUMERIC: `mpv_create()` prints
         * "Non-C locale detected. This is not supported." and returns NULL.
         *
         * This bites every JVM host: the JVM calls `setlocale(LC_ALL, "")` during startup, so the
         * process inherits the user's locale (here `vi_VN.UTF-8`). A plain C program never hits it
         * because C starts in the "C" locale unless it opts in — which is exactly why the same
         * mpv_create() call succeeded from C and returned NULL from Kotlin.
         *
         * Only LC_NUMERIC is reset, so number formatting elsewhere in the app is unaffected in the
         * ways users would notice (dates, collation, currency all keep the user's locale).
         *
         * The category constant is NOT portable: glibc numbers LC_NUMERIC as 1, while macOS and
         * the Windows CRT use 4.
         */
        private fun forceCNumericLocale() {
            val category =
                when {
                    com.sun.jna.Platform.isWindows() || com.sun.jna.Platform.isMac() -> 4
                    else -> 1
                }
            runCatching {
                val libc =
                    Native.load(
                        if (com.sun.jna.Platform.isWindows()) "msvcrt" else "c",
                        LibC::class.java,
                    )
                val current = libc.setlocale(category, null)
                if (current != null && current != "C" && current != "POSIX") {
                    libc.setlocale(category, "C")
                    Logger.d(TAG, "LC_NUMERIC was '$current', forced to 'C' so libmpv can start")
                }
            }.onFailure {
                Logger.e(TAG, "Could not force LC_NUMERIC=C; mpv_create() will likely fail: ${it.message}")
            }
        }

        /** True when [name] is a libmpv shared library on any of the three platforms. */
        private fun isMpvLibName(name: String): Boolean =
            name.startsWith("libmpv.so") ||
                name.startsWith("libmpv.") && name.endsWith(".dylib") ||
                name == "libmpv.dylib" ||
                name.startsWith("libmpv") && name.endsWith(".dll") ||
                name == "mpv-2.dll"

        /** File names that identify a directory as holding a usable libmpv. */
        private fun hasMpvLib(dir: java.io.File): Boolean =
            dir.listFiles()?.any { isMpvLibName(it.name) } == true

        /** Absolute paths of every libmpv file inside [dirs], most specific location first. */
        private fun bundledLibraryFiles(dirs: List<java.io.File>): List<String> =
            dirs.flatMap { dir ->
                dir.listFiles()?.filter { it.isFile && isMpvLibName(it.name) }?.map { it.absolutePath }
                    ?: emptyList()
            }

        /**
         * Directories that may hold a bundled libmpv, most specific first.
         *
         * Deliberately mirrors `DefaultVlcDiscoverer.findBundledVlcPath()`, because the two
         * backends are staged by the same pipelines and hit the same two-packagers problem:
         *
         *  1. `mpv.bundled.path` — set by `:desktopApp:run` (Gradle dev loop).
         *  2. `compose.application.resources.dir` — set by the Compose/jpackage packaging path
         *     only, and its per-OS subdirectory is searched too.
         *  3. A `mpv/` directory found by walking up from this class's own JAR. This is the
         *     one that matters for shipped builds: Conveyor stages natives via
         *     `mac.aarch64.inputs += { from = "mpv-natives/macos-arm64", to = "mpv" }` and does
         *     NOT set compose.application.resources.dir, so case 2 never fires there.
         *  4. `mpv-natives/<os>-<arch>` relative to the working directory, for a raw checkout.
         */
        private fun bundledLibraryDirs(): List<java.io.File> {
            val candidates = mutableListOf<java.io.File>()

            System.getProperty("mpv.bundled.path")?.let { candidates += java.io.File(it) }

            System.getProperty("compose.application.resources.dir")?.let { dir ->
                val root = java.io.File(dir)
                candidates += root
                root.listFiles()?.filter { it.isDirectory }?.let { candidates += it }
            }

            // Walk up from the JAR/classes location looking for a sibling `mpv/`. The depth
            // covers Conveyor's app layouts on all three OSes without hard-coding any of them.
            runCatching {
                val codeSource = MpvLibrary::class.java.protectionDomain?.codeSource?.location
                var dir = codeSource?.toURI()?.let { java.io.File(it) }?.parentFile
                repeat(6) {
                    val current = dir ?: return@repeat
                    candidates += current.resolve("mpv")
                    dir = current.parentFile
                }
            }

            val osName = System.getProperty("os.name", "").lowercase()
            val osArch = System.getProperty("os.arch", "").lowercase()
            val subDir =
                when {
                    osName.contains("win") -> if (osArch.contains("aarch64")) "windows-arm64" else "windows-x64"
                    osName.contains("mac") -> if (osArch.contains("aarch64")) "macos-arm64" else "macos-x64"
                    else -> "linux-x64"
                }
            candidates += java.io.File("mpv-natives/$subDir")

            return candidates.filter { it.isDirectory && hasMpvLib(it) }.distinctBy { it.absolutePath }
        }

        private fun loadLibrary(): MpvLibrary? {
            forceCNumericLocale()

            // Bundled natives take priority over any system install: they are the versions
            // MpvLibrary's struct layouts were verified against.
            val bundled = bundledLibraryDirs()
            if (bundled.isNotEmpty()) {
                val bundledPath = bundled.joinToString(java.io.File.pathSeparator) { it.absolutePath }

                // Register through JNA's runtime API, NOT by setting the `jna.library.path`
                // system property.
                //
                // JNA reads that property once, when NativeLibrary is first initialised, and
                // caches the parsed result — setting it afterwards is a no-op. And it IS already
                // initialised by this point: forceCNumericLocale() above does Native.load("c").
                //
                // The bug this caused was invisible on any dev machine: with libmpv installed
                // system-wide, dlopen() finds it through ld.so and everything works. On a clean
                // machine the bundled directory was never searched, and a shipped AppImage failed
                // with "libmpv native library not found ... Bundled natives were not located
                // either" despite carrying libmpv.so.2 inside it.
                //
                // addSearchPath() takes effect immediately and is per-library-name, so it is
                // registered for every candidate SONAME we might try below.
                CANDIDATE_NAMES.forEach { candidate ->
                    bundled.forEach { dir ->
                        com.sun.jna.NativeLibrary.addSearchPath(candidate, dir.absolutePath)
                    }
                }

                // Kept for the JVM's own System.loadLibrary and for any JNA instance that has not
                // been initialised yet; harmless, just not sufficient on its own.
                val existing = System.getProperty("jna.library.path")
                System.setProperty(
                    "jna.library.path",
                    if (existing.isNullOrBlank()) bundledPath else "$bundledPath${java.io.File.pathSeparator}$existing",
                )
                Logger.d(TAG, "Bundled libmpv search path: $bundledPath")
            } else {
                Logger.d(TAG, "No bundled libmpv found; falling back to a system-wide install")
            }

            // Absolute paths FIRST, plain SONAMEs second.
            //
            // JNA maps a bare name like "mpv" onto the platform convention — `libmpv.so` on Linux.
            // The bundles ship the versioned file (`libmpv.so.2`) and no unversioned symlink, so
            // every bare name misses and JNA falls through to dlopen(), which quietly succeeds
            // against a SYSTEM libmpv when one happens to be installed. That is exactly how a
            // shipped AppImage carrying its own libmpv still failed on a clean Ubuntu box while
            // working on every machine that had `apt install libmpv2`.
            //
            // Passing the absolute path sidesteps the naming convention entirely. The bundled
            // libraries declare `RUNPATH=$ORIGIN/lib`, so their ~97 dependencies resolve from the
            // bundle itself once the file is opened by path — nothing else needs configuring.
            val loadTargets = bundledLibraryFiles(bundled) + CANDIDATE_NAMES
            for (name in loadTargets) {
                try {
                    // Load PRIVATELY (RTLD_LOCAL), not with JNA's default RTLD_GLOBAL.
                    //
                    // libmpv drags in a large dependency closure — ffmpeg, libass, libplacebo,
                    // libsixel and more. With RTLD_GLOBAL every one of those symbols is published
                    // into the process-wide namespace, where Skiko/AWT/other natives can resolve
                    // against them by accident. That is what produced
                    //   ../src/allocator.c:139: sixel_allocator_malloc: Assertion `allocator' failed.
                    // — an abort inside libsixel although nothing in this codebase ever calls it.
                    //
                    // Evidence it is an interaction, not our call path: loading libmpv in a bare
                    // JVM and running mpv_create()/mpv_initialize() there is perfectly stable; the
                    // abort only appears once libmpv shares a process with Compose/Skiko, and it
                    // fires even on runs where MpvPlayer.create() completed all its steps.
                    //
                    // RTLD_NOW (2) without RTLD_GLOBAL (0x100) keeps those symbols private to this
                    // handle, which is what we want anyway — nothing outside links against libmpv.
                    //
                    // POSIX ONLY. JNA forwards this value verbatim to LoadLibraryEx on Windows,
                    // where 2 is not RTLD_NOW but LOAD_LIBRARY_AS_DATAFILE: the DLL gets mapped as
                    // plain data, imports are never resolved and — per MSDN — GetProcAddress
                    // refuses to return anything from it. The symptom is a load that "succeeds"
                    // and then fails on the very first lookup with a misleading message:
                    //   Error looking up function 'mpv_client_api_version':
                    //   The specified module could not be found.
                    // Windows has no RTLD_GLOBAL leakage to guard against anyway — DLL exports are
                    // never published process-wide — so the default flags are already correct.
                    val lib =
                        Native.load(
                            name,
                            MpvLibrary::class.java,
                            if (com.sun.jna.Platform.isWindows()) {
                                emptyMap<String, Any>()
                            } else {
                                mapOf<String, Any>(com.sun.jna.Library.OPTION_OPEN_FLAGS to 2)
                            },
                        )
                    val version = lib.mpv_client_api_version()
                    // Log the file that was ACTUALLY opened, not just the name we asked for.
                    // On a dev machine with libmpv installed system-wide both a working bundle
                    // and a broken one load fine — the only way to tell them apart is to see
                    // whether this points inside the app or at /usr/lib.
                    val resolved =
                        runCatching {
                            com.sun.jna.NativeLibrary.getInstance(name).file?.absolutePath
                        }.getOrNull() ?: "<unknown>"
                    Logger.d(
                        TAG,
                        "Loaded libmpv as '$name' (client API ${version shr 16}.${version and 0xFFFF}) from $resolved",
                    )
                    // Struct layouts here are hand-mapped for API 2.2. A different major means
                    // a potentially ABI-incompatible client.h — loud, because JNA would otherwise
                    // read mismatched fields silently.
                    if ((version shr 16) != (EXPECTED_API_VERSION shr 16)) {
                        Logger.e(
                            TAG,
                            "libmpv client API major ${version shr 16} != expected " +
                                "${EXPECTED_API_VERSION shr 16}. Struct layouts in MpvLibrary.kt " +
                                "were verified against 0.37.0 / API 2.2 and must be re-checked.",
                        )
                    }
                    return lib
                } catch (e: UnsatisfiedLinkError) {
                    Logger.d(TAG, "libmpv not loadable as '$name': ${e.message}")
                } catch (e: Exception) {
                    Logger.d(TAG, "libmpv not loadable as '$name': ${e.message}")
                }
            }
            // Shipped builds carry libmpv in mpv-natives/<os>-<arch>, staged by
            // `./gradlew :composeApp:mpvSetupAll` and placed by Conveyor. Reaching this point
            // means neither the bundle nor a system install was usable.
            Logger.e(
                TAG,
                "libmpv native library not found (tried: ${CANDIDATE_NAMES.joinToString(", ")}). " +
                    "Bundled natives were not located either — from a source checkout run " +
                    "'./gradlew :composeApp:mpvSetupAll', or install libmpv system-wide " +
                    "(Debian/Ubuntu: 'apt install libmpv2', Fedora: 'dnf install mpv-libs', " +
                    "macOS: 'brew install mpv', Windows: place libmpv-2.dll on the library path).",
            )
            return null
        }
    }
}

/** `enum mpv_format` — client.h. */
object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
    const val NODE_ARRAY = 7
    const val NODE_MAP = 8
    const val BYTE_ARRAY = 9
}

/** `enum mpv_event_id` — client.h. Only the ids this backend reacts to are listed. */
object MpvEventId {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val GET_PROPERTY_REPLY = 3
    const val SET_PROPERTY_REPLY = 4
    const val COMMAND_REPLY = 5
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val CLIENT_MESSAGE = 16
    const val VIDEO_RECONFIG = 17
    const val AUDIO_RECONFIG = 18
    const val SEEK = 20
    const val PLAYBACK_RESTART = 21
    const val PROPERTY_CHANGE = 22
    const val QUEUE_OVERFLOW = 24
    const val HOOK = 25
}

/** `enum mpv_end_file_reason` — client.h. */
object MpvEndFileReason {
    const val EOF = 0
    const val STOP = 2
    const val QUIT = 3
    const val ERROR = 4
    const val REDIRECT = 5
}

/**
 * `struct mpv_event` — client.h.
 *
 * LP64 layout: event_id@0, error@4, reply_userdata@8, data@16 (size 24).
 */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var event_id: Int = 0

    @JvmField var error: Int = 0

    @JvmField var reply_userdata: Long = 0

    @JvmField var data: Pointer? = null
}

/**
 * `struct mpv_event_property` — client.h.
 *
 * LP64 layout: name@0, format@8 (int, padded), data@16.
 */
@Structure.FieldOrder("name", "format", "data")
open class MpvEventProperty(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var name: Pointer? = null

    @JvmField var format: Int = 0

    @JvmField var data: Pointer? = null
}

/**
 * `struct mpv_event_end_file` — client.h.
 *
 * DELIBERATELY TRUNCATED. In 0.37.0 the C struct also carries `playlist_entry_id`,
 * `playlist_insert_id` and `playlist_insert_num_entries` (all added in API 1.108), but this
 * backend needs none of them. Mapping only the two fields that have existed since API 1.9 means
 * JNA reads exactly 8 bytes and can never run past the end of the struct — including against
 * pre-1.108 libmpv builds, where the trailing three fields do not exist at all. Safe because
 * this struct is only ever read from, never written back.
 *
 * LP64 layout: reason@0, error@4.
 */
@Structure.FieldOrder("reason", "error")
open class MpvEventEndFile(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var reason: Int = 0

    @JvmField var error: Int = 0
}
