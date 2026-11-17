package com.college.feature.attendance.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.college.core.ui.theme.AppTheme

// Surface and background tones mapped to application theme
val cardBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surface
val cardBorder: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surfaceBorder
val logBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.surface

// High-contrast typography mapped to application theme
val textPrimary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textPrimary
val textSecondary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textSecondary
val textMuted: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.textMuted

// Functional system accents mapped to application theme
val primaryBlue: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurple
val primaryBlueBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurpleBg

val alertRed: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.dangerRed

val childPurple: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurple
val childPurpleBg: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primaryPurpleBg
