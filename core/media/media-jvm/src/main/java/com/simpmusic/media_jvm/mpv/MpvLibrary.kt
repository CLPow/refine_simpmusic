package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

private const val TAG = "MpvLibrary"

@Suppress("FunctionNaming", "FunctionParameterNaming")
interface MpvLibrary : Library {
    fun mpv_create(): Pointer?

    fun mpv_initialize(ctx: Pointer): Int

    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    fun mpv_set_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int

    fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int

    fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer?

    fun mpv_observe_property(
        mpv: Pointer,
        reply_userdata: Long,
        name: String,
        format: Int,
    ): Int

    fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int

    fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer?

    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(
        res: PointerByReference,
        mpv: Pointer,
        params: Pointer,
    ): Int

    fun mpv_render_context_render(
        ctx: Pointer,
        params: Pointer,
    ): Int

    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: MpvRenderUpdateFn?,
        callbackCtx: Pointer?,
    )

    fun mpv_render_context_update(ctx: Pointer): Long

    fun mpv_render_context_free(ctx: Pointer)

    fun mpv_free(data: Pointer)

    fun mpv_error_string(error: Int): String?

    fun mpv_client_api_version(): Long

    companion object {
        private const val EXPECTED_API_VERSION = (2L shl 16) or 2L

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

        val INSTANCE: MpvLibrary? by lazy { loadLibrary() }

        private interface LibC : com.sun.jna.Library {
            fun setlocale(
                category: Int,
                locale: String?,
            ): String?
        }

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

        private fun isMpvLibName(name: String): Boolean =
            name.startsWith("libmpv.so") ||
                name.startsWith("libmpv.") && name.endsWith(".dylib") ||
                name == "libmpv.dylib" ||
                name.startsWith("libmpv") && name.endsWith(".dll") ||
                name == "mpv-2.dll"

        private fun hasMpvLib(dir: java.io.File): Boolean =
            dir.listFiles()?.any { isMpvLibName(it.name) } == true

        private fun bundledLibraryFiles(dirs: List<java.io.File>): List<String> =
            dirs.flatMap { dir ->
                dir.listFiles()?.filter { it.isFile && isMpvLibName(it.name) }?.map { it.absolutePath }
                    ?: emptyList()
            }

        private fun bundledLibraryDirs(): List<java.io.File> {
            val candidates = mutableListOf<java.io.File>()

            System.getProperty("mpv.bundled.path")?.let { candidates += java.io.File(it) }

            System.getProperty("compose.application.resources.dir")?.let { dir ->
                val root = java.io.File(dir)
                candidates += root
                root.listFiles()?.filter { it.isDirectory }?.let { candidates += it }
            }

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

            val bundled = bundledLibraryDirs()
            if (bundled.isNotEmpty()) {
                val bundledPath = bundled.joinToString(java.io.File.pathSeparator) { it.absolutePath }

                CANDIDATE_NAMES.forEach { candidate ->
                    bundled.forEach { dir ->
                        com.sun.jna.NativeLibrary.addSearchPath(candidate, dir.absolutePath)
                    }
                }

                val existing = System.getProperty("jna.library.path")
                System.setProperty(
                    "jna.library.path",
                    if (existing.isNullOrBlank()) bundledPath else "$bundledPath${java.io.File.pathSeparator}$existing",
                )
                Logger.d(TAG, "Bundled libmpv search path: $bundledPath")
            } else {
                Logger.d(TAG, "No bundled libmpv found; falling back to a system-wide install")
            }

            val loadTargets = bundledLibraryFiles(bundled) + CANDIDATE_NAMES
            for (name in loadTargets) {
                try {
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
                    val resolved =
                        runCatching {
                            com.sun.jna.NativeLibrary.getInstance(name).file?.absolutePath
                        }.getOrNull() ?: "<unknown>"
                    Logger.d(
                        TAG,
                        "Loaded libmpv as '$name' (client API ${version shr 16}.${version and 0xFFFF}) from $resolved",
                    )
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

object MpvEndFileReason {
    const val EOF = 0
    const val STOP = 2
    const val QUIT = 3
    const val ERROR = 4
    const val REDIRECT = 5
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var event_id: Int = 0

    @JvmField var error: Int = 0

    @JvmField var reply_userdata: Long = 0

    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("name", "format", "data")
open class MpvEventProperty(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var name: Pointer? = null

    @JvmField var format: Int = 0

    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("reason", "error")
open class MpvEventEndFile(
    pointer: Pointer? = null,
) : Structure(pointer) {
    @JvmField var reason: Int = 0

    @JvmField var error: Int = 0
}

object MpvRenderParamType {
    const val INVALID = 0
    const val API_TYPE = 1
    const val ADVANCED_CONTROL = 10
    const val SW_SIZE = 17
    const val SW_FORMAT = 18
    const val SW_STRIDE = 19
    const val SW_POINTER = 20
}

const val MPV_RENDER_API_TYPE_SW = "sw"

object MpvRenderUpdateFlag {
    const val FRAME = 1 shl 0
}

fun interface MpvRenderUpdateFn : Callback {
    fun invoke(cbCtx: Pointer?)
}

private const val RENDER_PARAM_SIZE = 16L
private const val RENDER_PARAM_DATA_OFFSET = 8L

internal fun buildRenderParams(params: List<Pair<Int, Pointer?>>): Memory {
    val block = Memory(RENDER_PARAM_SIZE * (params.size + 1))
    block.clear()
    params.forEachIndexed { i, (type, data) ->
        val base = RENDER_PARAM_SIZE * i
        block.setInt(base, type)
        block.setPointer(base + RENDER_PARAM_DATA_OFFSET, data)
    }
    return block
}

internal fun buildEdlUrl(
    videoUrl: String,
    audioUrl: String,
): String =
    listOf(videoUrl, audioUrl).joinToString(separator = ";", prefix = "edl://") { url ->
        "!new_stream;${edlEscape(url)}"
    }

internal fun edlEscape(value: String): String {
    val byteLength = value.toByteArray(Charsets.UTF_8).size
    return "%$byteLength%$value"
}
