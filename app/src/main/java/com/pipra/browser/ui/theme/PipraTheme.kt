package com.pipra.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PipraColors {
    val MatteBlack = Color(0xFF0B0C0E)
    val Charcoal = Color(0xFF14161A)
    val CardSurface = Color(0xFF1A1D23)
    val CardElevated = Color(0xFF22262E)
    val SilverPrimary = Color(0xFFE6E8EC)
    val SilverMuted = Color(0xFF9CA3AF)
    val SilverBorder = Color(0xFF2E333D)
    val ElectricBlue = Color(0xFF3B82F6)
    val ElectricBlueLight = Color(0xFF60A5FA)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
}

private val PipraDarkColorScheme = darkColorScheme(
    primary = PipraColors.ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D355E),
    onPrimaryContainer = PipraColors.ElectricBlueLight,
    secondary = PipraColors.SilverMuted,
    onSecondary = PipraColors.MatteBlack,
    background = PipraColors.MatteBlack,
    onBackground = PipraColors.SilverPrimary,
    surface = PipraColors.Charcoal,
    onSurface = PipraColors.SilverPrimary,
    surfaceVariant = PipraColors.CardSurface,
    onSurfaceVariant = PipraColors.SilverMuted,
    outline = PipraColors.SilverBorder,
    error = PipraColors.Danger,
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
fun PipraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PipraDarkColorScheme,
        typography = PipraTypography,
        content = content
    )
}
