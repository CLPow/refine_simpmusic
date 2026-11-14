package com.maxrave.simpmusic

import com.maxrave.domain.data.player.AppOnScreen
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.maxrave.simpmusic.desktop.DesktopSettings
import com.maxrave.simpmusic.desktop.WindowsIntegration
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.SingleInstanceManager
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.common.SUPPORTED_LOCATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.simpmusic.di.viewModelModule
import com.maxrave.simpmusic.extension.DesktopWindowChrome
import com.maxrave.simpmusic.ui.component.CustomTitleBar
import com.maxrave.simpmusic.ui.component.simpMusicScrollbarStyle
import com.maxrave.simpmusic.ui.component.applyWorkAreaMaximizedBounds
import com.maxrave.simpmusic.utils.NetworkFirstInterceptor
import com.maxrave.simpmusic.utils.VersionManager
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import multiplatform.network.cmptoast.ToastHost
import multiplatform.network.cmptoast.showToast
import okhttp3.OkHttpClient
import okio.FileSystem
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.app_name
import simpmusic.composeapp.generated.resources.circle_app_icon
import simpmusic.composeapp.generated.resources.close_miniplayer
import simpmusic.composeapp.generated.resources.explicit_content_blocked
import simpmusic.composeapp.generated.resources.ytm_stream_wifi_only_blocked
import simpmusic.composeapp.generated.resources.ytm_track_skipped
import simpmusic.composeapp.generated.resources.open_app
import simpmusic.composeapp.generated.resources.open_miniplayer
import simpmusic.composeapp.generated.resources.quit_app
import simpmusic.composeapp.generated.resources.time_out_check_internet_connection_or_change_piped_instance_in_settings
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import simpmusic.composeapp.generated.resources.ytm_next
import simpmusic.composeapp.generated.resources.ytm_play_pause
import simpmusic.composeapp.generated.resources.ytm_previous

/**
 * Any `scheme://…` command-line argument. RFC 3986 §3.1 allows ALPHA followed by
 * ALPHA / DIGIT / "+" / "-" / ".".
 */
private val DEEP_LINK_ARG = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://.+")

