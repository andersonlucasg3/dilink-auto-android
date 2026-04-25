package com.dilinkauto.server.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Car-optimized dark theme.
 * Large touch targets, high contrast, minimal distraction.
 */

val CarDark = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color.Black,
    secondary = Color(0xFF1A73E8),
    tertiary = Color(0xFF4CAF50),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1E2430),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = Color(0xFFEF5350)
)

val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp)
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
