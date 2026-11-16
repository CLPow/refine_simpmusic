package com.maxrave.simpmusic.ui.screen.home

import simpmusic.composeapp.generated.resources.ytm_save
import simpmusic.composeapp.generated.resources.ytm_rc_forget_body
import simpmusic.composeapp.generated.resources.ytm_rc_forget_title
import simpmusic.composeapp.generated.resources.ytm_rc_forget
import simpmusic.composeapp.generated.resources.ytm_rc_not_available
import simpmusic.composeapp.generated.resources.ytm_rc_available
import simpmusic.composeapp.generated.resources.ytm_rc_paired_devices
import simpmusic.composeapp.generated.resources.ytm_rc_device_name
import simpmusic.composeapp.generated.resources.ytm_rc_summary
import simpmusic.composeapp.generated.resources.ytm_rc_title
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import com.maxrave.domain.repository.ControlDevice
import com.maxrave.domain.repository.RefineControlRepository
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.LocalPlatformContext
import com.maxrave.common.QUALITY
import com.maxrave.common.SUPPORTED_LOCATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.repository.PlaybackPingStatus
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.desktop.DesktopSettings
import com.maxrave.simpmusic.desktop.isStartOnBootEnabled
import com.maxrave.simpmusic.desktop.isStartOnBootSupported
import com.maxrave.simpmusic.desktop.setStartOnBoot
import com.maxrave.simpmusic.expect.openUrl
import com.maxrave.simpmusic.expect.readCrashLog
import com.maxrave.simpmusic.expect.shareUrl
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.checkForUpdatesText
import com.maxrave.simpmusic.ui.component.rememberAppUpdater
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsCategoryDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.utils.VersionManager
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.audio_quality
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.ytm_about
import simpmusic.composeapp.generated.resources.ytm_app_version
import simpmusic.composeapp.generated.resources.ytm_autoplay
import simpmusic.composeapp.generated.resources.ytm_autoplay_summary
import simpmusic.composeapp.generated.resources.ytm_audio_quality_wifi
import simpmusic.composeapp.generated.resources.ytm_audio_quality_mobile
import simpmusic.composeapp.generated.resources.ytm_double_tap_seek
import simpmusic.composeapp.generated.resources.ytm_n_seconds
import simpmusic.composeapp.generated.resources.ytm_allow_external_start
import simpmusic.composeapp.generated.resources.ytm_allow_external_start_summary
import simpmusic.composeapp.generated.resources.ytm_sponsorblock
import simpmusic.composeapp.generated.resources.ytm_sponsorblock_summary
import simpmusic.composeapp.generated.resources.ytm_stream_wifi_only
import simpmusic.composeapp.generated.resources.ytm_stream_wifi_only_summary
import simpmusic.composeapp.generated.resources.ytm_cache
import simpmusic.composeapp.generated.resources.ytm_clear
import simpmusic.composeapp.generated.resources.ytm_clear_cache
import simpmusic.composeapp.generated.resources.ytm_clear_cache_confirm
import simpmusic.composeapp.generated.resources.ytm_clear_cache_confirm_body
import simpmusic.composeapp.generated.resources.ytm_clear_cache_summary
import simpmusic.composeapp.generated.resources.ytm_clear_downloads
import simpmusic.composeapp.generated.resources.ytm_clear_downloads_confirm
import simpmusic.composeapp.generated.resources.ytm_clear_downloads_confirm_body
import simpmusic.composeapp.generated.resources.ytm_clear_downloads_summary
import simpmusic.composeapp.generated.resources.ytm_clear_history
import simpmusic.composeapp.generated.resources.ytm_clear_history_confirm
import simpmusic.composeapp.generated.resources.ytm_clear_history_confirm_body
import simpmusic.composeapp.generated.resources.ytm_clear_history_summary
import simpmusic.composeapp.generated.resources.ytm_close_to_tray
import simpmusic.composeapp.generated.resources.ytm_close_to_tray_summary
import simpmusic.composeapp.generated.resources.ytm_data_saving
import simpmusic.composeapp.generated.resources.ytm_download_audio_quality
import simpmusic.composeapp.generated.resources.ytm_downloads
import simpmusic.composeapp.generated.resources.ytm_downloads_storage
import simpmusic.composeapp.generated.resources.ytm_general
import simpmusic.composeapp.generated.resources.ytm_licenses
import simpmusic.composeapp.generated.resources.ytm_licenses_summary
import simpmusic.composeapp.generated.resources.ytm_location
import simpmusic.composeapp.generated.resources.ytm_manage_history
import simpmusic.composeapp.generated.resources.ytm_manage_history_summary
import simpmusic.composeapp.generated.resources.ytm_minutes_ago
import simpmusic.composeapp.generated.resources.ytm_hours_ago
import simpmusic.composeapp.generated.resources.ytm_pause_history
import simpmusic.composeapp.generated.resources.ytm_pause_history_summary
import simpmusic.composeapp.generated.resources.ytm_ping_accepted
import simpmusic.composeapp.generated.resources.ytm_ping_expired
import simpmusic.composeapp.generated.resources.ytm_ping_just_now
import simpmusic.composeapp.generated.resources.ytm_ping_none
import simpmusic.composeapp.generated.resources.ytm_ping_not_signed_in
import simpmusic.composeapp.generated.resources.ytm_ping_rejected
import simpmusic.composeapp.generated.resources.ytm_ping_unreachable
import simpmusic.composeapp.generated.resources.ytm_playback
import simpmusic.composeapp.generated.resources.ytm_privacy
import simpmusic.composeapp.generated.resources.ytm_restricted_mode
import simpmusic.composeapp.generated.resources.ytm_restricted_mode_summary
import simpmusic.composeapp.generated.resources.ytm_settings
import simpmusic.composeapp.generated.resources.ytm_share_crash_log
import simpmusic.composeapp.generated.resources.ytm_share_crash_log_summary
import simpmusic.composeapp.generated.resources.ytm_source_code
import simpmusic.composeapp.generated.resources.ytm_start_minimized
import simpmusic.composeapp.generated.resources.ytm_start_minimized_summary
import simpmusic.composeapp.generated.resources.ytm_start_on_boot
import simpmusic.composeapp.generated.resources.ytm_start_on_boot_summary
import simpmusic.composeapp.generated.resources.ytm_using_space
import simpmusic.composeapp.generated.resources.ytm_using_space_of
import kotlin.time.Clock

