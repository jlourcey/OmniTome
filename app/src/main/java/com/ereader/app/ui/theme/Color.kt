package com.ereader.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Material 3 color palette for the E-Reader app ────────────────────────────
// These are used as fallbacks when the device does NOT support dynamic color
// (Android 11 and below). On Android 12+ the system extracts colors from the
// user's wallpaper automatically via dynamicLightColorScheme / dynamicDarkColorScheme.

// Primary — a deep teal/indigo that works well for book-related UI
val md_theme_light_primary            = Color(0xFF1B6B8A)
val md_theme_light_onPrimary          = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer   = Color(0xFFBEE9FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001F2A)

// Secondary
val md_theme_light_secondary            = Color(0xFF4D616B)
val md_theme_light_onSecondary          = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer   = Color(0xFFD0E6F1)
val md_theme_light_onSecondaryContainer = Color(0xFF081E26)

// Surface / Background
val md_theme_light_surface     = Color(0xFFFBFCFE)
val md_theme_light_onSurface   = Color(0xFF191C1E)
val md_theme_light_background  = Color(0xFFFBFCFE)
val md_theme_light_error       = Color(0xFFBA1A1A)

// Dark theme equivalents
val md_theme_dark_primary            = Color(0xFF70D4FF)
val md_theme_dark_onPrimary          = Color(0xFF003547)
val md_theme_dark_primaryContainer   = Color(0xFF004D66)
val md_theme_dark_onPrimaryContainer = Color(0xFFBEE9FF)

val md_theme_dark_secondary            = Color(0xFFB4CAD5)
val md_theme_dark_onSecondary          = Color(0xFF1E333C)
val md_theme_dark_secondaryContainer   = Color(0xFF354A53)
val md_theme_dark_onSecondaryContainer = Color(0xFFD0E6F1)

val md_theme_dark_surface     = Color(0xFF191C1E)
val md_theme_dark_onSurface   = Color(0xFFE1E3E5)
val md_theme_dark_background  = Color(0xFF191C1E)
val md_theme_dark_error       = Color(0xFFFFB4AB)

// ── Reader-specific colors ────────────────────────────────────────────────────
// These are not part of Material 3 but are used in ReaderControlsHud
val ReaderDayBackground   = Color(0xFFFFFFFF)
val ReaderDayText         = Color(0xFF1A1A1A)
val ReaderNightBackground = Color(0xFF1A1A2E)
val ReaderNightText       = Color(0xFFE0E0E0)
val ReaderSepiaBackground = Color(0xFFF4ECD8)
val ReaderSepiaText       = Color(0xFF5C4033)
// Paper / E-Ink: warm off-white like actual e-reader screens, near-black warm text
val ReaderPaperBackground = Color(0xFFF0EBE0)
val ReaderPaperText       = Color(0xFF1C1410)
