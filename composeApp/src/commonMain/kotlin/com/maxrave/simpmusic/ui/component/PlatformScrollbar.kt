package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A horizontal scrollbar for a lazy grid, on platforms that have one.
 *
 * Touch surfaces show a transient indicator while a fling is in progress and need nothing here, so
 * this is a no-op on Android and iOS. A desktop window has neither that indicator nor a swipe: a
 * horizontally paged shelf gives no sign that anything lies beyond the right edge, and a mouse
 * wheel scrolls the page rather than the shelf. Without a visible, draggable bar the remaining
 * items are simply unreachable.
 */
@Composable
expect fun HorizontalLazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
)
