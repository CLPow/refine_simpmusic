package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.HorizontalScrollbar
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

private val TRACK_HEIGHT = 12.dp
private val THUMB_THICKNESS = 8.dp

@Composable
actual fun HorizontalLazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) {
    // Drawn on an explicit track rather than floating over the content. The stock style is a
    // near-transparent thumb with no track, tuned for a light surface — against this background it
    // is effectively invisible, which reads as "there is no scrollbar" rather than "the shelf does
    // not scroll". The track also gives the shelf a visible extent before the pointer arrives,
    // which is the entire point on a surface with no touch indicator.
    // Nothing to scroll, nothing to draw. A track with a thumb spanning its whole width invites a
    // drag that cannot move, which is a worse answer than an absent scrollbar: it says the shelf
    // scrolls and then refuses to.
    if (!state.canScrollForward && !state.canScrollBackward) return

    Box(
        modifier = modifier.height(TRACK_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .height(THUMB_THICKNESS)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)),
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.fillMaxWidth(),
            style =
                ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = THUMB_THICKNESS,
                    shape = RoundedCornerShape(50),
                    hoverDurationMillis = 300,
                    unhoverColor = Color.White.copy(alpha = 0.35f),
                    hoverColor = Color.White.copy(alpha = 0.60f),
                ),
        )
    }
}
