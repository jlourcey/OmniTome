package com.ereader.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App typography — defines text styles used throughout the UI.
 *
 * Material 3 has a type scale with named roles:
 *   displayLarge / displayMedium / displaySmall  → hero text, splash screens
 *   headlineLarge / headlineMedium / headlineSmall → section headings
 *   titleLarge / titleMedium / titleSmall         → card titles, app bar
 *   bodyLarge / bodyMedium / bodySmall            → body text
 *   labelLarge / labelMedium / labelSmall         → buttons, chips, captions
 *
 * We use the system default font (Roboto on Android) for the UI shell.
 * The reader uses its own font system via Readium's CSS injection.
 */
val Typography = Typography(
    // App bar title "My Library"
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // Book card title
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // Author name, series badge
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // Filter chip labels
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
