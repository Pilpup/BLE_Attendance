package com.college.core.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Color palette tokens supporting light and dark themes
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val searchPillBg: Color,
    val searchPillBorder: Color,
    val primaryPurple: Color,
    val primaryPurpleBg: Color,
    val accentOrange: Color,
    val accentOrangeBg: Color,
    val successGreen: Color,
    val successGreenBg: Color,
    val dangerRed: Color,
    val dangerRedBg: Color,
    val warningAmber: Color,
    val warningAmberBg: Color,
    val infoBlue: Color,
    val infoBlueBg: Color,
    val isDark: Boolean
)

// Default light mode color tokens
val LightColors = AppColors(
    background = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceBorder = Color(0xFFE5E7EB),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),
    divider = Color(0xFFF3F4F6),
    searchPillBg = Color(0xFFFFFFFF),
    searchPillBorder = Color(0xFFE5E7EB),
    primaryPurple = Color(0xFF7C3AED),
    primaryPurpleBg = Color(0x1F7C3AED),
    accentOrange = Color(0xFFFF7A00),
    accentOrangeBg = Color(0x1FFF7A00),
    successGreen = Color(0xFF10B981),
    successGreenBg = Color(0x1F10B981),
    dangerRed = Color(0xFFEF4444),
    dangerRedBg = Color(0x1FEF4444),
    warningAmber = Color(0xFFF59E0B),
    warningAmberBg = Color(0x1FF59E0B),
    infoBlue = Color(0xFF2563EB),
    infoBlueBg = Color(0x1F2563EB),
    isDark = false
)

// Default dark mode color tokens
val DarkColors = AppColors(
    background = Color(0xFF0C0A17),
    surface = Color(0xFF161326),
    surfaceBorder = Color(0xFF2E274D),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFA5B4FC),
    textMuted = Color(0xFF6B7280),
    divider = Color(0xFF231F3D),
    searchPillBg = Color(0xFF161326),
    searchPillBorder = Color(0xFF2E274D),
    primaryPurple = Color(0xFF8B5CF6),
    primaryPurpleBg = Color(0x338B5CF6),
    accentOrange = Color(0xFFFF7A00),
    accentOrangeBg = Color(0x33FF7A00),
    successGreen = Color(0xFF10B981),
    successGreenBg = Color(0x3310B981),
    dangerRed = Color(0xFFEF4444),
    dangerRedBg = Color(0x33EF4444),
    warningAmber = Color(0xFFF59E0B),
    warningAmberBg = Color(0x33F59E0B),
    infoBlue = Color(0xFF3B82F6),
    infoBlueBg = Color(0x333B82F6),
    isDark = true
)

// CompositionLocal for theme color palette
val LocalAppColors = staticCompositionLocalOf { LightColors }

// Global accessor for active theme colors
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Adaptive root theme wrapper adjusting colors and system bar styles
@Composable
fun CollegeAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    // Synchronize system status and navigation bar icons with theme mode
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            // Match the window background to the app theme so IME
            // adjustResize transitions (keyboard open/close) never reveal a background color flash.
            val backgroundDrawable = ColorDrawable(colors.background.toArgb())
            window.setBackgroundDrawable(backgroundDrawable)
            window.decorView.setBackgroundColor(colors.background.toArgb())
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = colors.primaryPurple,
            secondary = colors.accentOrange,
            background = colors.background,
            surface = colors.surface
        )
    } else {
        lightColorScheme(
            primary = colors.primaryPurple,
            secondary = colors.accentOrange,
            background = colors.background,
            surface = colors.surface
        )
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content
        )
    }
}
