package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.ui.theme.typo

private val ChipShape = RoundedCornerShape(8.dp)

/**
 * A filter chip in YouTube Music's form: a rounded rectangle with a quiet tonal fill, inverted
 * (light pill, dark label) when selected. No outline and no check icon — fill-versus-inverted is
 * the whole signal, it survives without colour vision, and an icon would widen the selected chip
 * and reflow the scrolling row under the finger that just tapped it.
 */
@Composable
fun Chip(
    isAnimated: Boolean = false,
    isSelected: Boolean = false,
    text: String,
    onClick: () -> Unit,
) {
    InfiniteBorderAnimationView(
        isAnimated = isAnimated && isSelected,
        brush = Brush.sweepGradient(listOf(Color.Gray, Color.White)),
        backgroundColor = Color.Transparent,
        contentPadding = 0.dp,
        borderWidth = 1.dp,
        shape = ChipShape,
        oneCircleDurationMillis = 2500,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            ElevatedFilterChip(
                shape = ChipShape,
                elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 0.dp),
                colors =
                    FilterChipDefaults.elevatedFilterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface,
                    ),
                onClick = { onClick.invoke() },
                label = {
                    // Colour left unspecified so the chip's own label colours (incl. the inverted one) apply.
                    Text(text, maxLines = 1, style = typo().bodyMedium.copy(color = Color.Unspecified))
                },
                border = null,
                selected = isSelected,
            )
        }
    }
}