enum class SettingsCategory(
    val key: String,
    val title: StringResource,
) {
    General("general", Res.string.ytm_general),
    Playback("playback", Res.string.ytm_playback),
    DataSaving("data_saving", Res.string.ytm_data_saving),
    Downloads("downloads", Res.string.ytm_downloads_storage),
    Privacy("privacy", Res.string.ytm_privacy),
    About("about", Res.string.ytm_about),
    ;

    companion object {
        fun fromKey(key: String): SettingsCategory = entries.firstOrNull { it.key == key } ?: General
    }
}

@Composable
fun SettingsHomeScreen(
    navController: NavController,
    innerPadding: PaddingValues = PaddingValues(),
) {
    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = stringResource(Res.string.ytm_settings), onBack = { navController.navigateUp() }, showActions = false)
        LazyColumn(Modifier.fillMaxSize()) {
            items(SettingsCategory.entries) { category ->
                SettingRow(title = stringResource(category.title)) {
                    navController.navigate(SettingsCategoryDestination(category = category.key)) { launchSingleTop = true }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, coil3.annotation.ExperimentalCoilApi::class)
@Composable
fun SettingScreen(
    navController: NavController,
    innerPadding: PaddingValues = PaddingValues(),
    category: SettingsCategory,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    dataStoreManager: DataStoreManager = koinInject(),
    accountRepository: AccountRepository = koinInject(),
    refineControl: RefineControlRepository = koinInject(),
) {
    LaunchedEffect(Unit) { viewModel.getData() }
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val downloadQuality by viewModel.downloadQuality.collectAsStateWithLifecycle()
    val sendBackToGoogle by viewModel.sendBackToGoogle.collectAsStateWithLifecycle()
    val explicit by viewModel.explicitContentEnabled.collectAsStateWithLifecycle()
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    val downloadedSize by viewModel.downloadedCacheSize.collectAsStateWithLifecycle()
    val autoplay by dataStoreManager.endlessQueue.map { it == DataStoreManager.TRUE }.collectAsStateWithLifecycle(false)
    val qualityMobile by dataStoreManager.qualityMobile.map { QUALITY.normalize(it) }.collectAsStateWithLifecycle(null)
    val streamOverWifiOnly by dataStoreManager.streamOverWifiOnly.collectAsStateWithLifecycle(false)
    val doubleTapSeekSeconds by dataStoreManager.doubleTapSeekSeconds.collectAsStateWithLifecycle(10)
    val allowExternalStart by dataStoreManager.allowExternalStartPlayback.collectAsStateWithLifecycle(true)
    val sponsorBlock by dataStoreManager.sponsorBlockEnabled.map { it == DataStoreManager.TRUE }.collectAsStateWithLifecycle(false)
    val signInExpired by accountRepository.signInExpired.collectAsStateWithLifecycle()
    val refineControlOn by refineControl.enabled.collectAsStateWithLifecycle()
    val refineControlName by refineControl.deviceName.collectAsStateWithLifecycle()
    val refineControlDevices by refineControl.devices.collectAsStateWithLifecycle()
    var renameDevice by remember { mutableStateOf(false) }
    var forgetDevice by remember { mutableStateOf<ControlDevice?>(null) }
    var choice by remember { mutableStateOf<ChoiceDialog?>(null) }
    var confirm by remember { mutableStateOf<ConfirmDialog?>(null) }
    var showLicenses by remember { mutableStateOf(false) }

    val locationTitle = stringResource(Res.string.ytm_location)
    val audioQuality = stringResource(Res.string.audio_quality)
    val audioQualityWifi = stringResource(Res.string.ytm_audio_quality_wifi)
    val audioQualityMobile = stringResource(Res.string.ytm_audio_quality_mobile)
    val doubleTapSeekTitle = stringResource(Res.string.ytm_double_tap_seek)
    val downloadAudioQuality = stringResource(Res.string.ytm_download_audio_quality)
    val clearDownloadsConfirm = stringResource(Res.string.ytm_clear_downloads_confirm)
    val clearDownloadsConfirmBody = stringResource(Res.string.ytm_clear_downloads_confirm_body)
    val clearCacheConfirm = stringResource(Res.string.ytm_clear_cache_confirm)
    val clearCacheConfirmBody = stringResource(Res.string.ytm_clear_cache_confirm_body)
    val clearHistoryConfirm = stringResource(Res.string.ytm_clear_history_confirm)
    val clearHistoryConfirmBody = stringResource(Res.string.ytm_clear_history_confirm_body)
    val shareCrashLog = stringResource(Res.string.ytm_share_crash_log)

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = stringResource(category.title), onBack = { navController.navigateUp() }, showActions = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (category) {
                SettingsCategory.General -> {
                    SwitchRow(
                        title = stringResource(Res.string.ytm_restricted_mode),
                        summary = stringResource(Res.string.ytm_restricted_mode_summary),
                        checked = !explicit,
                    ) { viewModel.setExplicitContentEnabled(!it) }
                    if (refineControl.isSupported) {
                        SwitchRow(
                            title = stringResource(Res.string.ytm_rc_title),
                            summary = stringResource(Res.string.ytm_rc_summary),
                            checked = refineControlOn,
                        ) { refineControl.setEnabled(it) }
                        if (refineControlOn) {
                            SettingRow(title = stringResource(Res.string.ytm_rc_device_name), summary = refineControlName) { renameDevice = true }
                            refineControlDevices.filter { it.paired }.forEach { device ->
                                SettingRow(
                                    title = device.name,
                                    summary =
                                        stringResource(Res.string.ytm_rc_paired_devices) + " • " +
                                            stringResource(if (device.connected) Res.string.ytm_rc_available else Res.string.ytm_rc_not_available),
                                ) { forgetDevice = device }
                            }
                        }
                    }
                    SettingRow(title = locationTitle, summary = location.orEmpty()) {
                        val codes = SUPPORTED_LOCATION.items.map { it.toString() }
                        choice =
                            ChoiceDialog(locationTitle, codes, codes.indexOf(location)) { index ->
                                viewModel.changeLocation(codes[index])
                            }
                    }
                    if (getPlatform() == Platform.Desktop) {
                        if (isStartOnBootSupported()) {
                            var onBoot by remember { mutableStateOf(isStartOnBootEnabled()) }
                            SwitchRow(
                                title = stringResource(Res.string.ytm_start_on_boot),
                                summary = stringResource(Res.string.ytm_start_on_boot_summary),
                                checked = onBoot,
                            ) {
                                setStartOnBoot(it)
                                onBoot = isStartOnBootEnabled()
                            }
                        }
                        val startMinimized by dataStoreManager.getString(DesktopSettings.START_MINIMIZED).collectAsStateWithLifecycle(null)
                        SwitchRow(
                            title = stringResource(Res.string.ytm_start_minimized),
                            summary = stringResource(Res.string.ytm_start_minimized_summary),
                            checked = startMinimized == DataStoreManager.TRUE,
                        ) {
                            scope.launch { dataStoreManager.putString(DesktopSettings.START_MINIMIZED, if (it) DataStoreManager.TRUE else DataStoreManager.FALSE) }
                        }
                        val closeToTray by dataStoreManager.getString(DesktopSettings.CLOSE_TO_TRAY).collectAsStateWithLifecycle(null)
                        SwitchRow(
                            title = stringResource(Res.string.ytm_close_to_tray),
                            summary = stringResource(Res.string.ytm_close_to_tray_summary),
                            checked = closeToTray != DataStoreManager.FALSE,
                        ) {
                            scope.launch { dataStoreManager.putString(DesktopSettings.CLOSE_TO_TRAY, if (it) DataStoreManager.TRUE else DataStoreManager.FALSE) }
                        }
                    }
                }
                SettingsCategory.Playback -> {
                    SwitchRow(
                        title = stringResource(Res.string.ytm_autoplay),
                        summary = stringResource(Res.string.ytm_autoplay_summary),
                        checked = autoplay,
                    ) { enabled -> scope.launch { dataStoreManager.setEndlessQueue(enabled) } }
                    val seekSteps = listOf(5, 10, 15, 20, 30, 60)
                    val seekLabels = seekSteps.map { secondsLabel(it) }
                    SettingRow(title = doubleTapSeekTitle, summary = secondsLabel(doubleTapSeekSeconds)) {
                        choice =
                            ChoiceDialog(doubleTapSeekTitle, seekLabels, seekSteps.indexOf(doubleTapSeekSeconds)) { index ->
                                scope.launch { dataStoreManager.setDoubleTapSeekSeconds(seekSteps[index]) }
                            }
                    }
                    if (getPlatform() != Platform.Desktop) {
                        SwitchRow(
                            title = stringResource(Res.string.ytm_allow_external_start),
                            summary = stringResource(Res.string.ytm_allow_external_start_summary),
                            checked = allowExternalStart,
                        ) { enabled -> scope.launch { dataStoreManager.setAllowExternalStartPlayback(enabled) } }
                    }
                    SwitchRow(
                        title = stringResource(Res.string.ytm_sponsorblock),
                        summary = stringResource(Res.string.ytm_sponsorblock_summary),
                        checked = sponsorBlock,
                    ) { enabled -> scope.launch { dataStoreManager.setSponsorBlockEnabled(enabled) } }
                }
                SettingsCategory.DataSaving -> {
                    val isPhone = getPlatform() != Platform.Desktop
                    val wifiTitle = if (isPhone) audioQualityWifi else audioQuality
                    SettingRow(title = wifiTitle, summary = quality.orEmpty()) {
                        val options = QUALITY.items.map { it.toString() }
                        choice = ChoiceDialog(wifiTitle, options, options.indexOf(quality)) { viewModel.changeQuality(options[it]) }
                    }
                    if (isPhone) {
                        SettingRow(title = audioQualityMobile, summary = qualityMobile.orEmpty()) {
                            val options = QUALITY.items.map { it.toString() }
                            choice =
                                ChoiceDialog(audioQualityMobile, options, options.indexOf(qualityMobile)) {
                                    scope.launch { dataStoreManager.setQualityMobile(options[it]) }
                                }
                        }
                        SwitchRow(
                            title = stringResource(Res.string.ytm_stream_wifi_only),
                            summary = stringResource(Res.string.ytm_stream_wifi_only_summary),
                            checked = streamOverWifiOnly,
                        ) { enabled -> scope.launch { dataStoreManager.setStreamOverWifiOnly(enabled) } }
                    }
                }
                SettingsCategory.Downloads -> {
                    SettingRow(title = audioQuality, summary = downloadQuality.orEmpty()) {
                        val options = QUALITY.items.map { it.toString() }
                        choice =
                            ChoiceDialog(downloadAudioQuality, options, options.indexOf(downloadQuality)) {
                                viewModel.setDownloadQuality(options[it])
                            }
                    }
                    SettingRow(title = stringResource(Res.string.ytm_downloads), summary = stringResource(Res.string.ytm_using_space, formatBytes(downloadedSize)))
                    SettingRow(title = stringResource(Res.string.ytm_clear_downloads), summary = stringResource(Res.string.ytm_clear_downloads_summary)) {
                        confirm = ConfirmDialog(clearDownloadsConfirm, clearDownloadsConfirmBody) { viewModel.clearDownloadedCache() }
                    }
                    SettingRow(
                        title = stringResource(Res.string.ytm_cache),
                        summary =
                            if (getPlatform() == Platform.Android) {
                                stringResource(Res.string.ytm_using_space_of, formatBytes(cacheSize), formatBytes(SONG_CACHE_BUDGET_BYTES))
                            } else {
                                stringResource(Res.string.ytm_using_space, formatBytes(cacheSize))
                            },
                    )
                    SettingRow(title = stringResource(Res.string.ytm_clear_cache), summary = stringResource(Res.string.ytm_clear_cache_summary)) {
                        confirm =
                            ConfirmDialog(clearCacheConfirm, clearCacheConfirmBody) {
                                viewModel.clearPlayerCache()
                                viewModel.clearThumbnailCache(platformContext)
                            }
                    }
                }
                SettingsCategory.Privacy -> {
                    SwitchRow(
                        title = stringResource(Res.string.ytm_pause_history),
                        summary = stringResource(Res.string.ytm_pause_history_summary),
                        checked = sendBackToGoogle == DataStoreManager.FALSE,
                    ) { paused -> viewModel.setSendBackToGoogle(!paused) }
                    if (sendBackToGoogle != DataStoreManager.FALSE) {
                        val ping by produceState<PlaybackPingStatus?>(null) {
                            while (true) {
                                value = sharedViewModel.lastPlaybackPingStatus()
                                delay(5_000)
                            }
                        }
                        Text(
                            pingStatusText(ping, signInExpired),
                            style = typo().bodySmall,
                            color = Ytm.TextSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                    SettingRow(title = stringResource(Res.string.ytm_clear_history), summary = stringResource(Res.string.ytm_clear_history_summary)) {
                        confirm = ConfirmDialog(clearHistoryConfirm, clearHistoryConfirmBody) { viewModel.clearListeningHistory() }
                    }
                    SettingRow(title = stringResource(Res.string.ytm_manage_history), summary = stringResource(Res.string.ytm_manage_history_summary)) {
                        openUrl("https://myactivity.google.com/product/youtube_music")
                    }
                }
                SettingsCategory.About -> {
                    SettingRow(title = stringResource(Res.string.ytm_app_version), summary = VersionManager.getVersionName())
                    rememberAppUpdater()?.let { updater ->
                        val (title, summary) = checkForUpdatesText(updater)
                        SettingRow(title = title, summary = summary) { updater.checkNow() }
                    }
                    SettingRow(title = stringResource(Res.string.ytm_licenses), summary = stringResource(Res.string.ytm_licenses_summary)) { showLicenses = true }
                    SettingRow(title = stringResource(Res.string.ytm_source_code), summary = "github.com/CLPow/refine_simpmusic") {
                        openUrl("https://github.com/CLPow/refine_simpmusic")
                    }
                    val crashLog = remember { readCrashLog() }
                    if (!crashLog.isNullOrBlank()) {
                        SettingRow(title = shareCrashLog, summary = stringResource(Res.string.ytm_share_crash_log_summary)) {
                            shareUrl(shareCrashLog, crashLog)
                        }
                    }
                }
            }
        }
    }

    if (renameDevice) {
        var name by remember { mutableStateOf(refineControlName) }
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { renameDevice = false },
            title = { Text(stringResource(Res.string.ytm_rc_device_name), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Ytm.TextSecondary,
                        ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    refineControl.setDeviceName(name)
                    renameDevice = false
                }) { Text(stringResource(Res.string.ytm_save), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { renameDevice = false }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
        )
    }
    forgetDevice?.let { device ->
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { forgetDevice = null },
            title = { Text(stringResource(Res.string.ytm_rc_forget_title, device.name), color = Color.White) },
            text = { Text(stringResource(Res.string.ytm_rc_forget_body), color = Ytm.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    refineControl.forget(device.id)
                    forgetDevice = null
                }) { Text(stringResource(Res.string.ytm_rc_forget), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { forgetDevice = null }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
        )
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
                            Modifier.fillMaxWidth().selectable(selected = index == dialog.selected, role = Role.RadioButton) {
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
            confirmButton = { TextButton(onClick = { choice = null }) { Text(stringResource(Res.string.cancel), color = Color.White) } },
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
                }) { Text(stringResource(Res.string.ytm_clear), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) } },
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

@Composable
private fun pingStatusText(
    ping: PlaybackPingStatus?,
    signInExpired: Boolean,
): String =
    when {
        signInExpired || ping?.signInExpired == true -> stringResource(Res.string.ytm_ping_expired)
        ping == null -> stringResource(Res.string.ytm_ping_none)
        ping.notSignedIn -> stringResource(Res.string.ytm_ping_not_signed_in)
        ping.accepted -> {
            val minutes = ((Clock.System.now().toEpochMilliseconds() - ping.acceptedAt) / 60_000).toInt()
            val ago =
                when {
                    minutes < 1 -> stringResource(Res.string.ytm_ping_just_now)
                    minutes < 60 -> pluralStringResource(Res.plurals.ytm_minutes_ago, minutes, minutes)
                    else -> pluralStringResource(Res.plurals.ytm_hours_ago, minutes / 60, minutes / 60)
                }
            stringResource(Res.string.ytm_ping_accepted, ago)
        }
        ping.code == 0 -> stringResource(Res.string.ytm_ping_unreachable)
        else -> stringResource(Res.string.ytm_ping_rejected, ping.code)
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

@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = typo().bodyLarge.copy(fontSize = 16.sp), color = if (enabled) Color.White else Ytm.TextSecondary)
        if (!summary.isNullOrBlank()) {
            Text(summary, style = typo().bodyMedium, color = Ytm.TextSecondary)
        }
    }
}

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
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
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
            onCheckedChange = null,
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

private const val SONG_CACHE_BUDGET_BYTES = 1024L * 1024 * 1024

private fun formatBytes(bytes: Long?): String {
    val b = bytes ?: return "…"
    val mb = b / (1024.0 * 1024.0)
    return if (mb >= 1024) "${(mb / 1024 * 10).toLong() / 10.0} GB" else "${mb.toLong()} MB"
}

@Composable
private fun secondsLabel(seconds: Int): String = stringResource(Res.string.ytm_n_seconds, seconds)
