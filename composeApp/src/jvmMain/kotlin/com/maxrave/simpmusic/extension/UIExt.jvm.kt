package com.maxrave.simpmusic.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.maxrave.domain.data.model.ui.ScreenSizeInfo
import kotlin.math.roundToInt

object DesktopWindowChrome {
    const val TITLE_BAR_HEIGHT_DP = 32

    @Volatile
    var customTitleBarVisible: Boolean = false
}

@Composable
actual fun getScreenSizeInfo(): ScreenSizeInfo {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val chromeTopPx =
        if (DesktopWindowChrome.customTitleBarVisible) {
            with(density) { DesktopWindowChrome.TITLE_BAR_HEIGHT_DP.dp.roundToPx() }
        } else {
            0
        }
    val contentHeightPx = (window.containerSize.height - chromeTopPx).coerceAtLeast(0)
    return ScreenSizeInfo(
        hDP = with(density) {
            contentHeightPx.toDp().value.roundToInt()
        },
        wDP = with(density) {
            window.containerSize.width.toDp().value.roundToInt()
        },
        hPX = contentHeightPx,
        wPX = window.containerSize.width
    )
}

@Composable
actual fun KeepScreenOn() {
}

