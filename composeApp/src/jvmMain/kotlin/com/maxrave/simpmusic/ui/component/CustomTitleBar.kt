package com.maxrave.simpmusic.ui.component

import com.maxrave.simpmusic.desktop.WindowsIntegration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.maxrave.simpmusic.extension.DesktopWindowChrome
import com.maxrave.simpmusic.ui.theme.typo
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

private val CaptionButtonWidth = 46.dp
private val CaptionGlyphSize = 10.dp
private val CaptionGlyphStroke = 1.dp

private val SubtleHover = Color.White.copy(alpha = 0.0605f)
private val SubtlePressed = Color.White.copy(alpha = 0.0419f)

private val CloseHover = Color(0xFFC42B1C)
private val ClosePressed = Color(0xFFB12719)

internal fun applyWorkAreaMaximizedBounds(window: Window) {
    val frame = window as? Frame ?: return
    val graphicsConfiguration = frame.graphicsConfiguration ?: return
    val screen = graphicsConfiguration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
    frame.maximizedBounds =
        Rectangle(
            screen.x + insets.left,
            screen.y + insets.top,
            screen.width - insets.left - insets.right,
            screen.height - insets.top - insets.bottom,
        )
}

@Composable
fun CustomTitleBar(
    title: String,
    windowState: WindowState,
    window: Window,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black,
    titleColor: Color = Color.White,
) {
    var isMaximized by remember { mutableStateOf(windowState.placement == WindowPlacement.Maximized) }

    var dragStartX by remember { mutableStateOf(0) }
    var dragStartY by remember { mutableStateOf(0) }
    var systemMove by remember { mutableStateOf(false) }

    LaunchedEffect(windowState.placement) {
        isMaximized = windowState.placement == WindowPlacement.Maximized
    }

    val barHeight = DesktopWindowChrome.TITLE_BAR_HEIGHT_DP.dp

    val toggleMaximize = {
        if (windowState.placement == WindowPlacement.Maximized) {
            windowState.placement = WindowPlacement.Floating
        } else {
            applyWorkAreaMaximizedBounds(window)
            windowState.placement = WindowPlacement.Maximized
        }
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(containerColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { toggleMaximize() },
                        )
                    }.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                val mouseLocation = MouseInfo.getPointerInfo().location
                                dragStartX = mouseLocation.x - window.x
                                dragStartY = mouseLocation.y - window.y
                                systemMove =
                                    windowState.placement != WindowPlacement.Maximized &&
                                    WindowsIntegration.beginSystemMove()
                            },
                            onDragEnd = { systemMove = false },
                            onDragCancel = { systemMove = false },
                            onDrag = { change, _ ->
                                change.consume()
                                if (systemMove) return@detectDragGestures
                                val mouseLocation = MouseInfo.getPointerInfo().location
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    windowState.placement = WindowPlacement.Floating
                                    dragStartX = (windowState.size.width.value / 2).toInt()
                                    dragStartY = 16
                                }
                                window.setLocation(
                                    mouseLocation.x - dragStartX,
                                    mouseLocation.y - dragStartY,
                                )
                                if (windowState.placement != WindowPlacement.Maximized) {
                                    systemMove = WindowsIntegration.beginSystemMove()
                                }
                            },
                        )
                    },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                style = typo().labelSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        CaptionButton(
            icon = WindowControlIcon.Minimize,
            contentDescription = "Minimize",
            barHeight = barHeight,
            onClick = { windowState.isMinimized = true },
        )
        CaptionButton(
            icon = if (isMaximized) WindowControlIcon.Restore else WindowControlIcon.Maximize,
            contentDescription = if (isMaximized) "Restore Down" else "Maximize",
            barHeight = barHeight,
            onClick = { toggleMaximize() },
        )
        CaptionButton(
            icon = WindowControlIcon.Close,
            contentDescription = "Close",
            barHeight = barHeight,
            isClose = true,
            onClick = onCloseRequest,
        )
    }
}

private enum class WindowControlIcon {
    Minimize,
    Maximize,
    Restore,
    Close,
}

@Composable
private fun CaptionButton(
    icon: WindowControlIcon,
    contentDescription: String,
    barHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    isClose: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background =
        when {
            isPressed -> if (isClose) ClosePressed else SubtlePressed
            isHovered -> if (isClose) CloseHover else SubtleHover
            else -> Color.Transparent
        }

    Box(
        modifier =
            Modifier
                .size(width = CaptionButtonWidth, height = barHeight)
                .background(background)
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(CaptionGlyphSize)) {
            val stroke = CaptionGlyphStroke.toPx()
            val glyph = Color.White
            when (icon) {
                WindowControlIcon.Minimize ->
                    drawLine(
                        color = glyph,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Square,
                    )

                WindowControlIcon.Maximize ->
                    drawRect(
                        color = glyph,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )

                WindowControlIcon.Restore -> {
                    val inset = 2f * stroke
                    drawRect(
                        color = glyph,
                        topLeft = Offset(inset + stroke / 2f, stroke / 2f),
                        size = Size(size.width - inset - stroke, size.height - inset - stroke),
                        style = Stroke(width = stroke),
                    )
                    drawRect(
                        color = glyph,
                        topLeft = Offset(stroke / 2f, inset + stroke / 2f),
                        size = Size(size.width - inset - stroke, size.height - inset - stroke),
                        style = Stroke(width = stroke),
                    )
                }

                WindowControlIcon.Close -> {
                    drawLine(
                        color = glyph,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Square,
                    )
                    drawLine(
                        color = glyph,
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Square,
                    )
                }
            }
        }
    }
}
