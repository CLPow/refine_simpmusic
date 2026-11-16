package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.simpmusic.update.UpdateVersion
import com.maxrave.simpmusic.utils.VersionManager
import simpmusic.composeapp.generated.resources.ytm_update_available_summary
import simpmusic.composeapp.generated.resources.ytm_update_latest_summary
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.theme.YtmRed
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.update.AppUpdater
import com.maxrave.simpmusic.update.UpdateFailure
import com.maxrave.simpmusic.update.UpdateState
import com.maxrave.simpmusic.update.createAppUpdater
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.ytm_update_allow_body
import simpmusic.composeapp.generated.resources.ytm_update_allow_title
import simpmusic.composeapp.generated.resources.ytm_update_available_body
import simpmusic.composeapp.generated.resources.ytm_update_available_title
import simpmusic.composeapp.generated.resources.ytm_update_check
import simpmusic.composeapp.generated.resources.ytm_update_check_failed
import simpmusic.composeapp.generated.resources.ytm_update_checked_days
import simpmusic.composeapp.generated.resources.ytm_update_checked_hours
import simpmusic.composeapp.generated.resources.ytm_update_checked_now
import simpmusic.composeapp.generated.resources.ytm_update_checking
import simpmusic.composeapp.generated.resources.ytm_update_download_failed
import simpmusic.composeapp.generated.resources.ytm_update_downloading
import simpmusic.composeapp.generated.resources.ytm_update_later
import simpmusic.composeapp.generated.resources.ytm_update_never_checked
import simpmusic.composeapp.generated.resources.ytm_update_not_signed
import simpmusic.composeapp.generated.resources.ytm_update_ok
import simpmusic.composeapp.generated.resources.ytm_update_retry
import simpmusic.composeapp.generated.resources.ytm_update_up_to_date
import simpmusic.composeapp.generated.resources.ytm_update_update

@Composable
fun rememberAppUpdater(): AppUpdater? = remember { createAppUpdater() }

@Composable
fun AppUpdateHost(onToast: (String) -> Unit) {
    val updater = rememberAppUpdater() ?: return
    LifecycleEventEffect(Lifecycle.Event.ON_START) { updater.checkOnStart() }
    val state by updater.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        when (val s = state) {
            UpdateState.UpToDate -> {
                onToast(getString(Res.string.ytm_update_up_to_date))
                updater.dismiss()
            }
            is UpdateState.Failed if s.reason == UpdateFailure.CHECK -> {
                onToast(getString(Res.string.ytm_update_check_failed))
                updater.dismiss()
            }
            else -> Unit
        }
    }

    when (val s = state) {
        is UpdateState.Available ->
            UpdateDialog(
                title = stringResource(Res.string.ytm_update_available_title),
                confirm = stringResource(Res.string.ytm_update_update) to updater::update,
                dismiss = stringResource(Res.string.ytm_update_later) to updater::dismiss,
            ) {
                Text(stringResource(Res.string.ytm_update_available_body, s.release.version), style = typo().bodyMedium, color = Color.White)
                if (s.release.notes.isNotBlank()) {
                    Text(
                        s.release.notes,
                        style = typo().bodySmall,
                        color = Ytm.TextSecondary,
                        modifier =
                            Modifier
                                .padding(top = 12.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                    )
                }
            }

        is UpdateState.Downloading ->
            UpdateDialog(
                title = stringResource(Res.string.ytm_update_downloading, s.release.version),
                confirm = null,
                dismiss = stringResource(Res.string.cancel) to updater::dismiss,
            ) {
                if (s.progress < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = YtmRed, trackColor = Ytm.Raised)
                } else {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        color = YtmRed,
                        trackColor = Ytm.Raised,
                    )
                    Text("${(s.progress * 100).toInt()}%", style = typo().bodySmall, color = Ytm.TextSecondary)
                }
            }

        is UpdateState.NeedsInstallPermission ->
            UpdateDialog(
                title = stringResource(Res.string.ytm_update_allow_title),
                confirm = stringResource(Res.string.ytm_update_update) to updater::update,
                dismiss = stringResource(Res.string.ytm_update_later) to updater::dismiss,
            ) {
                Text(stringResource(Res.string.ytm_update_allow_body), style = typo().bodyMedium, color = Ytm.TextSecondary)
            }

        is UpdateState.Failed if s.reason == UpdateFailure.DOWNLOAD ->
            UpdateDialog(
                title = stringResource(Res.string.ytm_update_download_failed),
                confirm = stringResource(Res.string.ytm_update_retry) to updater::update,
                dismiss = stringResource(Res.string.ytm_update_later) to updater::dismiss,
            ) {}

        is UpdateState.Failed if s.reason == UpdateFailure.NOT_SIGNED_BY_REFINE ->
            UpdateDialog(
                title = stringResource(Res.string.ytm_update_download_failed),
                confirm = stringResource(Res.string.ytm_update_ok) to updater::dismiss,
                dismiss = null,
            ) {
                Text(stringResource(Res.string.ytm_update_not_signed), style = typo().bodyMedium, color = Ytm.TextSecondary)
            }

        else -> Unit
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    confirm: Pair<String, () -> Unit>?,
    dismiss: Pair<String, () -> Unit>?,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        containerColor = Ytm.Surface,
        onDismissRequest = { dismiss?.second?.invoke() },
        title = { Text(title, color = Color.White) },
        text = { Column(Modifier.fillMaxWidth()) { content() } },
        confirmButton = {
            confirm?.let { (label, action) -> TextButton(onClick = action) { Text(label, color = Color.White) } }
        },
        dismissButton = {
            dismiss?.let { (label, action) -> TextButton(onClick = action) { Text(label, color = Ytm.TextSecondary) } }
        },
    )
}

@Composable
fun checkForUpdatesText(updater: AppUpdater): Pair<String, String> {
    val state by updater.state.collectAsStateWithLifecycle()
    val last by updater.lastCheckMillis.collectAsStateWithLifecycle()
    val latest by updater.latestVersion.collectAsStateWithLifecycle()
    val available = latest?.takeIf { UpdateVersion.isNewer(it, VersionManager.getVersionName()) }
    val checked =
        when {
            last == null -> null
            else -> {
                val minutes = ((System.currentTimeMillis() - last!!) / 60_000).coerceAtLeast(0)
                when {
                    minutes < 60 -> stringResource(Res.string.ytm_update_checked_now)
                    minutes < 48 * 60 -> stringResource(Res.string.ytm_update_checked_hours, (minutes / 60).toInt())
                    else -> stringResource(Res.string.ytm_update_checked_days, (minutes / (24 * 60)).toInt())
                }
            }
        }
    val summary =
        when {
            state == UpdateState.Checking -> stringResource(Res.string.ytm_update_checking)
            available != null -> stringResource(Res.string.ytm_update_available_summary, available)
            latest != null && checked != null -> stringResource(Res.string.ytm_update_latest_summary, checked)
            else -> checked ?: stringResource(Res.string.ytm_update_never_checked)
        }
    return stringResource(Res.string.ytm_update_check) to summary
}
