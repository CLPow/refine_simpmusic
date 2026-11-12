package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.Computer
import com.maxrave.simpmusic.ui.icon.Devices
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Smartphone
import com.maxrave.simpmusic.ui.icon.VolumeUp
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.ytm_rc_available
import simpmusic.composeapp.generated.resources.ytm_rc_confirm
import simpmusic.composeapp.generated.resources.ytm_rc_connect
import simpmusic.composeapp.generated.resources.ytm_rc_control
import simpmusic.composeapp.generated.resources.ytm_rc_controlling
import simpmusic.composeapp.generated.resources.ytm_rc_devices
import simpmusic.composeapp.generated.resources.ytm_rc_disconnected
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address_hint
import simpmusic.composeapp.generated.resources.ytm_rc_enter_address_not_found
import simpmusic.composeapp.generated.resources.ytm_rc_firewall_hint
import simpmusic.composeapp.generated.resources.ytm_rc_looking
import simpmusic.composeapp.generated.resources.ytm_rc_not_available
import simpmusic.composeapp.generated.resources.ytm_rc_now_playing_here
import simpmusic.composeapp.generated.resources.ytm_rc_now_playing_on
import simpmusic.composeapp.generated.resources.ytm_rc_pair_body
import simpmusic.composeapp.generated.resources.ytm_rc_pair_title
import simpmusic.composeapp.generated.resources.ytm_rc_pair_waiting
import simpmusic.composeapp.generated.resources.ytm_rc_paired
import simpmusic.composeapp.generated.resources.ytm_rc_play_here
import simpmusic.composeapp.generated.resources.ytm_rc_play_on
import simpmusic.composeapp.generated.resources.ytm_rc_playing_on
import simpmusic.composeapp.generated.resources.ytm_rc_playing_title
import simpmusic.composeapp.generated.resources.ytm_rc_stop_controlling
import simpmusic.composeapp.generated.resources.ytm_rc_tap_to_pair
import simpmusic.composeapp.generated.resources.ytm_rc_this_device
import simpmusic.composeapp.generated.resources.ytm_rc_title
import simpmusic.composeapp.generated.resources.ytm_rc_transfer_failed
import simpmusic.composeapp.generated.resources.ytm_rc_unreachable
import simpmusic.composeapp.generated.resources.ytm_rc_volume

/**
 * The devices button of Refine Control, where Spotify has its "Connect" button. Drawn only while
 * Refine Control is switched on. White while another device is being controlled.
 */
@Composable
fun RefineControlButton(
    modifier: Modifier = Modifier,
    idleTint: Color = Color.White,
    control: RefineControlRepository = koinInject(),
) {
    val enabled by control.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val target by control.target.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            SimpIcons.Devices,
            contentDescription = stringResource(Res.string.ytm_rc_devices),
            tint = if (target is ControlTarget.Remote) Color.White else idleTint,
        )
    }
    if (open) RefineControlSheet(onDismiss = { open = false }, control = control)
}

