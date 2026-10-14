package com.maxrave.simpmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.expect.ui.SystemBarAppearanceEffect
import com.maxrave.simpmusic.expect.ui.platformDynamicColorScheme

/**
 * Booming Music–influenced Material 3 dark scheme (tokens from mardous/BoomingMusic Color.kt).
 * Cool indigo primary on a blue-black surface — the look of their Expressive player screenshots.
 */
private fun boomingColorScheme(): ColorScheme =
    darkColorScheme(
        primary = Color(0xFFB6C4FF),
        onPrimary = Color(0xFF1D2D61),
        primaryContainer = Color(0xFF354479),
        onPrimaryContainer = Color(0xFFDCE1FF),
        secondary = Color(0xFFC2C5DD),
        onSecondary = Color(0xFF2B3042),
        secondaryContainer = Color(0xFF424659),
        onSecondaryContainer = Color(0xFFDEE1F9),
        tertiary = Color(0xFFE3BADA),
        onTertiary = Color(0xFF432740),
        tertiaryContainer = Color(0xFF5B3D57),
        onTertiaryContainer = Color(0xFFFFD7F5),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF121318),
        onBackground = Color(0xFFE3E1E9),
        surface = Color(0xFF121318),
        onSurface = Color(0xFFE3E1E9),
        surfaceVariant = Color(0xFF45464F),
        onSurfaceVariant = Color(0xFFC6C5D0),
        outline = Color(0xFF90909A),
        outlineVariant = Color(0xFF45464F),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE3E1E9),
        inverseOnSurface = Color(0xFF2F3036),
        inversePrimary = Color(0xFF4D5C92),
        surfaceBright = Color(0xFF38393F),
        surfaceDim = Color(0xFF121318),
        surfaceContainerLowest = Color(0xFF0D0E13),
        surfaceContainerLow = Color(0xFF1A1B21),
        surfaceContainer = Color(0xFF1E1F25),
        surfaceContainerHigh = Color(0xFF292A2F),
        surfaceContainerHighest = Color(0xFF34343A),
    )

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

// Overlays stay dark in both themes: they cover artwork, where content is always light.
private val LightAppColors =
    DarkAppColors.copy(
        shimmerBackground = shimmerBackgroundLight,
        shimmerLine = shimmerLineLight,
    )

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** True in dark theme. Provided by [AppTheme] so platform glass (blur/tint) can adapt reliably. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** True when the Booming Music–influenced theme is active (fixed indigo scheme, no seed). */
val LocalIsBoomingTheme = staticCompositionLocalOf { false }

/** True when the YouTube Music theme is active; also switches the type to the system sans (Roboto). */
val LocalIsYtmTheme = staticCompositionLocalOf { false }

/**
 * Whether liquid-glass surfaces may actually draw glass.
 *
 * Provided by [AppTheme] from the user's setting; the default is true so previews and anything
 * composed outside [AppTheme] keep today's look. The setting row is Android-only, so Desktop always
 * provides true — its capsule player and detail-screen buttons are glass by design, with no switch.
 */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }

/**
 * The dark scheme to use for immersive screens while the app itself is on the light theme.
 * Provided by [AppTheme], consumed by [ForceDarkContent]; null only outside of [AppTheme].
 */
val LocalForcedDarkColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

/** Parses "RRGGBB" or "AARRGGBB" (optionally "#"-prefixed) into a [Color]; null if malformed. */
fun parseThemeColorHex(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    val argb =
        when (clean.length) {
            6 -> "FF$clean"
            8 -> clean
            else -> return null
        }
    return argb.toLongOrNull(16)?.let { Color(it) }
}

/**
 * Neutral surfaces for the light theme, on a pure neutral-grey ramp (R=G=B, no seed tint);
 * primary/secondary/tertiary stay seed-derived. The page background is #FAFAFA (neutral tone 98,
 * the Material 3 stance) rather than pure white: a full-bleed #FFFFFF expanse glares on a large
 * desktop window, and even Apple — who pins systemBackground to white — puts #F2F2F7 behind
 * list pages. Pure white is kept for surfaceBright/surfaceContainerLowest so cards and sheets
 * still have a brighter-than-page tier to lift onto. Dark stays pure-black AMOLED: black does
 * not glare and saves OLED.
 */
