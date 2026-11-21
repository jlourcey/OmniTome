package com.ereader.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// ── Static color schemes (used on Android 11 and below) ──────────────────────

private val LightColorScheme = lightColorScheme(
    primary            = md_theme_light_primary,
    onPrimary          = md_theme_light_onPrimary,
    primaryContainer   = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary            = md_theme_light_secondary,
    onSecondary          = md_theme_light_onSecondary,
    secondaryContainer   = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    surface    = md_theme_light_surface,
    onSurface  = md_theme_light_onSurface,
    background = md_theme_light_background,
    error      = md_theme_light_error
)

private val DarkColorScheme = darkColorScheme(
    primary            = md_theme_dark_primary,
    onPrimary          = md_theme_dark_onPrimary,
    primaryContainer   = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary            = md_theme_dark_secondary,
    onSecondary          = md_theme_dark_onSecondary,
    secondaryContainer   = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    surface    = md_theme_dark_surface,
    onSurface  = md_theme_dark_onSurface,
    background = md_theme_dark_background,
    error      = md_theme_dark_error
)

/**
 * EReaderTheme — wrap every Composable in this at the root of the UI tree.
 *
 * Behavior by Android version:
 *  - Android 12+ (API 31+): uses Dynamic Color, which extracts the color palette
 *    from the user's current wallpaper. The app adapts to their personal style.
 *  - Android 11 and below: falls back to our hand-crafted teal/indigo palette.
 *
 * [darkTheme] defaults to the system setting but can be overridden (e.g. to
 * force dark mode in the reader screen regardless of system preference).
 */
@Composable
fun EReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic Color — available on Android 12+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else           dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
