package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun materialIcon(
    name: String,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

private var _Comment: ImageVector? = null

val SimpIcons.Comment: ImageVector
    get() =
        _Comment ?: materialIcon(
            "Comment",
            "M21.99 4c0-1.1-.89-2-1.99-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4-.01-18zM18 14H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z",
        ).also { _Comment = it }

private var _Send: ImageVector? = null

val SimpIcons.Send: ImageVector
    get() = _Send ?: materialIcon("Send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z").also { _Send = it }
