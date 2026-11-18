package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.simpmusic.media_jvm.mpv.MpvPlayerAdapter
import com.simpmusic.media_jvm.mpv.MpvVideoFrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
actual fun VideoSurface(modifier: Modifier) {
    val adapter = koinInject<MediaPlayerInterface>() as? MpvPlayerAdapter
    val source = adapter?.currentVideoFrames?.collectAsState()?.value
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null) {
            VideoFrames(source = source, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun VideoFrames(
    source: MpvVideoFrameSource,
    modifier: Modifier,
) {
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        withContext(Dispatchers.Default) {
            source.frames.collect { image ->
                frame = image?.toComposeImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier.onSizeChanged { source.setTargetSize(it.width, it.height) },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
