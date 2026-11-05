package com.college.feature.attendance.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.college.core.ui.theme.AppTheme

// Surface and background tones mapped to application theme
val appBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.background
val cardBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surface
val cardBorder: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surfaceBorder
val cardBorderGlow: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurple.copy(alpha = 0.2f)
val logBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surface

// High-contrast typography mapped to application theme
val textPrimary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textPrimary
val textSecondary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textSecondary
val textMuted: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textMuted

// Functional system accents mapped to application theme
val primaryBlue: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurple
val primaryBlueBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurpleBg

val successGreen: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreen
val successGreenBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreenBg

val alertRed: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRed
val alertRedBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRedBg

val childPurple: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurple
val childPurpleBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurpleBg

val warningOrange: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrange
val warningOrangeBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrangeBg

// Radar and biometric verification accents
val neonMint: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreen
val neonMintBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreenBg
val neonMintGlow: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreen.copy(alpha = 0.2f)
val neonMintSubtle: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.successGreen.copy(alpha = 0.1f)

// Anti-proxy and spoofer warning accents
val crimsonRed: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRed
val crimsonRedBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRedBg
val crimsonRedGlow: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRed.copy(alpha = 0.3f)

// Sensory and camera exposure warning badges
val solarAmber: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrange
val solarAmberBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrangeBg
val solarAmberGlow: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrange.copy(alpha = 0.3f)
val amberAccent: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.accentOrange
