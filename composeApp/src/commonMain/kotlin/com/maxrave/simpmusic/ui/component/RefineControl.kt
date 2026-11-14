package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.repository.ControlDevice
import com.maxrave.domain.repository.ControlDeviceType
import com.maxrave.domain.repository.ControlNotice
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.repository.RemoteCommand
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.Computer
import com.maxrave.simpmusic.ui.icon.Devices
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Smartphone
import com.maxrave.simpmusic.ui.icon.VolumeUp
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.ytm_rc_available
import simpmusic.composeapp.generated.resources.ytm_rc_confirm
import simpmusic.composeapp.generated.resources.ytm_rc_connect
import simpmusic.composeapp.generated.resources.ytm_rc_devices
import simpmusic.composeapp.generated.resources.ytm_rc_disconnected
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address_hint
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address_not_found
import simpmusic.composeapp.generated.resources.ytm_rc_firewall_hint
import simpmusic.composeapp.generated.resources.ytm_rc_help
import simpmusic.composeapp.generated.resources.ytm_rc_listening_on
import simpmusic.composeapp.generated.resources.ytm_rc_looking
import simpmusic.composeapp.generated.resources.ytm_rc_not_available
import simpmusic.composeapp.generated.resources.ytm_rc_not_showing
import simpmusic.composeapp.generated.resources.ytm_rc_now_playing_here
import simpmusic.composeapp.generated.resources.ytm_rc_now_playing_on
import simpmusic.composeapp.generated.resources.ytm_rc_pair_body
import simpmusic.composeapp.generated.resources.ytm_rc_pair_title
import simpmusic.composeapp.generated.resources.ytm_rc_pair_waiting
import simpmusic.composeapp.generated.resources.ytm_rc_paired
import simpmusic.composeapp.generated.resources.ytm_rc_paused_title
import simpmusic.composeapp.generated.resources.ytm_rc_playing_on
import simpmusic.composeapp.generated.resources.ytm_rc_playing_title
import simpmusic.composeapp.generated.resources.ytm_rc_tap_to_pair
import simpmusic.composeapp.generated.resources.ytm_rc_this_computer
import simpmusic.composeapp.generated.resources.ytm_rc_this_phone
import simpmusic.composeapp.generated.resources.ytm_rc_transfer_failed
import simpmusic.composeapp.generated.resources.ytm_rc_unreachable
import simpmusic.composeapp.generated.resources.ytm_rc_volume

@Composable
fun RefineControlButton(
    modifier: Modifier = Modifier,
    idleTint: Color = Color.White,
    control: RefineControlRepository = koinInject(),
) {
    val enabled by control.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val target by control.target.collectAsStateWithLifecycle()
    val devices by control.devices.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val following = target is ControlTarget.Remote
    IconButton(onClick = { open = true }, modifier = modifier) {
        Box {
            Icon(
                SimpIcons.Devices,
                contentDescription = stringResource(Res.string.ytm_rc_devices),
                tint = if (following) YtmRed else idleTint,
            )
            if (!following && devices.any { it.paired && it.connected }) {
                Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(YtmRed, CircleShape))
            }
        }
    }
    if (open) RefineControlSheet(onDismiss = { open = false }, control = control)
}