@OptIn(ExperimentalMaterial3Api::class)
fun runDesktopApp(args: Array<String> = emptyArray()) {
    // Install crash dialog handler first — catches all uncaught exceptions
    CrashDialog.install()

    // Force AWT to probe the Desktop API now, BEFORE anything can load libmpv.
    //
    // On Linux the bundled libmpv drags in its own libglib-2.0.so.0 (2.72, built on
    // Ubuntu 22.04) through the $ORIGIN/lib rpath. Once that soname is taken, AWT's
    // XDesktopPeer.init() can no longer dlopen the SYSTEM libgio-2.0.so.0 — on a host
    // with newer glib (2.80 on Ubuntu 24.04) it dies with
    // "libgobject-2.0.so.0: undefined symbol: g_dir_unref" — and the JDK then reports
    // the whole Desktop API as unsupported for the rest of the process. Every external
    // link breaks: openUrl() silently does nothing, and Compose's LocalUriHandler
    // throws UnsupportedOperationException out of the click handler and crashes the app.
    //
    // XDesktopPeer caches the result of that probe on the first call, so calling it here
    // — while only the system glib is mapped — pins it to "supported" and lets the system
    // gio/gobject win the soname race. AWT is already initialized at this point anyway
    // (forceLinuxWmClass() in Main.kt touches the toolkit before we get here), so this
    // costs nothing.
    //
    // This is a workaround, not the cure: the real fix is to stop bundling glib in
    // scripts/mpv-linux/stage.sh, which needs the Linux mpv tarball rebuilt and
    // republished. Keep this call until that lands.
    java.awt.Desktop.isDesktopSupported()

    System.setProperty("compose.swing.render.on.graphics", "true")
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.layers.type", "COMPONENT")

    // Skiko's vsync wait can park the EDT forever after a display change:
    // macOS waits on a CVDisplayLink-signalled NSConditionLock with no timeout
    // (DisplayLinkThrottler.mm), Linux blocks inside the vsync'd glXSwapBuffers
    // when X11 loses its sync source (CMP-9725 / CMP-10019). Both surface as
    // "UI frozen, audio keeps playing" when the window moves to another
    // monitor. With vsync off, skiko paces frames with its refresh-rate frame
    // limiter (skiko.vsync.framelimit.fallback, on by default) instead.
    // Windows renders via DirectX and shows no such freeze — leave it alone.
    if (!System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) {
        System.setProperty("skiko.vsync.enabled", "false")
    }

    // Handle deep link URIs
    // macOS: receives URI via Desktop open URI handler (app already running or launched via scheme)
    // Windows/Linux: receives URI as command-line argument
    val isMacOS = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
    if (isMacOS && java.awt.Desktop.isDesktopSupported()) {
        val desktop = java.awt.Desktop.getDesktop()
        try {
            desktop.setOpenURIHandler { event ->
                DesktopDeepLinkHandler.onNewUri(event.uri.toString())
            }
        } catch (_: UnsupportedOperationException) {
            // Shouldn't happen on macOS, but handle gracefully
        }
        // Clicking the Dock icon of a running app arrives as a "reopen" Apple
        // Event — macOS never spawns a second instance, so the
        // SingleInstanceManager restore path can't fire for it. Route it
        // through the same restore signal the tray and second instances use.
        if (desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(
                java.awt.desktop.AppReopenedListener { DesktopRestoreSignal.request() },
            )
        }
    }
    // Handle URI passed as command-line argument (Windows/Linux, or explicit invocation)
    // Note: macOS does NOT pass URI as args — it uses Apple Events via setOpenURIHandler
    //
    // Matched by shape rather than against a fixed list of schemes: any scheme we register with
    // the OS must survive this line.
    val deepLinkArg = args.firstOrNull()?.takeIf { DEEP_LINK_ARG.matches(it) }
    // Single-instance guard — MUST run before startKoin. The DataStore Koin
    // singleton is `createdAtStart`, so a second Windows instance would touch
    // ~/.simpmusic/settings.preferences_pb and crash with an "Unable to rename
    // ...tmp" IOException (#2044) before it ever reached the old in-Compose check.
    // Bail out here, before Koin/DataStore initialize.
    val isSingleInstance =
        SingleInstanceManager.isSingleInstance(
            onRestoreRequest = { DesktopRestoreSignal.request() },
        )
    if (!isSingleInstance) {
        // Second instance: forward the deep link (if any) to the running instance,
        // then exit. Nothing has touched the DataStore file yet.
        deepLinkArg?.let { DesktopDeepLinkHandler.writePendingUri(it) }
        return
    }

    // First instance only: deliver our own deep link (non-macOS passes URI via args).
    if (!isMacOS) {
        deepLinkArg?.let { DesktopDeepLinkHandler.onNewUri(it) }
    }

    // Initialize Koin ONCE before application starts
    // Whether this is a virtual machine decides the window type (see detectVirtualMachine). The
    // probe starts a PowerShell, so it runs on its own thread while the app starts, and the
    // answer from the last launch is used straight away when there is one.
    val isVmProbe = java.util.concurrent.CompletableFuture.supplyAsync { detectVirtualMachine() }

    startKoin {
        loadAllModules()
        loadKoinModules(viewModelModule)
    }

    // First run: take the region from the system, as the phone app does. Without this the
    // desktop app kept the built-in default region for good.
    runBlocking {
        val dataStore = getKoin().get<DataStoreManager>()
        if (dataStore.getString("location").first() == null) {
            val country = java.util.Locale.getDefault().country
            dataStore.setLocation(if (SUPPORTED_LOCATION.items.contains(country)) country else "US")
        }
    }

    VersionManager.initialize()
    if (BuildKonfig.sentryDsn.isNotEmpty()) {
        Sentry.init { options ->
            options.dsn = BuildKonfig.sentryDsn
            options.release = "simpmusic-desktop@${VersionManager.getVersionName()}"
            options.setDiagnosticLevel(SentryLevel.ERROR)
        }
    }

    val mediaPlayerHandler by inject<MediaPlayerHandler>(MediaPlayerHandler::class.java)
    // The player calls this from its own threads; the text is looked up off them, not blocked on.
    mediaPlayerHandler.showToast = { type ->
        toastScope.launch {
            showToast(
                when (type) {
                    ToastType.ExplicitContent -> getString(Res.string.explicit_content_blocked)
                    ToastType.TrackSkipped -> getString(Res.string.ytm_track_skipped)
                    ToastType.WifiOnly -> getString(Res.string.ytm_stream_wifi_only_blocked)
                    is ToastType.PlayerError ->
                        getString(Res.string.time_out_check_internet_connection_or_change_piped_instance_in_settings, type.error)
                },
            )
        }
    }
    mediaPlayerHandler.pushPlayerError = { error ->
        Sentry.withScope { scope ->
            Sentry.captureMessage("Player Error: ${error.message}, code: ${error.errorCode}, code name: ${error.errorCodeName}")
        }
    }

    // Register simpmusic:// protocol handler on Windows (HKCU, no admin needed)
    WindowsProtocolRegistrar.register()

    val sharedViewModel = getKoin().get<SharedViewModel>()
    // Connect deep link handler to SharedViewModel
    DesktopDeepLinkHandler.listener = { intent ->
        sharedViewModel.setIntent(intent)
    }

    // ytmdesktop's Windows integrations: the media keys reach the player while the window is in
    // the background (the taskbar controls attach once the window exists, below).
    val onDesktopCommand: (WindowsIntegration.Command) -> Unit = { command ->
        when (command) {
            WindowsIntegration.Command.PlayPause -> sharedViewModel.onUIEvent(UIEvent.PlayPause)
            WindowsIntegration.Command.Next -> sharedViewModel.onUIEvent(UIEvent.Next)
            WindowsIntegration.Command.Previous -> sharedViewModel.onUIEvent(UIEvent.Previous)
        }
    }
    WindowsIntegration.startMediaKeys(onDesktopCommand)
    // Launched by "Start on boot", or with "Start minimized" on: open in the tray.
    val dataStore = getKoin().get<DataStoreManager>()
    val startHidden =
        WindowsIntegration.MINIMIZED_ARG in args ||
            runBlocking { dataStore.getString(DesktopSettings.START_MINIMIZED).first() } == DataStoreManager.TRUE

    application {
        // Main Window
        val windowState =
            rememberWindowState(
                size = DpSize(1500.dp, 860.dp),
            )
        var isVisible by remember { mutableStateOf(!startHidden) }
        // Tells the player when nobody can see the window, so it ticks less (AppOnScreen).
        LaunchedEffect(windowState) {
            snapshotFlow { isVisible && !windowState.isMinimized }.collect { AppOnScreen.set(it) }
        }
        // ytmdesktop's "Hide to tray on close". On by default here, which is how this app has
        // always closed; off, closing the window quits.
        val closeToTray by dataStore
            .getString(DesktopSettings.CLOSE_TO_TRAY)
            .collectAsState(DataStoreManager.TRUE)
        val closeWindow: () -> Unit = {
            if (closeToTray == DataStoreManager.FALSE) {
                mediaPlayerHandler.release()
                exitApplication()
            } else {
                isVisible = false
            }
        }
        // The single-instance guard now runs before startKoin (top of
        // runDesktopApp). Here we only react to a restore request raised when a
        // second instance launches: bring the window back to the foreground and
        // consume any deep link the second instance forwarded.
        LaunchedEffect(Unit) {
            DesktopRestoreSignal.requests.collect {
                isVisible = true
                windowState.isMinimized = false
                DesktopDeepLinkHandler.consumePendingUri()
            }
        }
        val openAppString = stringResource(Res.string.open_app)
        val quitAppString = stringResource(Res.string.quit_app)
        val openMiniPlayer = stringResource(Res.string.open_miniplayer)
        val closeMiniPlayer = stringResource(Res.string.close_miniplayer)
        val appName = stringResource(Res.string.app_name)
        val trayPlayPause = stringResource(Res.string.ytm_play_pause)
        val trayNext = stringResource(Res.string.ytm_next)
        val trayPrevious = stringResource(Res.string.ytm_previous)
        val nowPlayingData by sharedViewModel.nowPlayingScreenData.collectAsState()
        val nowPlayingTitle = nowPlayingData.nowPlayingTitle
        val nowPlayingArtist = nowPlayingData.artistName
        val hasTrack = nowPlayingTitle.isNotBlank()
        // Tray menus are narrow, so long titles would stretch the popup.
        val trayLabel: (String) -> String = { if (it.length > 40) it.take(39).trimEnd() + "…" else it }
        Tray(
            icon = painterResource(Res.drawable.circle_app_icon),
            tooltip =
                when {
                    hasTrack && nowPlayingArtist.isNotBlank() -> "$nowPlayingTitle — $nowPlayingArtist"
                    hasTrack -> nowPlayingTitle
                    else -> appName
                },
            primaryAction = {
                DesktopRestoreSignal.request()
            },
        ) {
            // Disabled entries: while the window is hidden the tray is the only place showing what
            // is playing.
            if (hasTrack) {
                Item(trayLabel(nowPlayingTitle), isEnabled = false)
                if (nowPlayingArtist.isNotBlank()) {
                    Item(trayLabel(nowPlayingArtist), isEnabled = false)
                }
                Divider()
                Item(trayPlayPause) {
                    sharedViewModel.onUIEvent(UIEvent.PlayPause)
                }
                Item(trayNext) { sharedViewModel.onUIEvent(UIEvent.Next) }
                Item(trayPrevious) { sharedViewModel.onUIEvent(UIEvent.Previous) }
                Divider()
            }
            if (!isVisible) {
                Item(openAppString) {
                    DesktopRestoreSignal.request()
                }
            }
            Divider()
            Item(quitAppString) {
                mediaPlayerHandler.release()
                exitApplication()
            }
        }
        // Detect virtual machines (Parallels, VirtualBox, VMware, etc.).
        // Transparent + undecorated Compose windows don't render on VM
        // GPU drivers — the window stays invisible while the JVM keeps
        // running, so we must detect the VM and fall back to a normal
        // decorated window.
        //
        // We probe Manufacturer + Model because brand strings live in
        // different fields per hypervisor (Parallels-on-ARM puts
        // "Parallels Software International Inc." in Manufacturer and
        // "Parallels ARM Virtual Machine" in Model; VirtualBox uses
        // "innotek GmbH" + "VirtualBox"; etc).
        //
        // Microsoft removed `wmic` from Windows 11 (deprecated since
        // 10 21H1), so on modern Windows it returns "command not
        // recognized" and our previous detection always saw an empty
        // vendor — Parallels Win 11 ARM users hit this and got an
        // invisible window. PowerShell `Get-CimInstance` is the modern
        // replacement; we try it first and fall back to wmic for older
        // hosts.
        // The probe's answer, or last launch's while it is still running (it only changes when
        // the machine does).
        val isVM =
            remember {
                cachedVirtualMachine() ?: isVmProbe.get()
            }
        // Publish whether the custom title bar will be mounted so getScreenSizeInfo() can
        // subtract the 40dp strip it occupies above the content (see DesktopWindowChrome).
        LaunchedEffect(isVM) {
            DesktopWindowChrome.customTitleBarVisible = !isVM
        }
        // What is playing, in the caption and therefore in the taskbar preview and alt-tab —
        // the convention every other desktop player follows, and the only way to tell two
        // windows apart without raising them. Falls back to the app name when nothing is loaded.
        val windowTitle =
            when {
                hasTrack && nowPlayingArtist.isNotBlank() -> "$nowPlayingTitle — $nowPlayingArtist"
                hasTrack -> nowPlayingTitle
                else -> appName
            }
        Window(
            onCloseRequest = closeWindow,
            title = windowTitle,
            icon = painterResource(Res.drawable.circle_app_icon),
            undecorated = !isVM,
            transparent = !isVM,
            state = windowState,
            visible = isVisible,
            // YouTube Music web's keyboard shortcuts. onKeyEvent, not the preview: a focused text
            // field (search) gets the keys first, so typing a space never pauses the song.
            onKeyEvent = { event -> handlePlayerShortcut(event, sharedViewModel) },
        ) {
            // Below this the sidebar and the page squeeze each other into an unusable layout.
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(960, 600) }
            // Restore requests (Dock reopen, tray, second instance) also need a
            // z-order raise; visibility/minimized are reset by the
            // application-level collector, but toFront needs the AWT window.
            LaunchedEffect(Unit) {
                DesktopRestoreSignal.requests.collect {
                    window.toFront()
                }
            }
            // Taskbar thumbnail buttons (previous / play-pause / next) and the progress on the
            // taskbar button, as ytmdesktop adds them.
            LaunchedEffect(Unit) {
                WindowsIntegration.attachTaskbar(window.windowHandle, onDesktopCommand)
                // A decorated (VM) window already is a standard one.
                if (!isVM) WindowsIntegration.enableSystemFrame()
                launch {
                    sharedViewModel.controllerState.collect { WindowsIntegration.setPlaying(it.isPlaying) }
                }
                sharedViewModel.timeline
                    .map { t -> if (t.total > 0 && t.current >= 0) (t.current.toFloat() / t.total) else null }
                    .map { f -> f?.let { (it * 200).toInt() / 200f } }
                    .distinctUntilChanged()
                    .collect { WindowsIntegration.setProgress(it) }
            }
            // Keep the maximize target pinned to the current screen work area.
            // The title bar applies this before maximizing itself, but a
            // maximize can also come from the OS — Win+Up, Aero Snap, or a
            // window restored already maximized — and those never reach our
            // click handler. Re-deriving it whenever the window moves or
            // resizes keeps the bounds right across monitors with different
            // taskbar placement.
            if (!isVM) {
                LaunchedEffect(windowState.position, windowState.size) {
                    applyWorkAreaMaximizedBounds(window)
                }
            }
            // Windows rounds only floating windows, and at an 8px radius rather
            // than the 12 used here before. A maximized window is square: the
            // window fills the work area edge to edge, so a rounded clip leaves
            // four uncovered notches showing the desktop through the corners.
            val isWindowMaximized = windowState.placement == WindowPlacement.Maximized
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (!isVM && !isWindowMaximized) {
                                Modifier.clip(RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            },
                        ),
            ) {
                if (!isVM) {
                    // The bar sits outside AppTheme, so its colours are given here: pure black to
                    // match the window colour the shell paints behind the panels.
                    CustomTitleBar(
                        title = windowTitle,
                        windowState = windowState,
                        window = window,
                        onCloseRequest = closeWindow,
                        containerColor = Color.Black,
                        titleColor = Color.White,
                    )
                }

                val context = LocalPlatformContext.current
                setSingletonImageLoaderFactory {
                    lateinit var imageLoader: ImageLoader
                    imageLoader =
                        ImageLoader
                            .Builder(context)
                            .components {
                                add(NetworkFirstInterceptor { imageLoader })
                                add(
                                    OkHttpNetworkFetcherFactory(
                                        callFactory = {
                                            OkHttpClient()
                                        },
                                    ),
                                )
                            }.diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .diskCache(
                                DiskCache
                                    .Builder()
                                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                                    .maxSizeBytes(512L * 1024 * 1024)
                                    .build(),
                            ).crossfade(true)
                            .build()
                    imageLoader
                }
                // One scrollbar appearance for every scrollbar in the app, rather than each
                // site restyling Compose's light-surface default on its own.
                CompositionLocalProvider(LocalScrollbarStyle provides simpMusicScrollbarStyle) {
                    App()
                }
                ToastHost()
            }
        }

    }
}

