package com.maxrave.simpmusic.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.maxrave.simpmusic.ui.theme.LocalForceDarkText
import com.maxrave.simpmusic.ui.component.ytm.Ytm

@Immutable
data class SurfaceDarkColors(
    val container: Color,
    val handle: Color,
    val content: Color,
    val subtitle: Color,
    val disabled: Color,
)

@Composable
fun rememberSurfaceDarkColors(): SurfaceDarkColors =
    SurfaceDarkColors(
        container = Ytm.Surface,
        handle = Color(0xFF5A5A5A),
        content = Color.White,
        subtitle = Ytm.TextSecondary,
        disabled = Color(0xFF717171),
    )