@Composable
fun RefineControlPlayingOn(
    modifier: Modifier = Modifier,
    control: RefineControlRepository = koinInject(),
) {
    val target by control.target.collectAsStateWithLifecycle()
    val remote = target as? ControlTarget.Remote ?: return
    var open by remember { mutableStateOf(false) }
    if (open) RefineControlSheet(onDismiss = { open = false }, control = control)
    Row(
        modifier.clickable(role = Role.Button) { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SimpIcons.Devices, contentDescription = null, tint = YtmRed, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(Res.string.ytm_rc_playing_on, remote.deviceName),
            style = typo().bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = YtmRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RefineControlHost(
    onToast: (String) -> Unit,
    control: RefineControlRepository = koinInject(),
) {
    if (!control.isSupported) return
    LaunchedEffect(control) {
        control.notices.collect { notice ->
            onToast(
                when (notice) {
                    is ControlNotice.Unreachable -> getString(Res.string.ytm_rc_unreachable, notice.deviceName)
                    is ControlNotice.TransferFailed -> getString(Res.string.ytm_rc_transfer_failed, notice.deviceName)
                    is ControlNotice.NowPlayingOn -> getString(Res.string.ytm_rc_now_playing_on, notice.deviceName)
                    ControlNotice.NowPlayingHere -> getString(Res.string.ytm_rc_now_playing_here)
                    is ControlNotice.Paired -> getString(Res.string.ytm_rc_paired, notice.deviceName)
                    is ControlNotice.Disconnected -> getString(Res.string.ytm_rc_disconnected, notice.deviceName)
                },
            )
        }
    }
    val prompt by control.pairing.collectAsStateWithLifecycle()
    prompt?.let { pairing ->
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = {},
            title = { Text(stringResource(Res.string.ytm_rc_pair_title, pairing.deviceName), color = Color.White) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        pairing.code.chunked(3).joinToString(" "),
                        style = typo().titleLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                    Text(
                        stringResource(
                            if (pairing.waitingForOther) Res.string.ytm_rc_pair_waiting else Res.string.ytm_rc_pair_body,
                            pairing.deviceName,
                        ),
                        style = typo().bodyMedium,
                        color = Ytm.TextSecondary,
                    )
                }
            },
            confirmButton = {
                if (!pairing.waitingForOther) {
                    TextButton(onClick = control::confirmPairing) { Text(stringResource(Res.string.ytm_rc_confirm), color = Color.White) }
                }
            },
            dismissButton = {
                TextButton(onClick = control::cancelPairing) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefineControlSheet(
    onDismiss: () -> Unit,
    control: RefineControlRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val devices by control.devices.collectAsStateWithLifecycle()
    val target by control.target.collectAsStateWithLifecycle()
    val remote by control.remote.collectAsStateWithLifecycle()
    val deviceName by control.deviceName.collectAsStateWithLifecycle()
    val pairing by control.pairing.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingPair by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var askAddress by remember { mutableStateOf(false) }
    val remoteTarget = target as? ControlTarget.Remote
    val isDesktop = getPlatform() == Platform.Desktop

    DisposableEffect(control) {
        control.setScanning(true)
        onDispose { control.setScanning(false) }
    }

    fun choose(deviceId: String?) {
        if (busy != null) return
        busy = deviceId ?: THIS_DEVICE
        scope.launch {
            val done = control.listenOn(deviceId)
            busy = null
            if (done) onDismiss()
        }
    }

    LaunchedEffect(devices, pendingPair) {
        val id = pendingPair ?: return@LaunchedEffect
        if (devices.any { it.id == id && it.paired && it.connected }) {
            pendingPair = null
            choose(id)
        }
    }
    LaunchedEffect(pairing) {
        if (pairing == null && pendingPair != null) {
            delay(3_000)
            if (devices.none { it.id == pendingPair && it.paired }) pendingPair = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(Res.string.ytm_rc_listening_on),
                style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )

            DeviceRow(
                icon = if (isDesktop) SimpIcons.Computer else SimpIcons.Smartphone,
                name = stringResource(if (isDesktop) Res.string.ytm_rc_this_computer else Res.string.ytm_rc_this_phone),
                status = deviceName,
                active = remoteTarget == null,
                busy = busy == THIS_DEVICE,
                dimmed = false,
                onClick = { choose(null) },
            )

            devices
                .sortedWith(compareByDescending<ControlDevice> { it.paired }.thenByDescending { it.connected })
                .forEach { device ->
                    DeviceRow(
                        icon = if (device.type == ControlDeviceType.PHONE) SimpIcons.Smartphone else SimpIcons.Computer,
                        name = device.name,
                        status = deviceStatus(device),
                        active = remoteTarget?.deviceId == device.id,
                        busy = busy == device.id,
                        dimmed = device.paired && !device.connected,
                        onClick =
                            when {
                                !device.paired -> {
                                    {
                                        pendingPair = device.id
                                        control.startPairing(device.id)
                                    }
                                }
                                device.connected -> {
                                    { choose(device.id) }
                                }
                                else -> null
                            },
                    )
                }

            if (devices.isEmpty()) {
                Text(
                    stringResource(Res.string.ytm_rc_looking),
                    style = typo().bodyMedium,
                    color = Ytm.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            val mirrored = remote
            if (remoteTarget != null && mirrored != null && mirrored.connected) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SimpIcons.VolumeUp, contentDescription = stringResource(Res.string.ytm_rc_volume), tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = dragging ?: mirrored.volume,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { control.send(RemoteCommand.SetVolume(it)) }
                            dragging = null
                        },
                        colors =
                            SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                            ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                stringResource(Res.string.ytm_rc_not_showing),
                style = typo().bodyMedium,
                color = Ytm.TextSecondary,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showHelp = !showHelp }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (showHelp) {
                Text(
                    stringResource(Res.string.ytm_rc_help),
                    style = typo().bodyMedium,
                    color = Ytm.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
                if (isDesktop) {
                    Text(
                        stringResource(Res.string.ytm_rc_firewall_hint),
                        style = typo().bodyMedium,
                        color = Ytm.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                }
                ActionRow(stringResource(Res.string.ytm_rc_enter_address)) { askAddress = true }
            }
            Spacer(Modifier.height(16.dp).windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (askAddress) {
        var address by remember { mutableStateOf("") }
        var notFound by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { askAddress = false },
            title = { Text(stringResource(Res.string.ytm_rc_enter_address), color = Color.White) },
            text = {
                Column {
                    Text(stringResource(Res.string.ytm_rc_enter_address_hint), style = typo().bodyMedium, color = Ytm.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            notFound = false
                        },
                        singleLine = true,
                        placeholder = { Text("192.168.1.20", color = Ytm.TextSecondary) },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Ytm.TextSecondary,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (notFound) {
                        Text(
                            stringResource(Res.string.ytm_rc_enter_address_not_found),
                            style = typo().bodyMedium,
                            color = Ytm.TextSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = address.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        scope.launch {
                            val found = control.connectByAddress(address)
                            searching = false
                            if (found) askAddress = false else notFound = true
                        }
                    },
                ) { Text(stringResource(Res.string.ytm_rc_connect), color = if (address.isNotBlank() && !searching) Color.White else Ytm.TextSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { askAddress = false }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) }
            },
        )
    }
}

private const val THIS_DEVICE = "this-device"

@Composable
private fun deviceStatus(device: ControlDevice): String =
    when {
        !device.paired -> stringResource(Res.string.ytm_rc_tap_to_pair)
        !device.connected -> stringResource(Res.string.ytm_rc_not_available)
        device.playingTitle != null && device.isPlaying -> stringResource(Res.string.ytm_rc_playing_title, device.playingTitle.orEmpty())
        device.playingTitle != null -> stringResource(Res.string.ytm_rc_paused_title, device.playingTitle.orEmpty())
        else -> stringResource(Res.string.ytm_rc_available)
    }

@Composable
private fun DeviceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    status: String,
    active: Boolean,
    busy: Boolean,
    dimmed: Boolean,
    onClick: (() -> Unit)?,
) {
    val color =
        when {
            active -> YtmRed
            dimmed -> Ytm.TextSecondary
            else -> Color.White
        }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = typo().bodyLarge.copy(fontSize = 15.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            active -> Icon(SimpIcons.VolumeUp, contentDescription = null, tint = YtmRed)
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = typo().bodyLarge.copy(fontSize = 15.sp),
        color = Color.White,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
