package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

actual val isSongRecognitionSupported: Boolean = false

// Desktop has no song recognition; the search bar never opens this.
@Composable
actual fun SongRecognitionSheet(
    onFind: (String) -> Unit,
    onDismiss: () -> Unit,
) {}
