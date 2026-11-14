package org.simpmusic.cast

import android.view.ContextThemeWrapper
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.R as MediaRouterR
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastIconButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    if (!isCastAvailable()) return

    val context = LocalContext.current
    val tintArgb = tint.toArgb()

    val indicator =
        remember(context, tintArgb) {
            AppCompatResources
                .getDrawable(context, R.drawable.ic_music_cast)
                ?.mutate()
                ?.apply { setTint(tintArgb) }
        }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val themedContext = ContextThemeWrapper(context, MediaRouterR.style.Theme_MediaRouter)
            MediaRouteButton(themedContext).apply {
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
                setPadding(0, 0, 0, 0)
            }
        },
        update = { button ->
            button.setRemoteIndicatorDrawable(indicator)
        },
    )
}
