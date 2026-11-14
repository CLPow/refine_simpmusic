package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val THUMB_THICKNESS = 5.dp

internal val simpMusicScrollbarStyle: ScrollbarStyle =
    ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = THUMB_THICKNESS,
        shape = RoundedCornerShape(50),
        hoverDurationMillis = 300,
        unhoverColor = Color.White.copy(alpha = 0.16f),
        hoverColor = Color.White.copy(alpha = 0.32f),
    )

