package com.dilinkauto.server.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.UiPalette

/**
 * Car-optimized dark theme.
 * Large touch targets, high contrast, minimal distraction.
 */

val CarDark = darkColorScheme(
    primary = Color(UiPalette.PRIMARY),
    onPrimary = Color(UiPalette.ON_PRIMARY),
    secondary = Color(UiPalette.SECONDARY),
    tertiary = Color(0xFF4CAF50),
    background = Color(UiPalette.BACKGROUND),
    surface = Color(UiPalette.SURFACE),
    surfaceVariant = Color(0xFF1E2430),
    onBackground = Color(UiPalette.ON_BACKGROUND),
    onSurface = Color(UiPalette.ON_SURFACE),
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = Color(0xFFEF5350)
)

/**
 * Car type scale (audit UX-07). Screens used to hand-write their own sizes
 * (11–28sp), which let peer text drift apart and let two slots — `titleSmall`
 * and `bodySmall` — silently fall through to the Material defaults.
 *
 * Every size is deliberately large: this screen is read at arm's length in a
 * moving vehicle. Nothing here may go below 12sp.
 */
val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),       // launch branding
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),  // full-screen empty states
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),   // sub-screen title
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),        // dialog title
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),       // card / status title
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),        // section label
    bodyLarge = TextStyle(fontSize = 16.sp),                                         // list / menu item
    bodyMedium = TextStyle(fontSize = 14.sp),                                        // body copy
    bodySmall = TextStyle(fontSize = 12.sp),                                         // captions and hints
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),        // buttons
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),       // inline value badge
    labelSmall = TextStyle(fontSize = 12.sp)                                         // choice chips
)

@Composable
fun CarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarDark,
        typography = CarTypography,
        content = content
    )
}

// App category colors
val NavigationColor = Color(0xFF4CAF50)
val MusicColor = Color(0xFFE91E63)
val CommunicationColor = Color(0xFF2196F3)
val OtherColor = Color(0xFF9E9E9E)

// ── Semantic state colors (audit R3-DRY-06) ──
//
// Screens used to hard-code the same hex literals (0xFFFFA726, 0x161B22, …) in
// 20+ places, so a tweak to one indicator never reached the others. New call
// sites must use these tokens instead of raw Color(0xFF...) values.

/** Streaming / success indicators (green). */
val SuccessColor = Color(0xFF4CAF50)

/** Connecting / pending / attention / dev-mode highlights (amber). */
val WarningColor = Color(0xFFFFA726)

/** Informational icon tint (blue). */
val InfoColor = Color(0xFF64B5F6)

/** Pinned-item icon tint (yellow). */
val PinnedColor = Color(0xFFFFD54F)

/** Chrome background of the persistent nav bars / now-playing bar — one step darker than [CarDark]'s background. */
val NavBarBackgroundColor = Color(0xFF0A0E14)

/** Unselected choice-chip container. */
val ChipUnselectedColor = Color(0xFF21262D)

/** Elevated card container used by the settings screen. */
val CardElevatedColor = Color(0xFF1A2332)

/** Unfocused text-field border. */
val OutlineBorderColor = Color(0xFF2A2F3A)
