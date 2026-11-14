package com.maxrave.simpmusic.ui.theme

import androidx.compose.ui.graphics.Color

// ===== Brand =====

/**
 * Brand seed color. The whole Material 3 ColorScheme is generated from this
 * color at runtime — see [AppTheme].
 */
val seed = Color(0xFF8ECAE6)

// ===== Semantic colors (not derivable from the color scheme) =====

/** Liked/favorite state (heart buttons, favorite tiles). */
val favoriteColor = Color(0xFFFF4081)

/** Currently playing lyric line. */
val lyricActiveColor = Color(0xFFFFFF00)

val shimmerBackground = Color(0x7E383737)
val shimmerLine = Color(0xFF4D4848)

val overlay = Color(0x32242424)
val blackMoreOverlay = Color(0x8f242424)

// ===== Desktop shell =====
// Spotify-style layering for the desktop window: the window itself takes the extreme, panels
// step one shade back towards the middle so they read as raised. The light panel is not here —
// it comes from colorScheme.surfaceContainer, which already sits at the right distance.

// ===== Desktop window controls =====
// The macOS traffic lights. Fixed by convention rather than by theme — users read these by
// colour, so they stay the same in light and dark.

// ===== Legacy — do not add new usages =====

