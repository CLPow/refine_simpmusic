package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun HorizontalLazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style =
            LocalScrollbarStyle.current.copy(
                // The default is tuned for a light surface and all but vanishes on this one.
                unhoverColor = Color.White.copy(alpha = 0.20f),
                hoverColor = Color.White.copy(alpha = 0.45f),
            ),
    )
}
