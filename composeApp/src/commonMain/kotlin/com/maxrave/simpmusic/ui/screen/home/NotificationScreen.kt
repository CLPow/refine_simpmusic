package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.simpmusic.extension.formatTimeAgo
import com.maxrave.simpmusic.ui.component.AmbientThemeGlow
import com.maxrave.simpmusic.ui.component.CenterLoadingBox
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.RippleIconButton
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.component.rememberNowPlayingGlowTint
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.RssFeed
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.NotificationViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.new_release
import simpmusic.composeapp.generated.resources.no_notification
import simpmusic.composeapp.generated.resources.notification
import simpmusic.composeapp.generated.resources.singles
import simpmusic.composeapp.generated.resources.ytm_notifications
import simpmusic.composeapp.generated.resources.ytm_no_notifications

/**
 * YouTube Music's notifications: one row per new release from an artist you subscribe to — the
 * artist's picture, "New release from …", the release and how long ago, its cover on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    navController: NavController,
    viewModel: NotificationViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val listNotification by viewModel.listNotification.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = stringResource(Res.string.ytm_notifications), onBack = { navController.navigateUp() })
        val list = listNotification?.filter { it.type != NotificationEntity.TYPE_BLOG }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            list.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.ytm_no_notifications), style = typo().bodyLarge, color = Ytm.TextSecondary)
                }
            else ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    list.forEach { notification ->
                        val releases = notification.album.map { it to true } + notification.single.map { it to false }
                        items(releases) { (release, isAlbum) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val browseId = release["browseId"].orEmpty()
                                        if (browseId.isNotEmpty()) navController.navigate(AlbumDestination(browseId = browseId))
                                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Thumb(notification.thumbnail, 40.dp, CircleShape)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "New release from ${notification.name}: ${release["title"].orEmpty()}",
                                        style = typo().bodyLarge.copy(fontSize = 14.sp),
                                        color = Color.White,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOf(if (isAlbum) "Album" else "Single", notification.time.formatTimeAgo()).joinToString(" • "),
                                        style = typo().bodyMedium,
                                        color = Ytm.TextSecondary,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Thumb(release["thumbnails"], 56.dp, RoundedCornerShape(4.dp))
                            }
                        }
                    }
                }
        }
    }
}

@Composable
fun NotificationItem(
    notification: NotificationEntity,
    navController: NavController,
) {
    if (notification.type == NotificationEntity.TYPE_BLOG) {
        BlogNotificationItem(notification)
        return
    }
    Box(
        modifier =
            Modifier
                .padding(5.dp)
                .fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.clickable {
                    navController.navigate(
                        ArtistDestination(
                            channelId = notification.channelId,
                        ),
                    )
                },
            ) {
                val thumb = notification.thumbnail
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(thumb)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(thumb)
                            .crossfade(true)
                            .build(),
                    placeholder = rememberHolderPainter(),
                    error = rememberHolderPainter(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .align(Alignment.CenterVertically)
                            .size(50.dp)
                            .clip(
                                CircleShape,
                            ),
                )
                Spacer(modifier = Modifier.padding(5.dp))
                Column {
                    Text(text = stringResource(Res.string.new_release), style = typo().titleSmall)
                    Spacer(modifier = Modifier.padding(3.dp))
                    Text(text = notification.name, style = typo().headlineMedium)
                }
            }
            LazyRow(
                Modifier.padding(top = 15.dp),
            ) {
                items(notification.single) { single ->
                    ItemAlbumNotification(
                        isAlbum = false,
                        browseId = single["browseId"] ?: "",
                        title = single["title"] ?: "",
                        thumbnail = single["thumbnails"],
                        navController,
                    )
                }
                items(notification.album) { album ->
                    ItemAlbumNotification(
                        isAlbum = true,
                        browseId = album["browseId"] ?: "",
                        title = album["title"] ?: "",
                        thumbnail = album["thumbnails"],
                        navController = navController,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = notification.time.formatTimeAgo(),
            style = typo().titleSmall,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 15.dp),
        )
    }
}

@Composable
fun BlogNotificationItem(notification: NotificationEntity) {
    val uriHandler = LocalUriHandler.current
    val link = notification.link
    Box(
        modifier =
            Modifier
                .padding(5.dp)
                .fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !link.isNullOrEmpty()) {
                    link?.let { uriHandler.openUri(it) }
                },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Top)
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SimpIcons.RssFeed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(Modifier.padding(end = 56.dp)) {
                Text(text = "New blog post", style = typo().titleSmall)
                Spacer(modifier = Modifier.height(3.dp))
                Text(text = notification.name, style = typo().titleMedium)
                notification.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = typo().bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = notification.time.formatTimeAgo(),
            style = typo().titleSmall,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 15.dp),
        )
    }
}

@Composable
fun ItemAlbumNotification(
    isAlbum: Boolean,
    browseId: String,
    title: String,
    thumbnail: String?,
    navController: NavController,
) {
    Box(
        modifier =
            Modifier
                .clickable {
                    navController.navigate(
                        AlbumDestination(
                            browseId = browseId,
                        ),
                    )
                },
    ) {
        Column(
            Modifier.padding(5.dp),
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(thumbnail)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(thumbnail)
                        .crossfade(true)
                        .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(150.dp)
                        .clip(
                            RoundedCornerShape(10),
                        ),
            )
            Text(
                text = title,
                style = typo().titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier =
                    Modifier
                        .width(150.dp)
                        .wrapContentHeight(align = Alignment.CenterVertically)
                        .padding(top = 10.dp)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                        ).focusable(),
            )
            Text(
                text = if (isAlbum) stringResource(Res.string.album) else stringResource(Res.string.singles),
                style = typo().bodySmall,
                maxLines = 1,
                modifier =
                    Modifier
                        .width(150.dp)
                        .wrapContentHeight(align = Alignment.CenterVertically)
                        .padding(top = 10.dp)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                        ).focusable(),
            )
        }
    }
}