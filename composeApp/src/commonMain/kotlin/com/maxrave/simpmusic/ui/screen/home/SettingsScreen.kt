package com.maxrave.simpmusic.ui.screen.home

import com.maxrave.simpmusic.desktop.setStartOnBoot
import com.maxrave.simpmusic.desktop.isStartOnBootSupported
import com.maxrave.simpmusic.desktop.isStartOnBootEnabled
import com.maxrave.simpmusic.desktop.DesktopSettings
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.Platform
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.LocalPlatformContext
import com.maxrave.common.QUALITY
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LOCATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.expect.hasSystemEqualizer
import com.maxrave.simpmusic.expect.openSystemEqualizer
import com.maxrave.simpmusic.expect.openUrl
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsCategoryDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.utils.VersionManager
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res

/** YouTube Music's settings sections, in its order (memberships and developer pages left out). */
enum class SettingsCategory(
    val key: String,
    val title: String,
) {
    General("general", "General"),
    Playback("playback", "Playback"),
    DataSaving("data_saving", "Data saving"),
    Downloads("downloads", "Downloads & storage"),
    Privacy("privacy", "Privacy & data"),
    About("about", "About Refine Music"),
    ;

    companion object {
        fun fromKey(key: String): SettingsCategory = entries.firstOrNull { it.key == key } ?: General
    }
}

/** YouTube Music's Settings: a plain list of its sections. */
@Composable
fun SettingsHomeScreen(
    navController: NavController,
    innerPadding: PaddingValues = PaddingValues(),
) {
    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = "Settings", onBack = { navController.navigateUp() })
        LazyColumn(Modifier.fillMaxSize()) {
            items(SettingsCategory.entries) { category ->
                SettingRow(title = category.title) {
                    navController.navigate(SettingsCategoryDestination(category = category.key))
                }
            }
        }
    }
}

