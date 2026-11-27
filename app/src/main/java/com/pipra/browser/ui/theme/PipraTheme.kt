package com.pipra.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pipra.browser.AppThemeMode

/** Theme-aware color tokens for Pipra Light (default) and Dark palettes. */
data class PipraPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val cardSurface: Color,
    val cardElevated: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color
)

private val LightPalette = PipraPalette(
    isDark = false,
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    cardSurface = Color(0xFFF8FAFC),
    cardElevated = Color(0xFFEEF2F6),
    textPrimary = Color(0xFF111827),
    textMuted = Color(0xFF4B5563),
    border = Color(0xFFD8DFE8),
    accent = Color(0xFF2563EB),
    accentSoft = Color(0xFF1D4ED8),
    danger = Color(0xFFDC2626),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706)
)

private val DarkPalette = PipraPalette(
    isDark = true,
    background = Color(0xFF0B0C0E),
    surface = Color(0xFF14161A),
    cardSurface = Color(0xFF1A1D23),
    cardElevated = Color(0xFF22262E),
    textPrimary = Color(0xFFE6E8EC),
    textMuted = Color(0xFF9CA3AF),
    border = Color(0xFF2E333D),
    accent = Color(0xFF3B82F6),
    accentSoft = Color(0xFF60A5FA),
    danger = Color(0xFFEF4444),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B)
)

val LocalPipraColors = staticCompositionLocalOf { LightPalette }

/**
 * Dynamic accessor that resolves to LightPalette or DarkPalette depending on the active
 * PipraTheme, keeping legacy `PipraColors.*` property call-sites reactive to theme switches.
 */
object PipraColors {
    val MatteBlack: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.background
    val Charcoal: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.surface
    val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.cardSurface
    val CardElevated: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.cardElevated
    val SilverPrimary: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.textPrimary
    val SilverMuted: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.textMuted
    val SilverBorder: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.border
    val ElectricBlue: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.accent
    val ElectricBlueLight: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.accentSoft
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.danger
    val Success: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.success
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalPipraColors.current.warning
}

private val PipraLightColorScheme = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = LightPalette.textMuted,
    onSecondary = Color.White,
    background = LightPalette.background,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.surface,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.cardSurface,
    onSurfaceVariant = LightPalette.textMuted,
    outline = LightPalette.border,
    error = LightPalette.danger,
    onError = Color.White
)

private val PipraDarkColorScheme = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D355E),
    onPrimaryContainer = DarkPalette.accentSoft,
    secondary = DarkPalette.textMuted,
    onSecondary = DarkPalette.background,
    background = DarkPalette.background,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.surface,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.cardSurface,
    onSurfaceVariant = DarkPalette.textMuted,
    outline = DarkPalette.border,
    error = DarkPalette.danger,
    onError = Color.White
)

private val PipraTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun PipraTheme(
    themeMode: AppThemeMode = AppThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (isDark) DarkPalette else LightPalette
    val scheme = if (isDark) PipraDarkColorScheme else PipraLightColorScheme

    CompositionLocalProvider(LocalPipraColors provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PipraTypography,
            content = content
        )
    }
}
