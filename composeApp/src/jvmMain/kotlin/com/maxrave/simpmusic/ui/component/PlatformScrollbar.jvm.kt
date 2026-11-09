package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Thickness of the thumb, and of the faint track behind it. */
private val THUMB_THICKNESS = 5.dp

/**
 * One scrollbar appearance for the whole desktop app.
 *
 * Compose's default is tuned for a light surface — a 12%-opacity *black* thumb, invisible against
 * this background — so it has to be replaced rather than nudged. Kept deliberately quiet: a
 * scrollbar is an affordance, not content, and at 5dp with a low-contrast thumb it reads as chrome
 * and stays out of the way until the pointer is near it.
 *
 * Provided through [LocalScrollbarStyle] at the composition root so every scrollbar in the app
 * inherits it, rather than each site inventing its own.
 */
internal val simpMusicScrollbarStyle: ScrollbarStyle =
    ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = THUMB_THICKNESS,
        shape = RoundedCornerShape(50),
        hoverDurationMillis = 300,
        unhoverColor = Color.White.copy(alpha = 0.16f),
        hoverColor = Color.White.copy(alpha = 0.32f),
    )

