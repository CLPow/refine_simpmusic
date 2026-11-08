package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.domain.audio.ENHANCE_EQ_BANDS_HZ
import com.maxrave.domain.audio.ENHANCE_EQ_MAX_DB
import com.maxrave.domain.audio.EnhanceSettings
import com.maxrave.domain.audio.EnhanceStatus
import com.maxrave.domain.audio.EnhanceStyle
import com.maxrave.domain.audio.OutputRoute
import com.maxrave.domain.audio.parseAutoEqFixedBand
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.expect.hasSystemEqualizer
import com.maxrave.simpmusic.expect.openSystemEqualizer
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.ytm_advanced
import simpmusic.composeapp.generated.resources.ytm_car_sound
import simpmusic.composeapp.generated.resources.ytm_car_sound_summary
import simpmusic.composeapp.generated.resources.ytm_crossfeed
import simpmusic.composeapp.generated.resources.ytm_crossfeed_summary
import simpmusic.composeapp.generated.resources.ytm_crossfeed_summary_desktop
import simpmusic.composeapp.generated.resources.ytm_custom_equalizer
import simpmusic.composeapp.generated.resources.ytm_custom_equalizer_custom
import simpmusic.composeapp.generated.resources.ytm_custom_equalizer_flat
import simpmusic.composeapp.generated.resources.ytm_done
import simpmusic.composeapp.generated.resources.ytm_enhance_audio
import simpmusic.composeapp.generated.resources.ytm_enhance_gated_hint
import simpmusic.composeapp.generated.resources.ytm_enhance_off_summary
import simpmusic.composeapp.generated.resources.ytm_enhance_on_summary
import simpmusic.composeapp.generated.resources.ytm_enhance_style
import simpmusic.composeapp.generated.resources.ytm_eq_import_autoeq
import simpmusic.composeapp.generated.resources.ytm_eq_import_failed
import simpmusic.composeapp.generated.resources.ytm_eq_import_hint
import simpmusic.composeapp.generated.resources.ytm_eq_reset
import simpmusic.composeapp.generated.resources.ytm_hold_to_compare
import simpmusic.composeapp.generated.resources.ytm_hold_to_compare_active
import simpmusic.composeapp.generated.resources.ytm_hold_to_compare_summary
import simpmusic.composeapp.generated.resources.ytm_import
import simpmusic.composeapp.generated.resources.ytm_low_volume_compensation
import simpmusic.composeapp.generated.resources.ytm_low_volume_compensation_summary
import simpmusic.composeapp.generated.resources.ytm_no_equalizer
import simpmusic.composeapp.generated.resources.ytm_normalize_volume
import simpmusic.composeapp.generated.resources.ytm_normalize_volume_summary
import simpmusic.composeapp.generated.resources.ytm_output
import simpmusic.composeapp.generated.resources.ytm_phone_equalizer
import simpmusic.composeapp.generated.resources.ytm_route_car
import simpmusic.composeapp.generated.resources.ytm_route_headphones
import simpmusic.composeapp.generated.resources.ytm_route_speaker
import simpmusic.composeapp.generated.resources.ytm_sound
import simpmusic.composeapp.generated.resources.ytm_stats_crossfeed
import simpmusic.composeapp.generated.resources.ytm_stats_custom_eq
import simpmusic.composeapp.generated.resources.ytm_stats_for_nerds
import simpmusic.composeapp.generated.resources.ytm_stats_itag
import simpmusic.composeapp.generated.resources.ytm_stats_leveller
import simpmusic.composeapp.generated.resources.ytm_stats_load
import simpmusic.composeapp.generated.resources.ytm_stats_low_volume
import simpmusic.composeapp.generated.resources.ytm_stats_nothing_playing
import simpmusic.composeapp.generated.resources.ytm_stats_off
import simpmusic.composeapp.generated.resources.ytm_stats_output
import simpmusic.composeapp.generated.resources.ytm_stats_peaks
import simpmusic.composeapp.generated.resources.ytm_stats_processing
import simpmusic.composeapp.generated.resources.ytm_stats_source
import simpmusic.composeapp.generated.resources.ytm_stats_unavailable
import simpmusic.composeapp.generated.resources.ytm_stats_volume
import simpmusic.composeapp.generated.resources.ytm_style_bass
import simpmusic.composeapp.generated.resources.ytm_style_bass_summary
import simpmusic.composeapp.generated.resources.ytm_style_natural
import simpmusic.composeapp.generated.resources.ytm_style_natural_summary
import simpmusic.composeapp.generated.resources.ytm_style_vocal
import simpmusic.composeapp.generated.resources.ytm_style_vocal_summary
import kotlin.math.abs
import kotlin.math.roundToInt