/** One section of Settings. */
@OptIn(ExperimentalMaterial3Api::class, coil3.annotation.ExperimentalCoilApi::class)
@Composable
fun SettingScreen(
    navController: NavController,
    innerPadding: PaddingValues = PaddingValues(),
    category: SettingsCategory,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    dataStoreManager: DataStoreManager = koinInject(),
) {
    LaunchedEffect(Unit) { viewModel.getData() }
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val downloadQuality by viewModel.downloadQuality.collectAsStateWithLifecycle()
    val sendBackToGoogle by viewModel.sendBackToGoogle.collectAsStateWithLifecycle()
    val explicit by viewModel.explicitContentEnabled.collectAsStateWithLifecycle()
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    val downloadedSize by viewModel.downloadedCacheSize.collectAsStateWithLifecycle()
    val autoplay by dataStoreManager.endlessQueue.map { it == DataStoreManager.TRUE }.collectAsStateWithLifecycle(false)
    var choice by remember { mutableStateOf<ChoiceDialog?>(null) }
    var confirm by remember { mutableStateOf<ConfirmDialog?>(null) }
    var showLicenses by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = category.title, onBack = { navController.navigateUp() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (category) {
                SettingsCategory.General -> {
                    SwitchRow(
                        title = "Restricted mode",
                        summary = "Hides songs that may contain mature content",
                        checked = !explicit,
                    ) { viewModel.setExplicitContentEnabled(!it) }
                    SettingRow(
                        title = "App language",
                        summary = SUPPORTED_LANGUAGE.getLanguageFromCode(language ?: "en-US"),
                    ) {
                        val names = SUPPORTED_LANGUAGE.items.map { it.toString() }
                        choice =
                            ChoiceDialog(
                                title = "App language",
                                options = names,
                                selected = names.indexOf(SUPPORTED_LANGUAGE.getLanguageFromCode(language ?: "en-US")),
                            ) { index ->
                                sharedViewModel.activityRecreate()
                                viewModel.changeLanguage(SUPPORTED_LANGUAGE.getCodeFromLanguage(names[index]))
                            }
                    }
                    SettingRow(title = "Location", summary = location.orEmpty()) {
                        val codes = SUPPORTED_LOCATION.items.map { it.toString() }
                        choice =
                            ChoiceDialog("Location", codes, codes.indexOf(location)) { index ->
                                viewModel.changeLocation(codes[index])
                            }
                    }
                    if (getPlatform() == Platform.Desktop) {
                        // ytmdesktop's window options.
                        if (isStartOnBootSupported()) {
                            var onBoot by remember { mutableStateOf(isStartOnBootEnabled()) }
                            SwitchRow(title = "Start on boot", summary = "Open in the tray when you sign in to Windows", checked = onBoot) {
                                setStartOnBoot(it)
                                onBoot = isStartOnBootEnabled()
                            }
                        }
                        val startMinimized by dataStoreManager.getString(DesktopSettings.START_MINIMIZED).collectAsStateWithLifecycle(null)
                        SwitchRow(title = "Start minimized", summary = "Open in the tray instead of a window", checked = startMinimized == DataStoreManager.TRUE) {
                            scope.launch { dataStoreManager.putString(DesktopSettings.START_MINIMIZED, if (it) DataStoreManager.TRUE else DataStoreManager.FALSE) }
                        }
                        val closeToTray by dataStoreManager.getString(DesktopSettings.CLOSE_TO_TRAY).collectAsStateWithLifecycle(null)
                        SwitchRow(title = "Hide to tray on close", summary = "Keep playing in the background when the window is closed", checked = closeToTray != DataStoreManager.FALSE) {
                            scope.launch { dataStoreManager.putString(DesktopSettings.CLOSE_TO_TRAY, if (it) DataStoreManager.TRUE else DataStoreManager.FALSE) }
                        }
                    }
                }
                SettingsCategory.Playback -> {
                    if (hasSystemEqualizer()) {
                        SettingRow(title = "Equalizer", summary = "Adjust audio settings") {
                            if (!openSystemEqualizer()) viewModel.makeToast("No equalizer found on this device")
                        }
                    }
                    SwitchRow(
                        title = "Autoplay",
                        summary = "Add similar content for endless listening",
                        checked = autoplay,
                    ) { enabled -> scope.launch { dataStoreManager.setEndlessQueue(enabled) } }
                }
                SettingsCategory.DataSaving -> {
                    SettingRow(title = "Audio quality", summary = quality.orEmpty()) {
                        val options = QUALITY.items.map { it.toString() }
                        choice = ChoiceDialog("Audio quality", options, options.indexOf(quality)) { viewModel.changeQuality(options[it]) }
                    }
                    SwitchRow(
                        title = "Don't play music videos",
                        summary = "Switch music videos to audio-only or album versions of songs",
                        checked = true,
                        enabled = false,
                    ) {}
                }
                SettingsCategory.Downloads -> {
                    SettingRow(title = "Audio quality", summary = downloadQuality.orEmpty()) {
                        val options = QUALITY.items.map { it.toString() }
                        choice =
                            ChoiceDialog("Download audio quality", options, options.indexOf(downloadQuality)) {
                                viewModel.setDownloadQuality(options[it])
                            }
                    }
                    SettingRow(title = "Downloads", summary = "Using ${formatBytes(downloadedSize)}")
                    SettingRow(title = "Clear downloads", summary = "Remove every downloaded song from this device") {
                        confirm = ConfirmDialog("Clear downloads?", "Every downloaded song will be removed from this device.") { viewModel.clearDownloadedCache() }
                    }
                    SettingRow(title = "Cache", summary = "Using ${formatBytes(cacheSize)}")
                    SettingRow(title = "Clear cache", summary = "Free up space used by songs played recently") {
                        confirm = ConfirmDialog("Clear cache?", "Recently played songs will need to load again.") {
                            viewModel.clearPlayerCache()
                            viewModel.clearThumbnailCache(platformContext)
                        }
                    }
                }
                SettingsCategory.Privacy -> {
                    SwitchRow(
                        title = "Pause watch history",
                        summary = "Songs you play won't be saved to your YouTube history or used for recommendations",
                        checked = sendBackToGoogle == DataStoreManager.FALSE,
                    ) { paused -> viewModel.setSendBackToGoogle(!paused) }
                    SettingRow(title = "Clear listening history on this device", summary = "Your YouTube history is not affected") {
                        confirm = ConfirmDialog("Clear listening history?", "Play counts and recently played songs on this device will be removed.") {
                            viewModel.clearListeningHistory()
                        }
                    }
                    SettingRow(title = "Manage all history", summary = "music.youtube.com › History") {
                        openUrl("https://myactivity.google.com/product/youtube")
                    }
                }
                SettingsCategory.About -> {
                    SettingRow(title = "App version", summary = VersionManager.getVersionName())
                    SettingRow(title = "Open source licenses", summary = "License details for open source software") { showLicenses = true }
                    SettingRow(title = "Source code", summary = "github.com/CLPow/refine_simpmusic") {
                        openUrl("https://github.com/CLPow/refine_simpmusic")
                    }
                }
            }
        }
    }

    choice?.let { dialog ->
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { choice = null },
            title = { Text(dialog.title, color = Color.White) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(dialog.options.size) { index ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                dialog.onSelect(index)
                                choice = null
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == dialog.selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Ytm.TextSecondary),
                                modifier = Modifier.padding(12.dp),
                            )
                            Text(dialog.options[index], style = typo().bodyLarge, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choice = null }) { Text("Cancel", color = Color.White) } },
        )
    }
    confirm?.let { dialog ->
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { confirm = null },
            title = { Text(dialog.title, color = Color.White) },
            text = { Text(dialog.message, color = Ytm.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    dialog.onConfirm()
                    confirm = null
                }) { Text("Clear", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Ytm.TextSecondary) } },
        )
    }
    if (showLicenses) {
        val libraries by produceLibraries { Res.readBytes("files/aboutlibraries.json").decodeToString() }
        ModalBottomSheet(
            onDismissRequest = { showLicenses = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Ytm.Surface,
        ) {
            LibrariesContainer(libraries, Modifier.fillMaxSize())
        }
    }
}

private class ChoiceDialog(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

private class ConfirmDialog(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

/** A YouTube Music settings row: title, grey summary, the whole row tappable. */
@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = typo().bodyLarge.copy(fontSize = 16.sp), color = Color.White)
        if (!summary.isNullOrBlank()) {
            Text(summary, style = typo().bodyMedium, color = Ytm.TextSecondary)
        }
    }
}

/** A settings row with a switch on the right. */
@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = typo().bodyLarge.copy(fontSize = 16.sp), color = if (enabled) Color.White else Ytm.TextSecondary)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = typo().bodyMedium, color = Ytm.TextSecondary)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Ytm.TextSecondary,
                    uncheckedTrackColor = Ytm.Raised,
                ),
        )
    }
}

private fun formatBytes(bytes: Long?): String {
    val b = bytes ?: return "…"
    val mb = b / (1024.0 * 1024.0)
    return if (mb >= 1024) "${(mb / 1024 * 10).toLong() / 10.0} GB" else "${mb.toLong()} MB"
}
