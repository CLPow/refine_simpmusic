package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No-op: the platform already shows a transient indicator while scrolling. */
@Composable
actual fun HorizontalLazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) = Unit
