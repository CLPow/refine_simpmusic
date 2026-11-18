package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun materialIcon(
    name: String,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        .build()

private val bookmark by lazy {
    materialIcon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
}

private val bookmarkBorder by lazy {
    materialIcon("BookmarkBorder", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
}

private val watchLater by lazy {
    materialIcon(
        "WatchLater",
        "M12,2C6.5,2 2,6.5 2,12s4.5,10 10,10 10,-4.5 10,-10S17.5,2 12,2zM16.2,16.2L11,13V7h1.5v5.2l4.5,2.7 -0.8,1.3z",
    )
}

private val podcasts by lazy {
    materialIcon(
        "Podcasts",
        "M14,12c0,0.74 -0.4,1.38 -1,1.72V22h-2v-8.28c-0.6,-0.35 -1,-0.98 -1,-1.72c0,-1.1 0.9,-2 2,-2S14,10.9 14,12zM12,6c-3.31,0 -6,2.69 -6,6c0,1.74 0.75,3.31 1.94,4.4l1.42,-1.42C8.53,14.25 8,13.19 8,12c0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.19 -0.53,2.25 -1.36,2.98l1.42,1.42C17.25,15.31 18,13.74 18,12C18,8.69 15.31,6 12,6zM12,2C6.48,2 2,6.48 2,12c0,2.85 1.2,5.41 3.11,7.24l1.42,-1.42C4.98,16.36 4,14.29 4,12c0,-4.41 3.59,-8 8,-8s8,3.59 8,8c0,2.29 -0.98,4.36 -2.53,5.82l1.42,1.42C20.8,17.41 22,14.85 22,12C22,6.48 17.52,2 12,2z",
    )
}

val SimpIcons.Bookmark: ImageVector get() = bookmark

val SimpIcons.BookmarkBorder: ImageVector get() = bookmarkBorder

val SimpIcons.WatchLater: ImageVector get() = watchLater

val SimpIcons.Podcasts: ImageVector get() = podcasts