private val STYLE_NAMES: Map<EnhanceStyle, Pair<StringResource, StringResource>> =
    mapOf(
        EnhanceStyle.NATURAL to (Res.string.ytm_style_natural to Res.string.ytm_style_natural_summary),
        EnhanceStyle.BASS to (Res.string.ytm_style_bass to Res.string.ytm_style_bass_summary),
        EnhanceStyle.VOCAL to (Res.string.ytm_style_vocal to Res.string.ytm_style_vocal_summary),
    )

/**
 * Settings › Playback › Sound: "Enhance audio" and everything under it.
 *
 * The rows that only act through Enhance are visibly disabled while it is off, so nothing on the
 * page looks like it is doing something it is not. The phone's equalizer sits apart under Output:
 * it is the phone's own and works whether Enhance is on or not. The source line in Stats for nerds
 * is always what YouTube sent, kept apart from what the engine does to it.
 */
@Composable
fun SoundSettingsScreen(
    navController: NavController,
    innerPadding: PaddingValues = PaddingValues(),
    viewModel: SettingsViewModel = koinViewModel(),
    mediaPlayerHandler: MediaPlayerHandler = koinInject(),
) {
    val settings by viewModel.enhanceSettings.collectAsStateWithLifecycle()
    val desktop = getPlatform() == Platform.Desktop
    val on = settings.enabled
    var showStyle by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    val noEqualizer = stringResource(Res.string.ytm_no_equalizer)
    val styleName = stringResource(STYLE_NAMES.getValue(settings.style).first)

    // Never leave the comparison stuck on if the page goes away mid-press.
    DisposableEffect(Unit) { onDispose { mediaPlayerHandler.player.setEnhanceCompare(false) } }

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = stringResource(Res.string.ytm_sound), onBack = { navController.navigateUp() }, showActions = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SwitchRow(
                title = stringResource(Res.string.ytm_enhance_audio),
                summary = if (on) stringResource(Res.string.ytm_enhance_on_summary, styleName) else stringResource(Res.string.ytm_enhance_off_summary),
                checked = on,
            ) { checked -> viewModel.updateEnhance { it.copy(enabled = checked) } }
            if (!on) Hint(stringResource(Res.string.ytm_enhance_gated_hint))
            SettingRow(title = stringResource(Res.string.ytm_enhance_style), summary = styleName, enabled = on) { showStyle = true }
            SwitchRow(
                title = stringResource(Res.string.ytm_normalize_volume),
                summary = stringResource(Res.string.ytm_normalize_volume_summary),
                checked = settings.normalizeVolume,
                enabled = on,
            ) { checked -> viewModel.updateEnhance { it.copy(normalizeVolume = checked) } }
            // Desktop would rewrite mpv's filter chain on every press, which drops out; it returns
            // there once entries can be changed in place.
            if (on && !desktop) {
                HoldToCompareRow(comparing) { pressed ->
                    comparing = pressed
                    mediaPlayerHandler.player.setEnhanceCompare(pressed)
                }
            }

            SectionHeader(stringResource(Res.string.ytm_advanced))
            SettingRow(
                title = stringResource(Res.string.ytm_custom_equalizer),
                summary =
                    if (settings.eqBandsDb.all { it == 0f }) {
                        stringResource(Res.string.ytm_custom_equalizer_flat)
                    } else {
                        stringResource(Res.string.ytm_custom_equalizer_custom)
                    },
                enabled = on,
            ) { showEq = true }
            SwitchRow(
                title = stringResource(Res.string.ytm_crossfeed),
                summary = stringResource(if (desktop) Res.string.ytm_crossfeed_summary_desktop else Res.string.ytm_crossfeed_summary),
                checked = settings.crossfeed,
                enabled = on,
            ) { checked -> viewModel.updateEnhance { it.copy(crossfeed = checked) } }
            if (!desktop) {
                SwitchRow(
                    title = stringResource(Res.string.ytm_low_volume_compensation),
                    summary = stringResource(Res.string.ytm_low_volume_compensation_summary),
                    checked = settings.lowVolumeCompensation,
                    enabled = on,
                ) { checked -> viewModel.updateEnhance { it.copy(lowVolumeCompensation = checked) } }
                SwitchRow(
                    title = stringResource(Res.string.ytm_car_sound),
                    summary = stringResource(Res.string.ytm_car_sound_summary),
                    checked = settings.carSound,
                    enabled = on,
                ) { checked -> viewModel.updateEnhance { it.copy(carSound = checked) } }
            }

            if (hasSystemEqualizer()) {
                SectionHeader(stringResource(Res.string.ytm_output))
                SettingRow(title = stringResource(Res.string.ytm_phone_equalizer)) {
                    // From now on the player announces its audio sessions to the phone's equalizer.
                    if (!settings.phoneEqualizerLinked) viewModel.updateEnhance { it.copy(phoneEqualizerLinked = true) }
                    if (!openSystemEqualizer()) viewModel.makeToast(noEqualizer)
                }
            }

            SectionHeader(stringResource(Res.string.ytm_stats_for_nerds))
            StatsForNerds(mediaPlayerHandler, desktop, settings, styleName)
        }
    }

    if (showStyle) {
        AlertDialog(
            containerColor = Ytm.Surface,
            onDismissRequest = { showStyle = false },
            title = { Text(stringResource(Res.string.ytm_enhance_style), color = Color.White) },
            text = {
                Column {
                    EnhanceStyle.entries.forEach { style ->
                        val (name, summary) = STYLE_NAMES.getValue(style)
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = style == settings.style, role = Role.RadioButton) {
                                viewModel.updateEnhance { it.copy(style = style) }
                                showStyle = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = style == settings.style,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Ytm.TextSecondary),
                                modifier = Modifier.padding(12.dp),
                            )
                            Column {
                                Text(stringResource(name), style = typo().bodyLarge, color = Color.White)
                                Text(stringResource(summary), style = typo().bodySmall, color = Ytm.TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStyle = false }) { Text(stringResource(Res.string.cancel), color = Color.White) } },
        )
    }
    if (showEq) {
        EqualizerDialog(
            bands = settings.eqBandsDb,
            onChange = { bands -> viewModel.updateEnhance { it.copy(eqBandsDb = if (bands.all { b -> b == 0f }) emptyList() else bands) } },
            onDismiss = { showEq = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = typo().titleSmall.copy(fontSize = 14.sp),
        color = Ytm.TextSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = typo().bodySmall, color = Ytm.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/**
 * Press and hold for the original, level-matched, for as long as the finger or mouse button is down.
 *
 * A scroll that starts on the row is not a hold: the press only counts once it has stayed put.
 * Space or Enter does the same from a keyboard, and TalkBack gets a plain toggle (tap to hear the
 * original, tap again to return), with its state announced.
 */
@Composable
private fun HoldToCompareRow(
    comparing: Boolean,
    onPressedChange: (Boolean) -> Unit,
) {
    val title = stringResource(if (comparing) Res.string.ytm_hold_to_compare_active else Res.string.ytm_hold_to_compare)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (comparing) Ytm.Raised else Color.Transparent)
            .semantics {
                role = Role.Switch
                contentDescription = title
                stateDescription = title
                onClick {
                    onPressedChange(!comparing)
                    true
                }
            }.onKeyEvent { event ->
                if (event.key != Key.Spacebar && event.key != Key.Enter) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (!comparing) onPressedChange(true)
                    KeyEventType.KeyUp -> onPressedChange(false)
                }
                true
            }.focusable()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Start only if the pointer stays put for a moment: a scroll is consumed by the
                    // list first, which ends the wait early and never starts the comparison.
                    val endedEarly = withTimeoutOrNull(HOLD_DELAY_MS) { waitForUpOrCancellation() ?: Unit } != null
                    if (!endedEarly) {
                        onPressedChange(true)
                        waitForUpOrCancellation()
                        onPressedChange(false)
                    }
                }
            }.padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = typo().bodyLarge.copy(fontSize = 16.sp), color = Color.White)
        Text(stringResource(Res.string.ytm_hold_to_compare_summary), style = typo().bodyMedium, color = Ytm.TextSecondary)
    }
}

