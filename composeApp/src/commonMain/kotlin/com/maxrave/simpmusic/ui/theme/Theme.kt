package com.maxrave.simpmusic.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.maxrave.simpmusic.expect.ui.SystemBarAppearanceEffect

val YtmRed = Color(0xFFFF0033)

private fun ytmColorScheme(): ColorScheme =
    darkColorScheme(
        primary = YtmRed,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF3A0010),
        onPrimaryContainer = Color.White,
        secondary = Color.White,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF2E2E2E),
        onSecondaryContainer = Color.White,
        tertiary = YtmRed,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF2E2E2E),
        onTertiaryContainer = Color.White,
        error = Color(0xFFFF6E6E),
        onError = Color.Black,
        errorContainer = Color(0xFF5C1111),
        onErrorContainer = Color.White,
        background = Color(0xFF030303),
        onBackground = Color.White,
        surface = Color(0xFF030303),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF212121),
        onSurfaceVariant = Color(0xFFAAAAAA),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF717171),
        outlineVariant = Color(0xFF303030),
        scrim = Color.Black,
        inverseSurface = Color.White,
        inverseOnSurface = Color.Black,
        inversePrimary = YtmRed,
        surfaceBright = Color(0xFF2E2E2E),
        surfaceDim = Color(0xFF030303),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0F0F0F),
        surfaceContainer = Color(0xFF181818),
        surfaceContainerHigh = Color(0xFF212121),
        surfaceContainerHighest = Color(0xFF2E2E2E),
    )

@Immutable
data class AppColors(
    val favorite: Color,
    val lyricActive: Color,
    val shimmerBackground: Color,
    val shimmerLine: Color,
    val overlay: Color,
    val overlayHeavy: Color,
)

private val DarkAppColors =
    AppColors(
        favorite = favoriteColor,
        lyricActive = lyricActiveColor,
        shimmerBackground = shimmerBackground,
        shimmerLine = shimmerLine,
        overlay = overlay,
        overlayHeavy = blackMoreOverlay,
    )

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

val LocalIsDarkTheme = staticCompositionLocalOf { true }

val LocalForcedDarkColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    content:
        @Composable()
        () -> Unit,
) {
    val colorScheme = remember { ytmColorScheme() }
    SystemBarAppearanceEffect(isDark = true)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        content = {
            CompositionLocalProvider(
                LocalRippleConfiguration provides SoftRippleConfiguration,
                LocalContentColor provides colorScheme.onSurfaceVariant,
                LocalAppColors provides DarkAppColors,
                LocalIsDarkTheme provides true,
                LocalForcedDarkColorScheme provides colorScheme,
                content = content,
            )
        },
        typography = typo(colorScheme),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
private val SoftRippleConfiguration =
    RippleConfiguration(
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = 0.06f,
                focusedAlpha = 0.04f,
                hoveredAlpha = 0.03f,
                pressedAlpha = 0.04f,
            ),
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ForceDarkContent(content: @Composable () -> Unit) {
    val darkScheme = LocalForcedDarkColorScheme.current ?: MaterialTheme.colorScheme
    MaterialExpressiveTheme(
        colorScheme = darkScheme,
        content = {
            CompositionLocalProvider(
                LocalForceDarkText provides true,
                LocalIsDarkTheme provides true,
                LocalContentColor provides darkScheme.onSurfaceVariant,
                LocalAppColors provides DarkAppColors,
                content = content,
            )
        },
        typography = typo(darkScheme, forceDark = true),
    )
}