/**
 * Bridges a restore request from the single-instance guard (which runs outside
 * Compose, at the top of [runDesktopApp]) into the running window's composition.
 * The guard calls [request] when a second instance launches; the window collects
 * [requests] to bring itself back to the foreground and pick up any deep link.
 */
private object DesktopRestoreSignal {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun request() {
        _requests.tryEmit(Unit)
    }
}

/** For the player's toasts, which arrive on the player's threads. */
private val toastScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

private const val VM_PREF_KEY = "isVirtualMachine"

private fun vmPrefs() = java.util.prefs.Preferences.userRoot().node("refine-music")

/** Last launch's answer to [detectVirtualMachine], or null on the first launch. */
private fun cachedVirtualMachine(): Boolean? =
    runCatching { vmPrefs().get(VM_PREF_KEY, null)?.toBooleanStrictOrNull() }.getOrNull()

/**
 * Detects virtual machines (Parallels, VirtualBox, VMware, etc.). Transparent, undecorated
 * Compose windows don't render on VM GPU drivers: the window stays invisible while the JVM keeps
 * running, so a VM gets a normal decorated window instead.
 *
 * Manufacturer and Model are both probed because brand strings live in different fields per
 * hypervisor (Parallels on ARM puts "Parallels Software International Inc." in Manufacturer and
 * "Parallels ARM Virtual Machine" in Model; VirtualBox uses "innotek GmbH" + "VirtualBox").
 * PowerShell's Get-CimInstance comes first because Windows 11 removed `wmic`, which is kept as a
 * fallback for older hosts. The answer is saved for the next launch.
 */
