package com.attendance.ble.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PersonaDarkColorScheme = darkColorScheme(
    primary = P3ElectricCyan,
    onPrimary = P3VoidBlack,
    primaryContainer = Color(0xFF0A224D),
    onPrimaryContainer = P3IceWhite,

    secondary = P3HyperBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0F326E),
    onSecondaryContainer = P3IceWhite,

    tertiary = P3LuminousGold,
    onTertiary = P3VoidBlack,
    tertiaryContainer = Color(0x33FFD000),
    onTertiaryContainer = P3LuminousGold,

    background = P3VoidBlack,
    onBackground = P3IceWhite,

    surface = P3CardGlass,
    onSurface = P3IceWhite,
    surfaceVariant = Color(0xFF0E1F42),
    onSurfaceVariant = P3SlateMuted,

    outline = P3CardBorder,
    outlineVariant = Color(0x2000E5FF),
)

@Composable
fun PersonaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PersonaDarkColorScheme,
        content = content
    )
}
