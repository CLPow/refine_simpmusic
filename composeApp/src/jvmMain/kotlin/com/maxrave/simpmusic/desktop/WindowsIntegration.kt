package com.maxrave.simpmusic.desktop

import com.maxrave.logger.Logger
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Windows desktop integration, ported from what ytmdesktop (github.com/ytmdesktop/ytmdesktop) adds
 * around YouTube Music: the media keys work while the app is in the background, the taskbar
 * button carries previous / play-pause / next under its thumbnail, and the taskbar button fills
 * with the song's progress. Also the "Start on boot" registry entry.
 *
 * ytmdesktop gets these from Electron (globalShortcut, BrowserWindow.setThumbarButtons,
 * setProgressBar); this is the same Win32 underneath, called through JNA:
 * RegisterHotKey for the media keys, and ITaskbarList3 for the taskbar.
 *
 * It also makes the borderless window a standard one to the shell (see [enableSystemFrame]), so
 * FancyZones and Snap work on it.
 *
 * Everything degrades to a no-op off Windows or when a call fails: none of it may cost playback.
 */
object WindowsIntegration {
    private const val TAG = "WindowsIntegration"
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    enum class Command { PlayPause, Next, Previous }

    // region Media keys

    private const val WM_HOTKEY = 0x0312
    private const val MOD_NOREPEAT = 0x4000
    private const val VK_MEDIA_NEXT_TRACK = 0xB0
    private const val VK_MEDIA_PREV_TRACK = 0xB1
    private const val VK_MEDIA_PLAY_PAUSE = 0xB3

    @Volatile private var mediaKeysStarted = false

