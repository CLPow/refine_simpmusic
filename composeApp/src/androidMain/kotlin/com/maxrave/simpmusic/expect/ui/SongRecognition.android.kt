package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.recognition.Recognition
import com.maxrave.simpmusic.recognition.SongRecognizer
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.ytm_song_search_failed
import simpmusic.composeapp.generated.resources.ytm_song_search_identifying
import simpmusic.composeapp.generated.resources.ytm_song_search_listening
import simpmusic.composeapp.generated.resources.ytm_song_search_listening_hint
import simpmusic.composeapp.generated.resources.ytm_song_search_mic_blocked
import simpmusic.composeapp.generated.resources.ytm_song_search_mic_needed
import simpmusic.composeapp.generated.resources.ytm_song_search_no_match
import simpmusic.composeapp.generated.resources.ytm_song_search_open_settings
import simpmusic.composeapp.generated.resources.ytm_song_search_allow
import simpmusic.composeapp.generated.resources.ytm_song_search_results

actual val isSongRecognitionSupported: Boolean = true

@Composable
actual fun SongRecognitionSheet(
    onFind: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(hasMic()) }
    var micBlocked by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var recognition by remember { mutableStateOf<Recognition>(Recognition.Listening) }
    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            micBlocked = !granted
        }
    LaunchedEffect(Unit) {
        if (!micGranted && !asked) {
            asked = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(micGranted, attempt) {
        if (!micGranted) return@LaunchedEffect
        recognition = Recognition.Listening
        recognition = SongRecognizer.recognize(onIdentifying = { recognition = Recognition.Identifying })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Ytm.Background).statusBarsPadding()) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(SimpIcons.Close, contentDescription = null, tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val state = recognition
                when {
                    !micGranted -> {
                        Text(
                            stringResource(if (micBlocked) Res.string.ytm_song_search_mic_blocked else Res.string.ytm_song_search_mic_needed),
                            style = typo().bodyLarge,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        WhitePill(
                            stringResource(if (micBlocked) Res.string.ytm_song_search_open_settings else Res.string.ytm_song_search_allow),
                        ) {
                            if (micBlocked) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } else {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }

                    state is Recognition.Found -> {
                        AsyncImage(
                            model = state.coverArt,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)).background(Ytm.Raised),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(state.title, style = typo().titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, textAlign = TextAlign.Center)
                        Text(state.artist, style = typo().bodyLarge, color = Ytm.TextSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        WhitePill(stringResource(Res.string.ytm_song_search_results)) {
                            onDismiss()
                            onFind("${state.title} ${state.artist}")
                        }
                    }

                    state is Recognition.NoMatch || state is Recognition.Failed -> {
                        Text(
                            stringResource(if (state is Recognition.NoMatch) Res.string.ytm_song_search_no_match else Res.string.ytm_song_search_failed),
                            style = typo().bodyLarge,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        WhitePill(stringResource(Res.string.retry)) { attempt++ }
                    }

                    else -> {
                        Pulse(active = state is Recognition.Listening)
                        Spacer(Modifier.height(32.dp))
                        Text(
                            stringResource(if (state is Recognition.Listening) Res.string.ytm_song_search_listening else Res.string.ytm_song_search_identifying),
                            style = typo().titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.ytm_song_search_listening_hint),
                            style = typo().bodyMedium,
                            color = Ytm.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pulse(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "song-search")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.35f else 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).scale(scale).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        Box(Modifier.size(88.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(SimpIcons.GraphicEq, contentDescription = null, tint = Color.Black, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun WhitePill(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
        color = Color.Black,
        modifier = Modifier.clip(CircleShape).background(Color.White).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
    )
}
