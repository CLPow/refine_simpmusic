package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

expect val isSongRecognitionSupported: Boolean

@Composable
expect fun SongRecognitionSheet(
    onFind: (String) -> Unit,
    onDismiss: () -> Unit,
)
