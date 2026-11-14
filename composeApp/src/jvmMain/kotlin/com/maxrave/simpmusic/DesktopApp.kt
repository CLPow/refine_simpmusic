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

private val DEEP_LINK_ARG = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://.+")

@OptIn(ExperimentalMaterial3Api::class)
fun runDesktopApp(args: Array<String> = emptyArray()) {
    CrashDialog.install()

    java.awt.Desktop.isDesktopSupported()

    System.setProperty("compose.swing.render.on.graphics", "true")
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.layers.type", "COMPONENT")

    if (!System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) {
        System.setProperty("skiko.vsync.enabled", "false")
    }

    val isMacOS = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
    if (isMacOS && java.awt.Desktop.isDesktopSupported()) {
        val desktop = java.awt.Desktop.getDesktop()
        try {
            desktop.setOpenURIHandler { event ->
                DesktopDeepLinkHandler.onNewUri(event.uri.toString())
            }
        } catch (_: UnsupportedOperationException) {
        }
        if (desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(
                java.awt.desktop.AppReopenedListener { DesktopRestoreSignal.request() },
            )
        }
    }
    val deepLinkArg = args.firstOrNull()?.takeIf { DEEP_LINK_ARG.matches(it) }
    val isSingleInstance =
        SingleInstanceManager.isSingleInstance(
            onRestoreRequest = { DesktopRestoreSignal.request() },
        )
    if (!isSingleInstance) {
        deepLinkArg?.let { DesktopDeepLinkHandler.writePendingUri(it) }
        return
    }

    if (!isMacOS) {
        deepLinkArg?.let { DesktopDeepLinkHandler.onNewUri(it) }
    }

    val isVmProbe = java.util.concurrent.CompletableFuture.supplyAsync { detectVirtualMachine() }

    startKoin {
        loadAllModules()
        loadKoinModules(viewModelModule)
    }

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

    WindowsProtocolRegistrar.register()

    val sharedViewModel = getKoin().get<SharedViewModel>()
    DesktopDeepLinkHandler.listener = { intent ->
        sharedViewModel.setIntent(intent)
    }

    val onDesktopCommand: (WindowsIntegration.Command) -> Unit = { command ->
        when (command) {
            WindowsIntegration.Command.PlayPause -> sharedViewModel.onUIEvent(UIEvent.PlayPause)
            WindowsIntegration.Command.Next -> sharedViewModel.onUIEvent(UIEvent.Next)
            WindowsIntegration.Command.Previous -> sharedViewModel.onUIEvent(UIEvent.Previous)
        }
    }
    WindowsIntegration.startMediaKeys(onDesktopCommand)
    val dataStore = getKoin().get<DataStoreManager>()
    val startHidden =
        WindowsIntegration.MINIMIZED_ARG in args ||
            runBlocking { dataStore.getString(DesktopSettings.START_MINIMIZED).first() } == DataStoreManager.TRUE

    application {
        val windowState =
            rememberWindowState(
                size = DpSize(1500.dp, 860.dp),
            )
        var isVisible by remember { mutableStateOf(!startHidden) }
        LaunchedEffect(windowState) {
            snapshotFlow { isVisible && !windowState.isMinimized }.collect { AppOnScreen.set(it) }
        }
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
        val isVM =
            remember {
                cachedVirtualMachine() ?: isVmProbe.get()
            }
        LaunchedEffect(isVM) {
            DesktopWindowChrome.customTitleBarVisible = !isVM
        }
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
            // onKeyEvent, not the preview: a focused text field (search) gets the keys first, so typing a space never pauses the song.
            onKeyEvent = { event -> handlePlayerShortcut(event, sharedViewModel) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(960, 600) }
            LaunchedEffect(Unit) {
                DesktopRestoreSignal.requests.collect {
                    window.toFront()
                }
            }
            LaunchedEffect(Unit) {
                WindowsIntegration.attachTaskbar(window.windowHandle, onDesktopCommand)
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
            if (!isVM) {
                LaunchedEffect(windowState.position, windowState.size) {
                    applyWorkAreaMaximizedBounds(window)
                }
            }
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
                CompositionLocalProvider(LocalScrollbarStyle provides simpMusicScrollbarStyle) {
                    App()
                }
                ToastHost()
            }
        }

    }
}

private object DesktopRestoreSignal {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun request() {
        _requests.tryEmit(Unit)
    }
}

private val toastScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

private const val VM_PREF_KEY = "isVirtualMachine"

private fun vmPrefs() = java.util.prefs.Preferences.userRoot().node("refine-music")

private fun cachedVirtualMachine(): Boolean? =
    runCatching { vmPrefs().get(VM_PREF_KEY, null)?.toBooleanStrictOrNull() }.getOrNull()

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
