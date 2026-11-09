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

/**
 * YouTube Music's palette: near-black page (#030303), stepped greys for raised surfaces, white
 * text with #AAAAAA secondary text, and a single red accent. Red is reserved for what YTM paints
 * red — the progress, the active tab marker, toggled states — so everything else stays neutral.
 */
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

/**
 * Semantic colors that sit outside the Material 3 ColorScheme.
 * Read them via [LocalAppColors] inside composables.
 */
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

/** True in dark theme, which is the only theme. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/**
 * The dark scheme to use for immersive screens while the app itself is on the light theme.
 * Provided by [AppTheme], consumed by [ForceDarkContent]; null only outside of [AppTheme].
 */
val LocalForcedDarkColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

/** The app's only theme: YouTube Music's dark palette and type. */
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

/**
 * A quieter ripple than the Material default, applied app-wide.
 *
 * Material 3's own alphas are tuned for light surfaces; on the near-black background this app
 * uses they read as a grey flash rather than a touch response. Each value is roughly 40% of
 * the default — enough to register the touch, not enough to wash the surface.
 *
 * The colour is left unspecified on purpose, so the ripple keeps deriving from LocalContentColor
 * and stays correct inside [ForceDarkContent] subtrees, which run a different scheme.
 *
 * This reaches bare `Modifier.clickable` too, not just Material components: MaterialTheme
 * already provides `material3.ripple()` as LocalIndication, and that is the one indication that
 * reads this configuration. Nothing extra has to be provided for it.
 *
 * The (color, rippleAlpha) constructor is deprecated in favour of one that also takes a `focus`,
 * but that three-argument version is internal to material3, and the two public replacements —
 * (color) and (focus, color) — drop rippleAlpha entirely. Adjusting ripple alpha therefore has
 * no non-deprecated path in this version, so the warning is suppressed rather than designed
 * around. Revisit once material3 opens up the full constructor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
private val SoftRippleConfiguration =
    RippleConfiguration(
        // Colour is deliberately left alone. Ripple derives it from LocalContentColor, which is
        // light on the dark theme and dark on the light one — always contrasting with what it
        // covers. Pinning a dark grey here instead made the ripple paint *darker* than the black
        // surface underneath, so a tap read as a sooty smudge rather than a highlight.
        // Alpha is the only lever that softens without changing which way the contrast runs.
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = 0.06f,
                focusedAlpha = 0.04f,
                hoveredAlpha = 0.03f,
                pressedAlpha = 0.04f,
            ),
    )

/**
 * Wraps immersive screens — artist, album, playlist, local playlist, podcast and the players — which
 * always draw over dark artwork and must therefore render dark even on the light theme.
 *
 * [LocalForceDarkText] alone is not enough: it only recolors text through [typo]. Icons, buttons and
 * every other Material default read [LocalContentColor] and [MaterialTheme.colorScheme], which at
 * light theme resolve to dark-on-light and turn grey and unreadable over the artwork. Providing the
 * dark scheme here fixes the whole subtree in one place.
 */
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

