package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun materialIcon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

val SimpIcons.Devices: ImageVector
    get() =
        devices ?: materialIcon(
            "Devices",
            "M4,6h18V4H4c-1.1,0 -2,0.9 -2,2v11H0v3h14v-3H4V6z" +
                "M23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1z" +
                "M22,17h-4v-7h4v7z",
        ).also { devices = it }

val SimpIcons.Computer: ImageVector
    get() =
        computer ?: materialIcon(
            "Computer",
            "M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4z" +
                "M4,6h16v10H4V6z",
        ).also { computer = it }

val SimpIcons.Smartphone: ImageVector
    get() =
        smartphone ?: materialIcon(
            "Smartphone",
            "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99z" +
                "M17,19H7V5h10v14z",
        ).also { smartphone = it }

private var devices: ImageVector? = null
private var computer: ImageVector? = null
private var smartphone: ImageVector? = null
