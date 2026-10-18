package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * Intercepts the system back gesture while [enabled], so back closes an overlay (a search field,
 * a selection, a sheet's tab) before it leaves the page, as YouTube Music does.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)
