package org.simpmusic.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import com.maxrave.logger.Logger

// All functions are safe no-ops so callers never need to branch on build flavor.

fun initCast(context: Context): Boolean {
    Logger.d("Cast", "NON-CAST build: Google Cast is not available")
    return false
}

fun isCastAvailable(): Boolean = false

fun wrapWithCastPlayer(
    context: Context,
    localPlayer: Player,
): Player = localPlayer

fun currentCastDeviceName(): String? = null

@Composable
fun CastIconButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
}
