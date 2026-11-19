package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme =
  lightColorScheme(
    primary = AccentCobalt,
    onPrimary = InkInverse,
    primaryContainer = AccentCobaltTint,
    onPrimaryContainer = AccentCobalt,
    secondary = SurfaceSecondary,
    onSecondary = InkPrimary,
    secondaryContainer = SurfaceSecondary,
    onSecondaryContainer = InkPrimary,
    background = BgDominant,
    onBackground = InkPrimary,
    surface = BgDominant,
    onSurface = InkPrimary,
    surfaceVariant = SurfaceSecondary,
    onSurfaceVariant = InkMuted,
    outline = BorderHairline,
  )

@Composable
fun LiveWallpaperTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = AppColorScheme, typography = Typography, content = content)
}
