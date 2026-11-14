package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
expect fun PlatformCastButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
)