    /**
     * Registers the three media keys system-wide on a thread of their own, which then waits for
     * them. If another app already holds a key, that key is left to it (as Electron's
     * globalShortcut does) and the others still work.
     */
    fun startMediaKeys(onCommand: (Command) -> Unit) {
        if (!isWindows || mediaKeysStarted) return
        mediaKeysStarted = true
        Thread({
            val keys = mapOf(1 to VK_MEDIA_PLAY_PAUSE, 2 to VK_MEDIA_NEXT_TRACK, 3 to VK_MEDIA_PREV_TRACK)
            keys.forEach { (id, vk) ->
                if (!User32.INSTANCE.RegisterHotKey(null, id, MOD_NOREPEAT, vk)) {
                    Logger.w(TAG, "Media key $vk is taken by another app")
                }
            }
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) {
                    when (msg.wParam.toInt()) {
                        1 -> onCommand(Command.PlayPause)
                        2 -> onCommand(Command.Next)
                        3 -> onCommand(Command.Previous)
                    }
                }
            }
        }, "media-keys").apply { isDaemon = true }.start()
    }

    // endregion

    // region Taskbar (ITaskbarList3)

    /** ITaskbarList3, called by vtable slot: IUnknown 0-2, ITaskbarList 3-7, ITaskbarList2 8, ITaskbarList3 9+. */
    private class TaskbarList3(pointer: Pointer) : Unknown(pointer) {
        fun hrInit(): Int = _invokeNativeInt(3, arrayOf(pointer))

        fun setProgressValue(
            hwnd: WinDef.HWND,
            done: Long,
            total: Long,
        ): Int = _invokeNativeInt(9, arrayOf(pointer, hwnd, done, total))

        fun setProgressState(
            hwnd: WinDef.HWND,
            flags: Int,
        ): Int = _invokeNativeInt(10, arrayOf(pointer, hwnd, flags))

        fun thumbBarAddButtons(
            hwnd: WinDef.HWND,
            buttons: Array<ThumbButton>,
        ): Int = _invokeNativeInt(15, arrayOf(pointer, hwnd, buttons.size, buttons[0].pointer))

        fun thumbBarUpdateButtons(
            hwnd: WinDef.HWND,
            buttons: Array<ThumbButton>,
        ): Int = _invokeNativeInt(16, arrayOf(pointer, hwnd, buttons.size, buttons[0].pointer))
    }

    /** THUMBBUTTON from ShObjIdl_core.h. */
    @Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
    class ThumbButton : Structure() {
        @JvmField var dwMask: Int = 0

        @JvmField var iId: Int = 0

        @JvmField var iBitmap: Int = 0

        @JvmField var hIcon: WinDef.HICON? = null

        @JvmField var szTip: CharArray = CharArray(260)

        @JvmField var dwFlags: Int = 0
    }

    private val CLSID_TASKBAR_LIST = Guid.CLSID("{56FDF344-FD6D-11d0-958A-006097C9A090}")
    private val IID_ITASKBAR_LIST3 = Guid.IID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}")
    private const val TBPF_NOPROGRESS = 0
    private const val TBPF_NORMAL = 0x2
    private const val TBPF_PAUSED = 0x8
    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBN_CLICKED = 0x1800
    private const val WM_COMMAND = 0x0111
    private const val GWLP_WNDPROC = -4
    private const val ID_PREVIOUS = 0x5101
    private const val ID_PLAY_PAUSE = 0x5102
    private const val ID_NEXT = 0x5103

    /** One STA thread owns the COM object; every taskbar call goes through it. */
    private val com = Executors.newSingleThreadExecutor { Thread(it, "taskbar-com").apply { isDaemon = true } }
    private var taskbar: TaskbarList3? = null
    private var hwnd: WinDef.HWND? = null
    private var buttonsAdded = false
    private var playing = false
    private var icons: Map<String, WinDef.HICON> = emptyMap()
    private var lastProgress: Float? = null

    // Held so the native side never calls a collected callback.
    private var wndProc: WinUser.WindowProc? = null
    @Volatile private var previousWndProc: Pointer? = null
    private var taskbarCreatedMessage = 0

    /**
     * Attaches the taskbar controls to the window [windowHandle] (ComposeWindow.windowHandle).
     * [onCommand] receives the thumbnail button presses.
     */
    fun attachTaskbar(
        windowHandle: Long,
        onCommand: (Command) -> Unit,
    ) {
        if (!isWindows || windowHandle == 0L || hwnd != null) return
        val handle = WinDef.HWND(Pointer(windowHandle))
        hwnd = handle
        taskbarCreatedMessage = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")
        com.execute {
            runCatching {
                Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
                val ref = PointerByReference()
                val hr = Ole32.INSTANCE.CoCreateInstance(CLSID_TASKBAR_LIST, null, 1, IID_ITASKBAR_LIST3, ref)
                if (hr.toInt() != 0) error("CoCreateInstance failed: ${hr.toInt()}")
                taskbar = TaskbarList3(ref.value).also { it.hrInit() }
                icons = buildIcons()
                addButtons()
            }.onFailure { Logger.w(TAG, "Taskbar unavailable: ${it.message}") }
        }
        // Thumbnail button presses arrive as WM_COMMAND on the window; the taskbar button being
        // recreated (Explorer restart, the window coming back from the tray) as TaskbarButtonCreated.
        runCatching {
            val proc =
                WinUser.WindowProc { h, msg, wParam, lParam ->
                    if (msg == WM_COMMAND && (wParam.toInt() ushr 16) == THBN_CLICKED) {
                        when (wParam.toInt() and 0xFFFF) {
                            ID_PREVIOUS -> onCommand(Command.Previous)
                            ID_PLAY_PAUSE -> onCommand(Command.PlayPause)
                            ID_NEXT -> onCommand(Command.Next)
                        }
                    } else if (msg == WM_NCCALCSIZE && systemFrame && wParam.toLong() != 0L) {
                        // The whole window stays client area: WS_THICKFRAME is there for the
                        // shell to see, not to draw a border around the custom title bar.
                        return@WindowProc WinDef.LRESULT(0)
                    } else if (msg == WM_APP_SYSTEM_MOVE) {
                        // On the window's own thread, which holds the mouse capture. Windows
                        // runs its move loop here until the button is released.
                        runCatching { User32Ext.INSTANCE?.ReleaseCapture() }
                        User32.INSTANCE.DefWindowProc(h, WM_SYSCOMMAND, WinDef.WPARAM(SC_DRAGMOVE), WinDef.LPARAM(0))
                        return@WindowProc WinDef.LRESULT(0)
                    } else if (msg == taskbarCreatedMessage) {
                        com.execute {
                            buttonsAdded = false
                            addButtons()
                            lastProgress?.let { applyProgress(it) }
                        }
                    }
                    // A message can arrive before SetWindowLongPtr has returned the old procedure.
                    val previous = previousWndProc
                    if (previous == null) {
                        User32.INSTANCE.DefWindowProc(h, msg, wParam, lParam)
                    } else {
                        User32.INSTANCE.CallWindowProc(previous, h, msg, wParam, lParam)
                    }
                }
            wndProc = proc
            previousWndProc =
                User32.INSTANCE.SetWindowLongPtr(handle, GWLP_WNDPROC, CallbackReference.getFunctionPointer(proc))
        }.onFailure { Logger.w(TAG, "Thumbnail buttons unavailable: ${it.message}") }
    }

    // endregion

    // region Window snapping (FancyZones, Snap)

    private const val GWL_STYLE = -16
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_SYSCOMMAND = 0x0112
    private const val WM_APP_SYSTEM_MOVE = 0x8000 + 0x52
    private const val SC_DRAGMOVE = 0xF012L
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    @Volatile private var systemFrame = false

    @Suppress("FunctionName")
    private interface User32Ext : StdCallLibrary {
        fun ReleaseCapture(): Boolean

        companion object {
            val INSTANCE: User32Ext? =
                runCatching { Native.load("user32", User32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }
    }

    /**
     * FancyZones only zones a borderless (WS_POPUP) window that also has a sizing frame and the
     * minimize and maximize boxes, and it, like Snap, only reacts to Windows' own move loop. AWT's
     * undecorated frame has neither, and the title bar used to move it by hand with setLocation,
     * so the window was invisible to both. This adds the three styles, keeps the frame out of
     * sight (WM_NCCALCSIZE above), and [beginSystemMove] hands title-bar drags to Windows.
     *
     * Needs the subclass from [attachTaskbar]; without it the frame would be drawn, so nothing is
     * changed.
     */
    fun enableSystemFrame() {
        val handle = hwnd ?: return
        if (!isWindows || previousWndProc == null) return
        runCatching {
            systemFrame = true
            val style = User32.INSTANCE.GetWindowLong(handle, GWL_STYLE)
            User32.INSTANCE.SetWindowLong(handle, GWL_STYLE, style or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX)
            User32.INSTANCE.SetWindowPos(
                handle,
                null,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
            )
        }.onFailure {
            systemFrame = false
            Logger.w(TAG, "Window snapping unavailable: ${it.message}")
        }
    }

    /**
     * Lets Windows move the window for the drag in progress, as it does when a native title bar
     * is dragged. Call while the left button is down. False when unavailable, in which case the
     * caller moves the window itself.
     */
    fun beginSystemMove(): Boolean {
        val handle = hwnd ?: return false
        if (!systemFrame) return false
        return User32.INSTANCE.PostMessage(handle, WM_APP_SYSTEM_MOVE, WinDef.WPARAM(0), WinDef.LPARAM(0)).let { true }
    }

    /** Swaps the middle thumbnail button between play and pause. */
    fun setPlaying(isPlaying: Boolean) {
        if (!isWindows || hwnd == null) return
        com.execute {
            if (playing == isPlaying && buttonsAdded) return@execute
            playing = isPlaying
            if (buttonsAdded) {
                runCatching { taskbar?.thumbBarUpdateButtons(hwnd!!, buttons()) }
            }
            lastProgress?.let { applyProgress(it) }
        }
    }

    /** The song's progress on the taskbar button, 0..1; null clears it. */
    fun setProgress(fraction: Float?) {
        if (!isWindows || hwnd == null) return
        lastProgress = fraction
        com.execute { applyProgress(fraction) }
    }

    private fun applyProgress(fraction: Float?) {
        val bar = taskbar ?: return
        val h = hwnd ?: return
        runCatching {
            if (fraction == null) {
                bar.setProgressState(h, TBPF_NOPROGRESS)
            } else {
                bar.setProgressState(h, if (playing) TBPF_NORMAL else TBPF_PAUSED)
                bar.setProgressValue(h, (fraction.coerceIn(0f, 1f) * 1000).toLong(), 1000L)
            }
        }
    }

    private fun addButtons() {
        val bar = taskbar ?: return
        val h = hwnd ?: return
        if (buttonsAdded) return
        val hr = runCatching { bar.thumbBarAddButtons(h, buttons()) }.getOrDefault(-1)
        buttonsAdded = hr == 0
    }

    private fun buttons(): Array<ThumbButton> {
        @Suppress("UNCHECKED_CAST")
        val array = ThumbButton().toArray(3) as Array<ThumbButton>
        fun fill(
            b: ThumbButton,
            id: Int,
            icon: String,
            tip: String,
        ) {
            b.dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            b.iId = id
            b.hIcon = icons[icon]
            b.szTip = tip.toCharArray().copyOf(260)
            b.dwFlags = 0
            b.write()
        }
        fill(array[0], ID_PREVIOUS, "previous", "Previous")
        fill(array[1], ID_PLAY_PAUSE, if (playing) "pause" else "play", if (playing) "Pause" else "Play")
        fill(array[2], ID_NEXT, "next", "Next")
        return array
    }

    /** White transport glyphs drawn here and loaded as icons, so no image files have to ship. */
    private fun buildIcons(): Map<String, WinDef.HICON> {
        val dir = File(System.getProperty("java.io.tmpdir"), "refine-music-taskbar").apply { mkdirs() }
        return listOf("previous", "play", "pause", "next")
            .mapNotNull { name ->
                val file = File(dir, "$name.ico")
                file.writeBytes(icoOf(glyph(name)))
                val handle =
                    User32.INSTANCE.LoadImage(null, file.absolutePath, WinUser.IMAGE_ICON, 32, 32, WinUser.LR_LOADFROMFILE)
                handle?.let { name to WinDef.HICON(it.pointer) }
            }.toMap()
    }

    private fun glyph(name: String): BufferedImage {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        when (name) {
            "play" -> g.fill(Path2D.Float().apply { moveTo(10f, 6f); lineTo(26f, 16f); lineTo(10f, 26f); closePath() })
            "pause" -> {
                g.fillRect(9, 7, 5, 18)
                g.fillRect(18, 7, 5, 18)
            }
            "next" -> {
                g.fill(Path2D.Float().apply { moveTo(7f, 7f); lineTo(20f, 16f); lineTo(7f, 25f); closePath() })
                g.stroke = BasicStroke(3f)
                g.drawLine(23, 7, 23, 25)
            }
            else -> {
                g.fill(Path2D.Float().apply { moveTo(25f, 7f); lineTo(12f, 16f); lineTo(25f, 25f); closePath() })
                g.stroke = BasicStroke(3f)
                g.drawLine(9, 7, 9, 25)
            }
        }
        g.dispose()
        return image
    }

    /** A one-image .ico holding the PNG as is (supported since Windows Vista). */
    private fun icoOf(image: BufferedImage): ByteArray {
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(1)
        header.put(32.toByte()).put(32.toByte()).put(0).put(0)
        header.putShort(1).putShort(32).putInt(png.size).putInt(22)
        return header.array() + png
    }

    // endregion

    // region Start on boot

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "RefineMusic"
    const val MINIMIZED_ARG = "--minimized"

    /** The installed launcher, or null when not running from it (a dev run through Gradle). */
    private fun launcherPath(): String? =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
            ?.takeIf { it.endsWith(".exe", ignoreCase = true) && !it.endsWith("java.exe", ignoreCase = true) }

    fun isStartOnBootSupported(): Boolean = isWindows && launcherPath() != null

    /** Adds or removes the HKCU Run entry; the boot launch starts in the tray. */
    fun setStartOnBoot(enabled: Boolean) {
        if (!isWindows) return
        runCatching {
            if (enabled) {
                val path = launcherPath() ?: return
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE, "\"$path\" $MINIMIZED_ARG")
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE)
            }
        }.onFailure { Logger.w(TAG, "Start on boot: ${it.message}") }
    }

    fun isStartOnBootEnabled(): Boolean =
        isWindows && runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE) }.getOrDefault(false)

    // endregion
}
