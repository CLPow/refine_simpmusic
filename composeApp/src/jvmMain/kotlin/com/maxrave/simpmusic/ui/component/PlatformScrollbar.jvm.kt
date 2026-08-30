package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Thickness of the thumb, and of the faint track behind it. */
private val THUMB_THICKNESS = 5.dp

/** Row the scrollbar occupies, leaving a little breathing room above and below the thumb. */
private val TRACK_HEIGHT = 10.dp

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

@Composable
actual fun HorizontalLazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) {
    // Nothing to scroll, nothing to draw. A track whose thumb spans its whole width invites a drag
    // that cannot move, which is a worse answer than an absent scrollbar: it says the shelf scrolls
    // and then refuses to.
    if (!state.canScrollForward && !state.canScrollBackward) return

    Box(
        modifier = modifier.height(TRACK_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        // A faint track, so the extent is visible before the pointer arrives — there is no
        // transient indicator on a desktop surface the way there is on touch.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .height(THUMB_THICKNESS)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(50)),
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.fillMaxWidth(),
            style = LocalScrollbarStyle.current,
        )
    }
}
