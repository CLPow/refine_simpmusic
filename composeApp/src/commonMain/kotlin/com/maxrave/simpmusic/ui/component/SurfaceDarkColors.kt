package com.maxrave.simpmusic.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.maxrave.simpmusic.ui.theme.LocalForceDarkText
import com.maxrave.simpmusic.ui.component.ytm.Ytm

/**
 * Color set for overlay surfaces (bottom sheets & dialogs) that must **inherit force-dark** from the
 * screen that opened them.
 *
 * Compose renders sheets/dialogs in a Popup that keeps the parent composition's CompositionLocals, so
 * [LocalForceDarkText] flows in from the caller: opened from a force-dark screen (Artist/Album/Playlist/
 * LocalPlaylist/NowPlaying/FullscreenPlayer/Podcast) it stays the original dark look; opened from a
 * normal screen it follows the app theme (light on a light screen).
 *
 * Keep `Color.Transparent` and `scrimColor = Color.Black.copy(...)` as-is — the scrim is a dim over the
 * content behind the sheet and is dark in both themes.
 */
@Immutable
data class SurfaceDarkColors(
    val container: Color, // sheet/dialog background + inner cards (old #242424 / black)
    val handle: Color, // drag handle (old #474545)
    val content: Color, // primary text + icons (old white)
    val subtitle: Color, // secondary text (old #B0B0A0 / #CCCCCC / #D0D0C0)
    val disabled: Color, // disabled text/icons + dividers (old gray / #555555 / #3D3D3D)
)

@Composable
fun rememberSurfaceDarkColors(): SurfaceDarkColors =
    // YouTube Music's sheets and dialogs: #212121, white text, #AAAAAA secondary. The app is always
    // on the YouTube Music dark theme, so this holds whichever screen opened the sheet.
    SurfaceDarkColors(
        container = Ytm.Surface,
        handle = Color(0xFF5A5A5A),
        content = Color.White,
        subtitle = Ytm.TextSecondary,
        disabled = Color(0xFF717171),
    )