private const val HOLD_DELAY_MS = 150L

@Composable
private fun EqualizerDialog(
    bands: List<Float>,
    onChange: (List<Float>) -> Unit,
    onDismiss: () -> Unit,
) {
    var gains by remember { mutableStateOf(List(ENHANCE_EQ_BANDS_HZ.size) { bands.getOrElse(it) { 0f } }) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = Ytm.Surface,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (importing) Res.string.ytm_eq_import_autoeq else Res.string.ytm_custom_equalizer), color = Color.White) },
        text = {
            if (importing) {
                Column {
                    Text(stringResource(Res.string.ytm_eq_import_hint), style = typo().bodyMedium, color = Ytm.TextSecondary)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = false
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 8.dp),
                        textStyle = typo().bodySmall.copy(color = Color.White),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Ytm.TextSecondary,
                                cursorColor = Color.White,
                            ),
                    )
                    if (importError) {
                        Text(stringResource(Res.string.ytm_eq_import_failed), style = typo().bodySmall, color = Color(0xFFFF8A80))
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ENHANCE_EQ_BANDS_HZ.forEachIndexed { index, hz ->
                        val label = bandLabel(hz)
                        val value = gainLabel(gains[index])
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = typo().bodySmall, color = Ytm.TextSecondary, modifier = Modifier.width(56.dp))
                            Slider(
                                value = gains[index],
                                onValueChange = { v -> gains = gains.toMutableList().also { it[index] = (v * 2).roundToInt() / 2f } },
                                onValueChangeFinished = { onChange(gains) },
                                valueRange = -ENHANCE_EQ_MAX_DB..ENHANCE_EQ_MAX_DB,
                                // 0.5 dB steps across ±12 dB.
                                steps = (ENHANCE_EQ_MAX_DB * 4).toInt() - 1,
                                modifier =
                                    Modifier.weight(1f).semantics {
                                        contentDescription = label
                                        stateDescription = value
                                    },
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White,
                                        inactiveTrackColor = Ytm.Raised,
                                        activeTickColor = Color.Transparent,
                                        inactiveTickColor = Color.Transparent,
                                    ),
                            )
                            Text(value, style = typo().bodySmall, color = Color.White, modifier = Modifier.width(56.dp).padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (importing) {
                TextButton(onClick = {
                    val parsed = parseAutoEqFixedBand(importText)
                    if (parsed == null) {
                        importError = true
                    } else {
                        gains = parsed
                        onChange(parsed)
                        importing = false
                    }
                }) { Text(stringResource(Res.string.ytm_import), color = Color.White) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ytm_done), color = Color.White) }
            }
        },
        dismissButton = {
            Row {
                if (importing) {
                    TextButton(onClick = { importing = false }) { Text(stringResource(Res.string.cancel), color = Ytm.TextSecondary) }
                } else {
                    TextButton(onClick = {
                        gains = List(ENHANCE_EQ_BANDS_HZ.size) { 0f }
                        onChange(gains)
                    }) { Text(stringResource(Res.string.ytm_eq_reset), color = Ytm.TextSecondary) }
                    TextButton(onClick = { importing = true }) { Text(stringResource(Res.string.ytm_eq_import_autoeq), color = Ytm.TextSecondary) }
                }
            }
        },
    )
}