/** "Playing on Desk" while another device is being controlled; nothing otherwise. */
@Composable
fun RefineControlPlayingOn(
    modifier: Modifier = Modifier,
    control: RefineControlRepository = koinInject(),
) {
    val target by control.target.collectAsStateWithLifecycle()
    val remote = target as? ControlTarget.Remote ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(SimpIcons.Devices, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(Res.string.ytm_rc_playing_on, remote.deviceName),
            style = typo().bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Put once at the root of the app: the pairing code dialog (it has to appear on the device that
 * did not ask, too) and the toasts.
 */
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
                        // Two groups of three read more easily across two screens.
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
    var expanded by remember { mutableStateOf<String?>(null) }
    var askAddress by remember { mutableStateOf(false) }
    val remoteTarget = target as? ControlTarget.Remote

    // Look harder for devices while the list is on screen.
    DisposableEffect(control) {
        control.setScanning(true)
        onDispose { control.setScanning(false) }
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
                stringResource(Res.string.ytm_rc_title),
                style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )

            // This device.
            DeviceRow(
                icon = if (getPlatform() == Platform.Desktop) SimpIcons.Computer else SimpIcons.Smartphone,
                name = deviceName,
                status = stringResource(Res.string.ytm_rc_this_device),
                selected = remoteTarget == null,
                dimmed = false,
                onClick = { if (remoteTarget != null) expanded = if (expanded == THIS_DEVICE) null else THIS_DEVICE },
            )
            if (expanded == THIS_DEVICE && remoteTarget != null) {
                ActionRow(stringResource(Res.string.ytm_rc_play_here)) {
                    scope.launch { control.transferHere(remoteTarget.deviceId) }
                    onDismiss()
                }
                ActionRow(stringResource(Res.string.ytm_rc_stop_controlling)) {
                    control.controlThisDevice()
                    onDismiss()
                }
            }

            devices.forEach { device ->
                val isTarget = remoteTarget?.deviceId == device.id
                DeviceRow(
                    icon = if (device.type == ControlDeviceType.PHONE) SimpIcons.Smartphone else SimpIcons.Computer,
                    name = device.name,
                    status = deviceStatus(device, isTarget),
                    selected = isTarget,
                    dimmed = device.paired && !device.connected,
                    onClick = {
                        when {
                            !device.paired -> control.startPairing(device.id)
                            device.connected || isTarget -> expanded = if (expanded == device.id) null else device.id
                            else -> Unit
                        }
                    },
                )
                if (expanded == device.id && device.paired) {
                    if (device.connected && !isTarget) {
                        // Switch streaming: what plays here carries on there.
                        ActionRow(stringResource(Res.string.ytm_rc_play_on, device.name)) {
                            scope.launch { control.transferTo(device.id) }
                            onDismiss()
                        }
                        ActionRow(stringResource(Res.string.ytm_rc_control, device.name)) {
                            control.controlRemote(device.id)
                            onDismiss()
                        }
                    }
                    if (device.playingTitle != null || isTarget) {
                        ActionRow(stringResource(Res.string.ytm_rc_play_here)) {
                            scope.launch { control.transferHere(device.id) }
                            onDismiss()
                        }
                    }
                    if (isTarget) {
                        ActionRow(stringResource(Res.string.ytm_rc_stop_controlling)) {
                            control.controlThisDevice()
                            onDismiss()
                        }
                    }
                }
            }

            if (devices.isEmpty()) {
                Text(
                    stringResource(Res.string.ytm_rc_looking),
                    style = typo().bodyMedium,
                    color = Ytm.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (getPlatform() == Platform.Desktop) {
                    Text(
                        stringResource(Res.string.ytm_rc_firewall_hint),
                        style = typo().bodyMedium,
                        color = Ytm.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    )
                }
            }

            // The volume of the device being controlled: a phone has no slider for it otherwise.
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
                        // Sent when the finger lifts: one command, not one per pixel.
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

            ActionRow(stringResource(Res.string.ytm_rc_enter_address), indent = false) { askAddress = true }
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
private fun deviceStatus(
    device: ControlDevice,
    isTarget: Boolean,
): String =
    when {
        !device.paired -> stringResource(Res.string.ytm_rc_tap_to_pair)
        !device.connected -> stringResource(Res.string.ytm_rc_not_available)
        device.playingTitle != null && device.isPlaying -> stringResource(Res.string.ytm_rc_playing_title, device.playingTitle.orEmpty())
        isTarget -> stringResource(Res.string.ytm_rc_controlling)
        else -> stringResource(Res.string.ytm_rc_available)
    }

@Composable
private fun DeviceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    status: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val color = if (dimmed) Ytm.TextSecondary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = typo().bodyLarge.copy(fontSize = 15.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = typo().bodyMedium, color = Ytm.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(SimpIcons.Check, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun ActionRow(
    label: String,
    indent: Boolean = true,
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
                .padding(start = if (indent) 56.dp else 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    )
}
