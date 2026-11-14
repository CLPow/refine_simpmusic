package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/** True where the search bar offers song search (Android). */
expect val isSongRecognitionSupported: Boolean

/**
 * YouTube Music's song search: listens through the microphone and names the song playing nearby.
 * Android only; the search bar offers it only where [isSongRecognitionSupported]. [onFind] gets
 * "title artist" to search for. Engine ported from upstream SimpMusic e1923eeb (from Metrolist).
 */
@Composable
expect fun SongRecognitionSheet(
    onFind: (String) -> Unit,
    onDismiss: () -> Unit,
)
