package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.maxrave.simpmusic.expect.ui.toImageBitmap
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.ytm_back
import simpmusic.composeapp.generated.resources.ytm_pause
import simpmusic.composeapp.generated.resources.ytm_play
import simpmusic.composeapp.generated.resources.ytm_shuffle

private val Secondary = Ytm.TextSecondary

/**
 * The header of an album or playlist page as YouTube Music draws it on a phone: back and more on
 * a plain top row, a centred square cover, the title, the artist or owner, a meta line, then one
 * row of round actions around a large white play button.
 */
@Composable
fun YtmCollectionHeader(
    thumbnail: String?,
    title: String,
    subtitle: String?,
    meta: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
    onArtworkLoaded: ((ImageBitmap) -> Unit)? = null,
    leadingAction: @Composable (() -> Unit)? = null,
    secondAction: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMore) {
                Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalPlatformContext.current)
                    .data(thumbnail)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey(thumbnail)
                    // Shown at 240dp; the palette is built from this decode too.
                    .size(720)
                    .build(),
            placeholder = rememberHolderPainter(),
            error = rememberHolderPainter(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { onArtworkLoaded?.invoke(it.result.image.toImageBitmap()) },
            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = typo().titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = typo().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp)
                        .then(if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier),
            )
        }
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(meta, style = typo().bodyMedium, color = Secondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionSlot { leadingAction?.invoke() }
            ActionSlot { secondAction?.invoke() }
            val playLabel = stringResource(if (isPlaying) Res.string.ytm_pause else Res.string.ytm_play)
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(role = Role.Button, onClickLabel = playLabel, onClick = onPlay)
                    .semantics { contentDescription = playLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp),
                )
            }
            ActionSlot { YtmRoundAction(SimpIcons.Shuffle, onShuffle, label = stringResource(Res.string.ytm_shuffle)) }
            ActionSlot { YtmRoundAction(SimpIcons.MoreVert, onMore, label = stringResource(Res.string.more)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActionSlot(content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { content() }
}

/** A secondary round action of the header row: a translucent disc with a white glyph. */
@Composable
fun YtmRoundAction(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = Color.White,
    label: String? = null,
) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * YouTube Music's bar over a scrolled album or playlist: back, the title fading in once the
 * header has scrolled away, and more. Drawn over the list; invisible (and not hit-testable) at
 * the top, where the header carries its own back and more.
 */
@Composable
fun YtmCollectionTopBar(
    listState: LazyListState,
    title: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    val shown by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "collectionTopBar")
    if (alpha == 0f) return
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .background(Ytm.Background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(SimpIcons.ArrowBackIosNew, contentDescription = stringResource(Res.string.ytm_back), tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(
            title,
            style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMore) {
            Icon(SimpIcons.MoreVert, contentDescription = stringResource(Res.string.more), tint = Color.White)
        }
    }
}