private fun detectVirtualMachine(): Boolean {
    if (System.getProperty("compose.window.no-transparent", "false").toBooleanStrictOrNull() == true) return true
    val osName = System.getProperty("os.name", "")
    if (!osName.contains("Windows", ignoreCase = true)) return false
    val probes =
        listOf(
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "(Get-CimInstance Win32_ComputerSystem | " +
                    "Select-Object Manufacturer,Model | " +
                    "Format-List | Out-String).Trim()",
            ),
            listOf("wmic", "computersystem", "get", "manufacturer,model"),
        )
    val sysInfo =
        probes
            .asSequence()
            .mapNotNull { cmd ->
                runCatching {
                    val p =
                        ProcessBuilder(cmd)
                            .redirectErrorStream(true)
                            .start()
                    val out = p.inputStream.bufferedReader().readText()
                    if (p.waitFor() == 0 && out.isNotBlank()) out else null
                }.getOrNull()
            }.firstOrNull()
            .orEmpty()
    val vmTokens = listOf("Parallels", "VirtualBox", "VMware", "QEMU", "KVM", "Xen", "Hyper-V")
    val isVm = vmTokens.any { sysInfo.contains(it, ignoreCase = true) }
    runCatching { vmPrefs().put(VM_PREF_KEY, isVm.toString()) }
    return isVm
}