/** Live read-out, refreshed every second while the page is open. */
@Composable
private fun StatsForNerds(
    mediaPlayerHandler: MediaPlayerHandler,
    desktop: Boolean,
    settings: EnhanceSettings,
    styleName: String,
) {
    var status by remember { mutableStateOf<EnhanceStatus?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            status = runCatching { mediaPlayerHandler.player.enhanceStatus() }.getOrNull()
            delay(1_000)
        }
    }
    val s = status
    val codec = s?.sourceCodec
    val source =
        if (s == null || codec == null) {
            stringResource(Res.string.ytm_stats_nothing_playing)
        } else {
            listOfNotNull(
                codec.removePrefix("x-").uppercase().let { if (it == "MP4A-LATM") "AAC" else it },
                s.sourceItag?.let { stringResource(Res.string.ytm_stats_itag, it) },
                s.sourceBitrateKbps?.let { "$it kbps" },
                s.sourceSampleRateHz?.let { rateLabel(it) },
            ).joinToString(" · ")
        }
    StatLine(stringResource(Res.string.ytm_stats_source), source)
    if (!desktop && s != null) {
        StatLine(
            stringResource(Res.string.ytm_stats_output),
            stringResource(
                when (s.route) {
                    OutputRoute.SPEAKER -> Res.string.ytm_route_speaker
                    OutputRoute.HEADPHONES -> Res.string.ytm_route_headphones
                    OutputRoute.CAR -> Res.string.ytm_route_car
                },
            ),
        )
    }
    val processing =
        when {
            s?.unavailable == true -> stringResource(Res.string.ytm_stats_unavailable)
            s == null || !s.active -> stringResource(Res.string.ytm_stats_off)
            else -> {
                val crossfeedOn = settings.crossfeed && (desktop || s.route == OutputRoute.HEADPHONES)
                listOfNotNull(
                    styleName,
                    if (settings.eqBandsDb.any { it != 0f }) stringResource(Res.string.ytm_stats_custom_eq) else null,
                    if (crossfeedOn) stringResource(Res.string.ytm_stats_crossfeed) else null,
                    s.normalizationDb.takeIf { it != 0f }?.let { stringResource(Res.string.ytm_stats_volume, db(it)) },
                    s.lowVolumeDb.takeIf { it > 0.05f }?.let { stringResource(Res.string.ytm_stats_low_volume, db(it)) },
                    s.levellerDb?.let { stringResource(Res.string.ytm_stats_leveller, db(it)) },
                    s.limiterReductionDb.takeIf { it < -0.05f }?.let { stringResource(Res.string.ytm_stats_peaks, db(it)) },
                    s.loadPercent.takeIf { it > 0f }?.let { stringResource(Res.string.ytm_stats_load, ((it * 10).roundToInt() / 10.0).toString()) },
                ).joinToString(" · ")
            }
        }
    StatLine(stringResource(Res.string.ytm_stats_processing), processing)
}

@Composable
private fun StatLine(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = typo().bodyMedium, color = Ytm.TextSecondary, modifier = Modifier.width(96.dp))
        Text(value, style = typo().bodyMedium, color = Color.White)
    }
}

private fun bandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"

private fun rateLabel(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 100 / 10.0} kHz"

private fun gainLabel(db: Float): String = (if (db > 0) "+" else "") + ((db * 10).roundToInt() / 10.0) + " dB"

private fun db(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return (if (rounded > 0) "+" else if (rounded < 0) "−" else "") + abs(rounded) + " dB"
}
