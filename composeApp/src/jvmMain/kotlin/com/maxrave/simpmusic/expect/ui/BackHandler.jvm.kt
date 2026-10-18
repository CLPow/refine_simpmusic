package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/** Desktop has no system back; the on-screen back arrows do the same job there. */
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
}
