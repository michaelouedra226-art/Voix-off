package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val VoxaDarkColorScheme = darkColorScheme(
    primary = TurquoisePrimary,
    onPrimary = TurquoiseOnPrimary,
    primaryContainer = TurquoiseContainer,
    onPrimaryContainer = TurquoiseOnContainer,
    secondary = GoldSecondary,
    onSecondary = GoldOnSecondary,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = GoldOnContainer,
    tertiary = TagVoiceViolet,
    onTertiary = Color(0xFF28084A),
    background = MidnightBg,
    onBackground = StudioTextPrimary,
    surface = MidnightSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = MidnightCard,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLow = MidnightSurface,
    surfaceContainer = MidnightCard,
    surfaceContainerHigh = MidnightCardElevated,
    outline = MidnightBorder,
    outlineVariant = Color(0xFF1D3155),
    error = StudioError,
    onError = Color(0xFF3E080D),
    errorContainer = StudioErrorContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val VoxaLightColorScheme = lightColorScheme(
    primary = LightTurquoise,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = LightGold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = Color(0xFF7E22CE),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightCard,
    surfaceContainerHigh = Color(0xFFDCE5F2),
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val VoxaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

@Composable
fun VoxaStudioTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) VoxaDarkColorScheme else VoxaLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoxaTypography,
        shapes = VoxaShapes,
        content = content
    )
}