private fun ColorScheme.withNeutralLightSurfaces(): ColorScheme =
    copy(
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFE2E2E2),
        onSurfaceVariant = Color(0xFF474747),
        surfaceTint = primary,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDADADA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF1F1F1),
        surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
        outline = Color(0xFF777777),
        outlineVariant = Color(0xFFC7C7C7),
        inverseSurface = Color(0xFF303030),
        inverseOnSurface = Color(0xFFF1F1F1),
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppTheme(
    themeMode: String = DataStoreManager.THEME_MODE_DARK,
    themeColorSource: String = DataStoreManager.THEME_COLOR_DEFAULT,
    customThemeColor: Color? = null,
    liquidGlassEnabled: Boolean = true,
    content:
        @Composable()
        () -> Unit,
) {
    val isDark = isDarkTheme(themeMode)
    val booming = themeMode == DataStoreManager.THEME_MODE_BOOMING
    val ytm = themeMode == DataStoreManager.THEME_MODE_YTM
    val wallpaperScheme =
        if (themeColorSource == DataStoreManager.THEME_COLOR_WALLPAPER && !booming && !ytm) {
            platformDynamicColorScheme(isDark)
        } else {
            null
        }
    val seedColor =
        if (themeColorSource == DataStoreManager.THEME_COLOR_CUSTOM && !booming) {
            customThemeColor ?: seed
        } else {
            seed
        }
    // Symmetric base: dark pins background/surface to pure black via isAmoled; light pins them to
    // pure white with a neutral-grey ramp (the seed otherwise tints the light neutrals warm/cream).
    // BOOMING ignores the seed and wallpaper: it is a fixed Material 3 look from Booming Music.
    val colorScheme =
        when {
            ytm -> remember { ytmColorScheme() }
            booming -> boomingColorScheme()
            wallpaperScheme != null -> wallpaperScheme
            else ->
                rememberDynamicColorScheme(
                    seedColor = seedColor,
                    isDark = isDark,
                    isAmoled = isDark,
                    style = PaletteStyle.TonalSpot,
                    modifyColorScheme = { cs -> if (isDark) cs else cs.withNeutralLightSurfaces() },
                )
        }
    // Immersive screens stay dark even at light theme (see [ForceDarkContent]). Resolve their scheme
    // once here instead of letting every such subtree build a palette of its own.
    val forcedDarkScheme =
        if (isDark || ytm) {
            colorScheme
        } else {
            rememberDynamicColorScheme(
                seedColor = seedColor,
                isDark = true,
                isAmoled = true,
                style = PaletteStyle.TonalSpot,
            )
        }
    SystemBarAppearanceEffect(isDark)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        content = {
            CompositionLocalProvider(
                LocalRippleConfiguration provides SoftRippleConfiguration,
                LocalContentColor provides colorScheme.onSurfaceVariant,
                LocalAppColors provides if (isDark) DarkAppColors else LightAppColors,
                LocalIsDarkTheme provides isDark,
                LocalIsBoomingTheme provides booming,
                LocalIsYtmTheme provides ytm,
                LocalForcedDarkColorScheme provides forcedDarkScheme,
                LocalLiquidGlassEnabled provides liquidGlassEnabled,
                content = content,
            )
        },
        typography = typo(colorScheme, ytm = ytm),
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

/**
 * Resolves the stored theme mode to a plain boolean.
 *
 * Pulled out of [AppTheme] so chrome living *outside* it can ask the same question — the desktop
 * title bar is drawn before AppTheme is entered, so it has no MaterialTheme to read and would
 * otherwise need its own copy of this `when`.
 */
@Composable
fun isDarkTheme(themeMode: String): Boolean =
    when (themeMode) {
        DataStoreManager.THEME_MODE_LIGHT -> false
        DataStoreManager.THEME_MODE_SYSTEM -> isSystemInDarkTheme()
        DataStoreManager.THEME_MODE_BOOMING -> true
        DataStoreManager.THEME_MODE_YTM -> true
        else -> true
    }
