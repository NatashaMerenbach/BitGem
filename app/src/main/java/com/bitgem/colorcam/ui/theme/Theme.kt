package com.bitgem.colorcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    background = DarkBackground,
    onBackground = Color(0xFFE6E8EC),
    surface = DarkSurface,
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB9BDC6),
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

private val ColorCamTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * The app is a camera app, so the dark scheme is always used — a bright UI next to a live
 * preview is unreadable, and keeping the chrome constant means the analysed colors are the
 * only thing changing on screen.
 */
@Composable
fun ColorCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = ColorCamTypography,
        content = content,
    )
}
