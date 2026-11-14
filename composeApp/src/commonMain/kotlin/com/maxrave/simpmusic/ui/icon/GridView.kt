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
val SimpIcons.GridView: ImageVector
  get() {
    if (_GridView != null) {
      return _GridView!!
    }
    _GridView =
      ImageVector.Builder(
          name = "GridView",
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
            moveTo(4.0f, 3f)
            lineTo(9.5f, 3f)
            quadTo(10.5f, 3f, 10.5f, 4.0f)
            lineTo(10.5f, 9.5f)
            quadTo(10.5f, 10.5f, 9.5f, 10.5f)
            lineTo(4.0f, 10.5f)
            quadTo(3f, 10.5f, 3f, 9.5f)
            lineTo(3f, 4.0f)
            quadTo(3f, 3f, 4.0f, 3f)
            close()
            moveTo(14.5f, 3f)
            lineTo(20.0f, 3f)
            quadTo(21f, 3f, 21f, 4.0f)
            lineTo(21f, 9.5f)
            quadTo(21f, 10.5f, 20.0f, 10.5f)
            lineTo(14.5f, 10.5f)
            quadTo(13.5f, 10.5f, 13.5f, 9.5f)
            lineTo(13.5f, 4.0f)
            quadTo(13.5f, 3f, 14.5f, 3f)
            close()
            moveTo(4.0f, 13.5f)
            lineTo(9.5f, 13.5f)
            quadTo(10.5f, 13.5f, 10.5f, 14.5f)
            lineTo(10.5f, 20.0f)
            quadTo(10.5f, 21f, 9.5f, 21f)
            lineTo(4.0f, 21f)
            quadTo(3f, 21f, 3f, 20.0f)
            lineTo(3f, 14.5f)
            quadTo(3f, 13.5f, 4.0f, 13.5f)
            close()
            moveTo(14.5f, 13.5f)
            lineTo(20.0f, 13.5f)
            quadTo(21f, 13.5f, 21f, 14.5f)
            lineTo(21f, 20.0f)
            quadTo(21f, 21f, 20.0f, 21f)
            lineTo(14.5f, 21f)
            quadTo(13.5f, 21f, 13.5f, 20.0f)
            lineTo(13.5f, 14.5f)
            quadTo(13.5f, 13.5f, 14.5f, 13.5f)
            close()
          }
        }
        .build()
    return _GridView!!
  }

private var _GridView: ImageVector? = null
