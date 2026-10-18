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

// Windows 11 caption metrics. A standard caption button is 46 wide and fills the
// full height of the caption strip with no inset. That flush fill is what lets
// the close button be hit by throwing the pointer into the top-right screen
// corner, and it reads as native more than the glyph shapes themselves do.
//
// The bar height comes from DesktopWindowChrome rather than a local constant:
// getScreenSizeInfo() subtracts that same value from the window height to derive
// the content area, so a bar that disagrees with it puts every layout below out
// by the difference.
private val CaptionButtonWidth = 46.dp
private val CaptionGlyphSize = 10.dp
private val CaptionGlyphStroke = 1.dp

// Win11 dark-theme subtle fills, used for the minimize/maximize hover states.
private val SubtleHover = Color.White.copy(alpha = 0.0605f)
private val SubtlePressed = Color.White.copy(alpha = 0.0419f)

// The close button is the one caption control with its own accent: Win11 uses
// a red fill with a white glyph rather than the neutral subtle fill.
private val CloseHover = Color(0xFFC42B1C)
private val ClosePressed = Color(0xFFB12719)

/**
 * Constrains the frame to maximize into the screen work area rather than the
 * full screen bounds.
 *
 * A decorated window gets this from the OS for free, but an undecorated one
 * maximizes edge-to-edge and ends up with its bottom strip — here the player
 * bar — buried under the taskbar. Pinning `maximizedBounds` to the work area
 * reported for the window's current screen restores the normal behaviour, and
 * because it is read per screen it stays correct on a multi-monitor setup and
 * for a taskbar docked to any edge.
 *
 * Must be applied *before* the placement changes: AWT reads the bounds at the
 * moment it maximizes.
 */
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

/**
 * Custom title bar for the JVM desktop application, styled to Windows 11
 * conventions: minimize / maximize / close sit flush against the right edge in
 * that order, as flat full-height rectangles with 10dp line-art glyphs.
 *
 * The window is still `undecorated`, so this bar also owns drag-to-move and
 * double-click-to-maximize. Those live on the title region only, because
 * dragging from a caption button would otherwise move the window instead of
 * pressing the button.
 */
@Composable
fun CustomTitleBar(
    title: String,
    windowState: WindowState,
    window: Window,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
    // Passed in rather than read from MaterialTheme: this bar is drawn outside AppTheme, so
    // MaterialTheme here would hand back the default light scheme no matter what the user picked.
    containerColor: Color = Color.Black,
    titleColor: Color = Color.White,
) {
    var isMaximized by remember { mutableStateOf(windowState.placement == WindowPlacement.Maximized) }

    // Track drag start position
    var dragStartX by remember { mutableStateOf(0) }
    var dragStartY by remember { mutableStateOf(0) }
    // Windows is moving the window for this drag (see WindowsIntegration.beginSystemMove).
    var systemMove by remember { mutableStateOf(false) }

    // Update isMaximized when window state changes
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
        // Draggable title region: everything left of the caption buttons.
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
                                // Windows' own move loop is what FancyZones and Snap respond to.
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
                                // If maximized, restore before moving
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    windowState.placement = WindowPlacement.Floating
                                    // Recalculate drag offset after restore
                                    dragStartX = (windowState.size.width.value / 2).toInt()
                                    dragStartY = 16
                                }
                                window.setLocation(
                                    mouseLocation.x - dragStartX,
                                    mouseLocation.y - dragStartY,
                                )
                                // Restored from maximized under the pointer: Windows takes the rest of the drag.
                                if (windowState.placement != WindowPlacement.Maximized) {
                                    systemMove = WindowsIntegration.beginSystemMove()
                                }
                            },
                        )
                    },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Windows left-aligns the caption title rather than centring it.
            Text(
                text = title,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                style = typo().labelSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Caption buttons, in Windows order: minimize, maximize/restore, close.
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
        // Glyphs are drawn rather than taken from a font: Segoe MDL2 Assets is
        // not guaranteed present on every Windows install and is absent from
        // the Linux/macOS builds that share this file, and vector-drawing the
        // four shapes keeps them crisp at every DPI.
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
                    // Two offset outlines: the rear pane peeks out top-right,
                    // matching the Windows "restore down" glyph.
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