/**
 * YouTube Music web's player shortcuts: Space plays or pauses, Shift+N and Shift+P skip, L and H
 * (or the arrow keys with Shift) seek ten seconds, + and - change the volume.
 */
private fun handlePlayerShortcut(
    event: androidx.compose.ui.input.key.KeyEvent,
    sharedViewModel: SharedViewModel,
): Boolean {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    val seekBy: (Long) -> Unit = { deltaMs ->
        val t = sharedViewModel.timeline.value
        if (t.total > 0) {
            val target = (t.current + deltaMs).coerceIn(0L, t.total)
            sharedViewModel.onUIEvent(UIEvent.UpdateProgress(target * 100f / t.total))
        }
    }
    val volume = sharedViewModel.controllerState.value.volume
    when (event.key) {
        Key.Spacebar -> sharedViewModel.onUIEvent(UIEvent.PlayPause)
        Key.N -> if (event.isShiftPressed) sharedViewModel.onUIEvent(UIEvent.Next) else return false
        Key.P -> if (event.isShiftPressed) sharedViewModel.onUIEvent(UIEvent.Previous) else return false
        Key.L -> seekBy(10_000)
        Key.H -> seekBy(-10_000)
        Key.DirectionRight -> if (event.isShiftPressed) seekBy(10_000) else return false
        Key.DirectionLeft -> if (event.isShiftPressed) seekBy(-10_000) else return false
        Key.Equals, Key.Plus, Key.NumPadAdd -> sharedViewModel.onUIEvent(UIEvent.UpdateVolume((volume + 0.05f).coerceAtMost(1f)))
        Key.Minus, Key.NumPadSubtract -> sharedViewModel.onUIEvent(UIEvent.UpdateVolume((volume - 0.05f).coerceAtLeast(0f)))
        else -> return false
    }
    return true
}
