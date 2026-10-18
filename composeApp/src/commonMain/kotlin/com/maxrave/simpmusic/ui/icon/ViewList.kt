package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
val SimpIcons.ViewList: ImageVector
  get() {
    if (_ViewList != null) {
      return _ViewList!!
    }
    _ViewList =
      ImageVector.Builder(
          name = "ViewList",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(3.75f, 5f)
            lineTo(5.25f, 5f)
            quadTo(6f, 5f, 6f, 5.75f)
            lineTo(6f, 7.25f)
            quadTo(6f, 8f, 5.25f, 8f)
            lineTo(3.75f, 8f)
            quadTo(3f, 8f, 3f, 7.25f)
            lineTo(3f, 5.75f)
            quadTo(3f, 5f, 3.75f, 5f)
            close()
            moveTo(8.75f, 5f)
            lineTo(20.25f, 5f)
            quadTo(21f, 5f, 21f, 5.75f)
            lineTo(21f, 7.25f)
            quadTo(21f, 8f, 20.25f, 8f)
            lineTo(8.75f, 8f)
            quadTo(8f, 8f, 8f, 7.25f)
            lineTo(8f, 5.75f)
            quadTo(8f, 5f, 8.75f, 5f)
            close()
            moveTo(3.75f, 10.5f)
            lineTo(5.25f, 10.5f)
            quadTo(6f, 10.5f, 6f, 11.25f)
            lineTo(6f, 12.75f)
            quadTo(6f, 13.5f, 5.25f, 13.5f)
            lineTo(3.75f, 13.5f)
            quadTo(3f, 13.5f, 3f, 12.75f)
            lineTo(3f, 11.25f)
            quadTo(3f, 10.5f, 3.75f, 10.5f)
            close()
            moveTo(8.75f, 10.5f)
            lineTo(20.25f, 10.5f)
            quadTo(21f, 10.5f, 21f, 11.25f)
            lineTo(21f, 12.75f)
            quadTo(21f, 13.5f, 20.25f, 13.5f)
            lineTo(8.75f, 13.5f)
            quadTo(8f, 13.5f, 8f, 12.75f)
            lineTo(8f, 11.25f)
            quadTo(8f, 10.5f, 8.75f, 10.5f)
            close()
            moveTo(3.75f, 16f)
            lineTo(5.25f, 16f)
            quadTo(6f, 16f, 6f, 16.75f)
            lineTo(6f, 18.25f)
            quadTo(6f, 19f, 5.25f, 19f)
            lineTo(3.75f, 19f)
            quadTo(3f, 19f, 3f, 18.25f)
            lineTo(3f, 16.75f)
            quadTo(3f, 16f, 3.75f, 16f)
            close()
            moveTo(8.75f, 16f)
            lineTo(20.25f, 16f)
            quadTo(21f, 16f, 21f, 16.75f)
            lineTo(21f, 18.25f)
            quadTo(21f, 19f, 20.25f, 19f)
            lineTo(8.75f, 19f)
            quadTo(8f, 19f, 8f, 18.25f)
            lineTo(8f, 16.75f)
            quadTo(8f, 16f, 8.75f, 16f)
            close()
          }
        }
        .build()
    return _ViewList!!
  }

private var _ViewList: ImageVector? = null
