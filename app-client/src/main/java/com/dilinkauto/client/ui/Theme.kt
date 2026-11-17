package com.dilinkauto.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.UiPalette

/**
 * Phone-app theme. Material3 dark scheme tuned for the DiLink Auto palette.
 * Shared slots come from [UiPalette] so the phone and car apps cannot drift.
 *
 * [ClientTypography] is the phone counterpart of the car app's type scale
 * (audit UX-07): the app previously shipped no Typography at all, so every
 * screen picked its own size and peer text drifted across 12/13/14sp while
 * captions sank to 11sp. Sizes here match the literals that were already in
 * use, so adopting a token is not a visual change — it is what stops the next
 * screen from inventing a fifteenth size.
 *
 * Nothing may go below 12sp; 13sp folded into [Typography.bodySmall] because
 * nothing about that text was a different rank from the 12sp copy beside it.
 */
val ClientTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),       // main screen title
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),        // settings title
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),           // sub-screen title
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),        // primary action
    titleSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),         // step action
    bodyLarge = TextStyle(fontSize = 15.sp),                                          // lead paragraph
    bodyMedium = TextStyle(fontSize = 14.sp),                                         // card text
    bodySmall = TextStyle(fontSize = 12.sp),                                          // secondary and captions
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)          // inline emphasis
)

@Composable
fun DiLinkAutoTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(UiPalette.PRIMARY),
        onPrimary = Color(UiPalette.ON_PRIMARY),
        secondary = Color(UiPalette.SECONDARY),
        background = Color(UiPalette.BACKGROUND),
        surface = Color(UiPalette.SURFACE),
        onBackground = Color(UiPalette.ON_BACKGROUND),
        onSurface = Color(UiPalette.ON_SURFACE)
    )
    MaterialTheme(colorScheme = darkColors, typography = ClientTypography, content = content)
}
