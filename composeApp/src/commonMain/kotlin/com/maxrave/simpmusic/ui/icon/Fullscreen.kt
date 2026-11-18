package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SimpIcons.Fullscreen: ImageVector
    get() =
        _Fullscreen ?: ImageVector
            .Builder(name = "Fullscreen", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(7f, 14f)
                    horizontalLineTo(5f)
                    verticalLineToRelative(5f)
                    horizontalLineToRelative(5f)
                    verticalLineToRelative(-2f)
                    horizontalLineTo(7f)
                    close()
                    moveTo(5f, 10f)
                    horizontalLineToRelative(2f)
                    verticalLineTo(7f)
                    horizontalLineToRelative(3f)
                    verticalLineTo(5f)
                    horizontalLineTo(5f)
                    close()
                    moveTo(17f, 17f)
                    horizontalLineToRelative(-3f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(5f)
                    verticalLineToRelative(-5f)
                    horizontalLineToRelative(-2f)
                    close()
                    moveTo(14f, 5f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(3f)
                    horizontalLineToRelative(2f)
                    verticalLineTo(5f)
                    close()
                }
            }.build()
            .also { _Fullscreen = it }

val SimpIcons.FullscreenExit: ImageVector
    get() =
        _FullscreenExit ?: ImageVector
            .Builder(name = "FullscreenExit", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(5f, 16f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(3f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-5f)
                    horizontalLineTo(5f)
                    close()
                    moveTo(8f, 8f)
                    horizontalLineTo(5f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(5f)
                    verticalLineTo(5f)
                    horizontalLineTo(8f)
                    close()
                    moveTo(14f, 19f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-3f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-5f)
                    close()
                    moveTo(16f, 8f)
                    verticalLineTo(5f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(5f)
                    horizontalLineToRelative(5f)
                    verticalLineTo(8f)
                    close()
                }
            }.build()
            .also { _FullscreenExit = it }

private var _Fullscreen: ImageVector? = null
private var _FullscreenExit: ImageVector? = null
